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
| `challengeTimeoutMs` | long | `10,000` | Maximum time (ms) allowed to complete a single challenge. Exceeding this consumes one retry. |
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
