# MOSIP Face Liveness & PAD Engine — Configuration Guide

## Base Configuration

Built via `LivenessConfig.builder()`:

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `livenessEnabled` | boolean | `true` | Enable/disable liveness verification. When disabled, passive scoring and active challenges are skipped, but the face-detection gate and **PAD stay fail-closed** — a confirmed presentation attack still rejects. Every bypassing session is audited as `LIVENESS_DISABLED`. |
| `activeLivenessEnabled` | boolean | `true` | Enable/disable active (Stage 2) liveness. When disabled and passive fails, session hard-fails instead of escalating. |
| `passiveThreshold` | double | `0.80` | Liveness confidence threshold in [0, 1]. Median score ≥ threshold → proceed; < threshold → escalate (or fail). |
| `minFaceQuality` | double | `0.50` | Minimum face quality score in [0, 1]. Frames below this are rejected as RETRYABLE_ERROR. |
| `passiveMinFrames` | int | `5` | Minimum consecutive frames before a passive liveness decision is made. |
| `passiveWindowFrames` | int | `7` | Sliding window size for temporal median voting. Must be ≥ `passiveMinFrames`. |
| `minChallengeCount` | int | `2` | Minimum number of distinct challenges that must pass before active liveness is satisfied. |
| `challengeTimeoutMs` | long | `15,000` | How long (ms) a challenge stays open for the user to perform the action. Lowered from 60,000 to 15,000 at the product's request; the floor below is applied at runtime. Exceeding the window consumes one retry. |
| `min-challenge-window-ms` (`mosip.liveness`) | long | `15,000` (hard min `1,000`) | Floor a stored `challengeTimeoutMs` is raised to, so a shortened or legacy policy row cannot turn a person who needed a moment into a certain failure. Raising it only makes the flow more patient; production lowers it only in tests (the e2e timeout path runs 3s windows instead of 2 × 15s). |
| `maxRetries` | int | `2` | Number of retries (on top of the initial attempt) before hard failure. Total attempts = 1 + maxRetries. |
| `supportedChallengeTypes` | Set\<ChallengeType\> | All 5 types | Pool of challenge types the engine may select from. |
| `onRepeatedFailure` | RepeatedFailureAction | `LOCK_OUT` | Behavior when retry budget is exhausted: LOCK_OUT, FALLBACK, or ESCALATE_TO_OPERATOR. |
| `workflowOverrides` | Map\<WorkflowType, LivenessPolicy\> | empty | Per-workflow policy overrides (see below). |

### Validation Rules

Validated by `EffectivePolicyValidator` at **session creation** (the frozen
snapshot must be valid before it is persisted) and on every
`PUT /api/v1/config/{workflowType}`. A violation is a 400.

