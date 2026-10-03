# MOSIP Face Liveness & PAD Engine — Configuration Guide

## Base Configuration

Built via `LivenessConfig.builder()`:

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `livenessEnabled` | boolean | `true` | Enable/disable liveness verification entirely. When disabled, sessions bypass scoring and return PASSED immediately. |
| `activeLivenessEnabled` | boolean | `true` | Enable/disable active (Stage 2) liveness. When disabled and passive fails, session hard-fails instead of escalating. |
| `passiveThreshold` | double | `0.80` | Liveness confidence threshold in [0, 1]. Median score ≥ threshold → proceed; < threshold → escalate (or fail). |
| `minFaceQuality` | double | `0.50` | Minimum face quality score in [0, 1]. Frames below this are rejected as RETRYABLE_ERROR. |
| `passiveMinFrames` | int | `5` | Minimum consecutive frames before a passive liveness decision is made. |
| `passiveWindowFrames` | int | `7` | Sliding window size for temporal median voting. Must be ≥ `passiveMinFrames`. |
| `minChallengeCount` | int | `2` | Minimum number of distinct challenges that must pass before active liveness is satisfied. |
| `challengeTimeoutMs` | long | `15,000` | How long (ms) a challenge stays open for the user to perform the action. Lowered from 60,000 to 15,000 at the product's request; a 15s floor is applied at runtime. Exceeding the window consumes one retry. |
| `maxRetries` | int | `2` | Number of retries (on top of the initial attempt) before hard failure. Total attempts = 1 + maxRetries. |
| `supportedChallengeTypes` | Set\<ChallengeType\> | All 5 types | Pool of challenge types the engine may select from. |
| `onRepeatedFailure` | RepeatedFailureAction | `LOCK_OUT` | Behavior when retry budget is exhausted: LOCK_OUT, FALLBACK, or ESCALATE_TO_OPERATOR. |
| `workflowOverrides` | Map\<WorkflowType, LivenessPolicy\> | empty | Per-workflow policy overrides (see below). |

### Validation Rules

- `passiveThreshold` ∈ [0, 1]
- `minFaceQuality` ∈ [0, 1]
- `passiveMinFrames` ≥ 1
- `passiveWindowFrames` ≥ `passiveMinFrames`
- `minChallengeCount` ≥ 1
- `maxRetries` ≥ 0
- `challengeTimeoutMs` > 0
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
curl -X PUT http://localhost:8000/api/v1/config/RESIDENT \
     -H 'Content-Type: application/json' \
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

| Action | Behavior |
|--------|----------|
| `LOCK_OUT` | Hard-fail the session; operator/device locked out of further attempts. Default. |
| `FALLBACK` | Hard-fail and flag for fallback capture flow (e.g., manual verification). |
| `ESCALATE_TO_OPERATOR` | Hard-fail and flag for supervisor/operator escalation. |

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
| Max retries exceeded | "Verification could not be completed. Please contact support." | No |
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