- `passiveThreshold` ∈ **(0, 1]** — a threshold of exactly 0 would pass every frame
- `minFaceQuality` ∈ [0, 1]
- `passiveMinFrames` ≥ 1
- `passiveWindowFrames` ≥ `passiveMinFrames`
- `minChallengeCount` ≥ 1 (and **≤ the number of configured challenge types** when active liveness is on)
- `maxRetries` ≥ 0
- `challengeTimeoutMs` ≥ `mosip.liveness.min-challenge-window-ms`
  (**15,000** by default, never below the engine's 1,000 clamp) — a shorter
  window is refused by the API and again when a session freezes its snapshot
- `mosip.liveness.min-challenge-window-ms` ≥ `1,000` (values below are clamped up)

---

## Auditing policy edits

Every `PUT /api/v1/config/{workflowType}` writes one `CONFIG_CHANGED` entry to
`audit_logs` **in the same transaction as the update**, so a saved policy can
never be silent and a rolled-back update can never leave a phantom entry. A
rejected request (no/invalid admin key, invalid value) writes nothing at all.

```
GET /api/v1/config/audit?limit=50     # newest first, 1–500
GET /api/v1/config/audit?limit=50&workflowType=OPERATOR   # filtered in SQL, not in the client
GET /api/v1/config/audit/verify          # walks the whole tamper-evident chain
```

`workflowType` is optional; omitting it returns every workflow. The filter is  pushed into the query (`audit_logs.workflow_type`, indexed by V8) because the
audit table grows a row per frame decision — filtering after the fact would page
through the whole pipeline history to answer a policy question. An unknown value
is a 400, not a silently unfiltered feed.

Every `CONFIG_CHANGED` row is chained (`prev_hash` → `entry_hash`), and
PostgreSQL rejects `UPDATE` and `DELETE` on `audit_logs`. `/audit/verify`
reports the first break and whether it was an edited row or a deleted one.

```json
{
  "eventType": "CONFIG_CHANGED",
  "createdAt": "2026-10-04T12:34:56.789Z",
  "details": {
    "workflowType": "OPERATOR",
    "action": "UPDATED",
    "actor": "key:9f2c1a7b4e0d",
    "changes": {
      "passiveThreshold": { "from": 0.80, "to": 0.93 },
      "maxRetryCount": { "from": 3, "to": 4 }
    }
  }
}
```

- **Only fields that actually moved** are listed. A `PUT` that resubmits the
  current values is still recorded (someone touched the config) but with an empty
  `changes` object — a real move and a no-op must not read alike.
- **`action`** is `CREATED` when the `PUT` was the one that first created the
  `config_policies` row, otherwise `UPDATED`.
- **`actor`** is a truncated SHA-256 fingerprint of the presented admin key.
  The key itself is never stored, logged or echoed; the fingerprint only
  correlates edits made with the same key.
- Config events have **no session**, so they never appear in a session's
  `audit` trail, and that trail can never pick one up (`session_id IS NULL`).
- The feed is an open read, like `GET /api/v1/config/{workflowType}` (which
  already publishes the current policy). Only writes require the admin key.

ponytail: the feed is a bounded, newest-first read of one table with no
per-workflow server-side filter — the workflow type lives inside the JSON
`details` column, which is deliberately unqueryable (text, identical on H2 and  PostgreSQL). Filter client-side, as `static/console.js` does.

- `supportedChallengeTypes` must not be empty (and if `activeLivenessEnabled`, must have ≥ 1 type)

---

## Choosing `passiveThreshold` — calibrate, don't guess

A threshold number only means something relative to the **score distribution of
the scorer that produced the scores**. Two things changed to make the number
derivable instead of asserted:

1. **The score is a liveness confidence.** With `mosip.liveness.backend: auto`
   the passive score is MiniFASNet-V2's live-class probability (model
   confidence), not the old image-quality heuristic. A threshold chosen against
   the old scale does not transfer to the new scale — re-run the sweep after any
   scorer change.
2. **The decision is a median over 5 scored frames**, not a single frame, so
   calibration must evaluate the same windowed decision (it does).

### The sweep endpoint

```
GET /api/v1/eval/threshold-sweep?targetBpcer=0.02
```

Returns one row per threshold (0.05 … 0.95, step 0.05):

| Field | Meaning |
|-------|---------|
| `bpcer` | fraction of bona-fide windows that would **escalate to an active challenge** at this threshold (user-experience cost) |
| `apcer` | fraction of attack windows that would **pass passive** at this threshold (security cost); `null` when unmeasured |
| `acer` | mean of the two; `null` when APCER is unmeasured |
| `recommendedThreshold` | the operating point (see rule below) |
| `recommendationBasis` / `note` | why, and what data was (not) available |

**Inputs:** bona-fide windows are the first `passiveMinFrames` scores of every
session that passed, taken from `frame_events` (real captures). Attack windows
are recorded presentation-attack sessions when any exist; otherwise a clearly
labelled **proxy corpus** (simulated print/screen degradations,
`attackDataIsProxy: true`). With neither, `apcer` is `null` and the note says
`APCER unmeasured` — never a fabricated zero.

**Recommendation rule:** the *highest* threshold that keeps
`BPCER ≤ targetBpcer` with `APCER = 0` (stricter is safer, so within the UX
budget we take the strictest point); failing that, minimum `ACER`; failing
that, no recommendation.

### Applying the result

```bash
# read the table
curl 'http://localhost:8000/api/v1/eval/threshold-sweep?targetBpcer=0.02'

# apply the recommended value per workflow (DB row wins at runtime)
# PUT requires the admin API key (fail-closed: set MOSIP_ADMIN_API_KEY first)
curl -X PUT http://localhost:8000/api/v1/config/RESIDENT \
     -H 'Content-Type: application/json' \
     -H "X-Admin-API-Key: $MOSIP_ADMIN_API_KEY" \
     -d '{"passiveThreshold": 0.90}'
```

Defaults are unified on `0.80` across `LivenessConfig`, `application.yml`, the
`config_policies` seed and the DB-miss fallback (V2 migration) — previously the
seed said 0.75 while everything else said 0.80. `targetBpcer` is the knob for
how much UX you spend: 0.02 means at most ~2% of genuine users are pushed into
an active challenge.

---

## Service knobs (`application.yml`, `mosip.liveness.*`)

| Key | Default | Description |
|-----|---------|-------------|
| `backend` | `auto` | `auto` = use the bundled MiniFASNet ONNX model when it loads, heuristic fallback otherwise; `heuristic` = force the OpenCV quality heuristic (model-less CI/debug). |
| `model-path` | *(blank)* | Filesystem override for the model; blank uses the bundled `classpath:models/minifasnet_v2.onnx` (SHA-256 `d7b3cd9b…` verified on load). |
| `passive-threshold` | `0.80` | Mirrors `LivenessConfig.DEFAULT_PASSIVE_THRESHOLD`; the `config_policies` DB row wins at runtime. |
| `min-face-quality`, `passive-min-frames`, `passive-window-frames`, `min-challenge-count`, `challenge-timeout-ms`, `max-retries` | see yml | Engine defaults for the embedded (library) path. |

---

## Per-Workflow Policy Overrides

Each workflow (RESIDENT_REGISTRATION, OPERATOR_AUTH, SUPERVISOR_AUTH) can override any subset of the base config via `LivenessPolicy`:

```java
LivenessConfig config = LivenessConfig.builder()
    .passiveThreshold(0.80)
    .workflowOverrides(Map.of(
        WorkflowType.SUPERVISOR_AUTH, new LivenessPolicy()
            .withPassiveThreshold(0.92)       // stricter for supervisors
            .withMinChallengeCount(3),         // more challenges required
        WorkflowType.OPERATOR_AUTH, new LivenessPolicy()
            .withPassiveThreshold(0.85)
            .withActiveLivenessEnabled(false)  // skip active for operators
    ))
    .build();
```

Any field not overridden inherits the base value. The merge happens in `LivenessConfig.effectivePolicy(workflow)`.

---

## Repeated Failure Actions

What the session does once the active-challenge retry budget (`maxRetryCount`)
is exhausted. **All three are terminal and none grants another challenge** — extra
attempts belong in `maxRetryCount`, not in the failure action. They differ only in
the outcome signal and whether the client may open a fresh session. Wired into the
HTTP decision path (`DecisionEngineService.processChallengeValidation`), so a
per-workflow value is real behaviour, not just stored config.

| Action | Session | `action` returned | `failureReason` | `mayRetrySession` |
|--------|---------|-------------------|-----------------|-------------------|
| `LOCK_OUT` | FAILED | `locked` | `max_retries_exceeded:locked_out` | `false` |
| `ESCALATE_TO_OPERATOR` | FAILED | `escalate_to_operator` | `max_retries_exceeded:escalation_required` | `false` |
| `FALLBACK` (seeded as `ALLOW_RETRY`) | FAILED | `failed` | `max_retries_exceeded` | `true` |

`escalate_to_operator` is a signal only (there is no escalation endpoint of its
own); the client routes the subject to an operator.

---

## Per-user-type policy at session start

The user type (`RESIDENT` / `OPERATOR` / `SUPERVISOR`) is chosen **once**, when the
session is created. The server resolves that workflow's effective policy, validates
it, and **freezes it on the session** (`policy_snapshot`). The decision engine then
uses the frozen snapshot for the whole session, so an admin edit to
`config_policies` cannot change an in-flight session's operating point. Legacy rows
with no snapshot fall back to a live read.

The three workflows seed **distinct** operating points (single source of truth:
`WorkflowPolicyDefaults`, mirrored by the `V4__session_policy_snapshot.sql` seed and
the `ConfigController` lazy-create path):

| Workflow | `passiveThreshold` | `minChallengeCount` | `challengeTimeoutMs` | `maxRetryCount` | `onRepeatedFailure` | challenges |
|----------|--------------------|---------------------|----------------------|-----------------|---------------------|------------|
| `RESIDENT` | 0.80 | 1 | 20000 | 3 | `ESCALATE_TO_OPERATOR` | blink, smile |
| `OPERATOR` | 0.82 | 1 | 15000 | 2 | `ALLOW_RETRY` | blink, turn_left, turn_right |
| `SUPERVISOR` | 0.85 | 2 | 15000 | 1 | `LOCK_OUT` | blink, smile, turn_left, turn_right |

A resident may be physically assisted by an officer, so a failed resident check
escalates to an operator; operator/supervisor authentication must never escalate to
an operator, so those workflows fail/retry instead. All values are defaults —
override any field at runtime with `PUT /api/v1/config/{workflowType}`.

```bash
# The session-create response carries the resolved, frozen policy:
curl -s -X POST localhost:8000/api/v1/sessions \
  -H 'Content-Type: application/json' \
  -d '{"workflowType":"SUPERVISOR","deviceId":"L1-CAM-01"}' | python3 -m json.tool
```

> Live preview vs frozen snapshot: `GET /api/v1/config/{workflowType}/effective`
> shows what a **new** session would get; a session's own `policy` field shows what
> that **in-flight** session is actually using.

---

## Behavior on Failure

| Condition | User Message (generic, never reveals detection method) | Retryable? |
|-----------|--------------------------------------------------------|------------|
| Face not detected | "No face detected. Please look at the camera." | Yes |
| Multiple faces | "Multiple faces detected. Only one person may be in the frame." | Yes |
| Poor face quality | "Face is not clear. Please improve lighting and hold still." | Yes |
| Liveness below threshold (active disabled) | "Liveness check incomplete. Please continue looking at the camera." | No (hard fail) |
| PAD failure | "Face verification could not be completed. Please try again." | **No** (terminal, distinct audit) |
| Challenge failure | "Verification action was not completed. Please try again." | Yes (retry) |
| Challenge timeout | "Verification timed out. Please try again." | Yes (retry) |
| Max retries exceeded (`LOCK_OUT`) | "Face verification failed. Please contact an operator for assistance." | No |
| Max retries exceeded (`ESCALATE_TO_OPERATOR`) | "Face verification could not be completed. An operator will assist you." | No (operator assist) |
| Max retries exceeded (`FALLBACK`/`ALLOW_RETRY`) | "We could not verify face liveness. Please try again." | Yes — a **new** session (`mayRetrySession: true`) |
| Device unavailable | "Biometric device is unavailable." | No |

---

## Example: Hardened Supervisor Configuration

```java
LivenessConfig hardened = LivenessConfig.builder()
    .passiveThreshold(0.90)
    .minFaceQuality(0.60)
    .passiveMinFrames(7)
    .passiveWindowFrames(10)
    .minChallengeCount(3)
    .maxRetries(1)
    .challengeTimeoutMs(8_000)
    .onRepeatedFailure(RepeatedFailureAction.LOCK_OUT)
    .supportedChallengeTypes(EnumSet.of(
        ChallengeType.BLINK,
        ChallengeType.SMILE,
        ChallengeType.TURN_HEAD_LEFT,
        ChallengeType.TURN_HEAD_RIGHT,
        ChallengeType.LOOK_DIRECTION))
    .workflowOverrides(Map.of(
        WorkflowType.SUPERVISOR_AUTH, new LivenessPolicy()
            .withPassiveThreshold(0.95)
            .withMinChallengeCount(4)
            .withMaxRetries(0)  // no retries for supervisors
    ))
    .build();
```

---

## Configuration Loading

In production, configuration should be loaded from a signed, integrity-verified config file (e.g., JSON or properties). The `LivenessConfig.Builder` can be populated programmatically:

```java
LivenessConfig config = LivenessConfig.builder()
    .passiveThreshold(Double.parseDouble(props.getProperty("liveness.passive.threshold", "0.80")))
    .minChallengeCount(Integer.parseInt(props.getProperty("liveness.active.minChallenges", "2")))
    .build();
```

Config updates may sync when the device is online; the sync must use integrity verification (e.g., signed manifests) to prevent tampering.
