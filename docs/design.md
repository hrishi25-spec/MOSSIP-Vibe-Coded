# MOSIP Face Liveness & PAD Engine — Technical Design

## 1. Architecture Overview

```
┌─────────────────────────────────────────────────────────┐
│                    Host Application                      │
│  (Registration Client / Operator Auth / Supervisor Auth) │
└───────────────┬─────────────────────────────────────────┘
                │  LivenessPipeline interface
                ▼
┌─────────────────────────────────────────────────────────┐
│                  FaceLivenessEngine                       │
│  ┌────────────┐  ┌──────────────┐  ┌────────────────┐  │
│  │  Session    │  │   Decision   │  │  Challenge     │  │
│  │  Manager    │  │   Logic      │  │  Selector      │  │
│  └────────────┘  └──────────────┘  └────────────────┘  │
│  ┌────────────┐  ┌──────────────┐  ┌────────────────┐  │
│  │  Audit     │  │   Metrics    │  │  Config        │  │
│  │  Logger    │  │   Collector  │  │  Resolver      │  │
│  └────────────┘  └──────────────┘  └────────────────┘  │
└───────────────┬─────────────────────────────────────────┘
                │  LivenessBackend interface
                ▼
┌─────────────────────────────────────────────────────────┐
│              Pluggable Backend Layer                      │
│  ┌──────────────┐  ┌─────────────────┐  ┌────────────┐ │
│  │ MockBackend   │  │ TfLiteMiniFasNet│  │ (vendor)   │ │
│  │ (testing)     │  │ (real PAD)      │  │ (swap)     │ │
│  └──────────────┘  └─────────────────┘  └────────────┘ │
└─────────────────────────────────────────────────────────┘
```

### Layer Responsibilities

| Layer | Responsibility |
|-------|---------------|
| **Host Application** | Camera frame capture via device adapter, UI prompts, workflow orchestration |
| **FaceLivenessEngine** | Session lifecycle, passive→active decision flow, challenge management, audit logging |
| **LivenessBackend** | Frame analysis, face detection/landmarks, passive liveness scoring, PAD verdict |
| **Config Resolver** | Base config + per-workflow policy overrides → effective session policy |
| **Audit/Metrics** | Structured decision logs (no PII), anonymized operational metrics |

---

## 2. Frame-Streaming Pipeline

### 2.1 Interface

```java
public interface LivenessPipeline {
    String initSession(WorkflowType workflow);
    FrameAssessment pushFrame(String sessionId, Frame frame);
    Challenge requestChallenge(String sessionId);
    ValidationResult validateChallenge(String sessionId, List<Frame> frames);
    SessionSummary closeSession(String sessionId);
}
```

### 2.2 Frame Processing Flow

```
pushFrame(sessionId, frame)
  │
  ├─ Session already PASSED → return passed (idempotent)
  ├─ Session already FAILED → throw INVALID_STATE
  ├─ Liveness disabled → mark bypass, return PASSED
  │
  ├─ State = IN_CHALLENGE:
  │   └─ checkChallengeDeadline()
  │       ├─ No timeout → return CHALLENGE_IN_PROGRESS
  │       ├─ Timeout + budget left → return ESCALATED_TO_ACTIVE
  │       └─ Timeout + budget exhausted → return FAILED
  │
  └─ Normal frame processing:
      ├─ backend.analyzeFrame(frame) → FaceSignals
      │   ├─ faceCount == 0 → RETRYABLE_ERROR (FACE_NOT_DETECTED)
      │   ├─ faceCount > 1  → RETRYABLE_ERROR (MULTIPLE_FACES)
      │   └─ quality < min  → RETRYABLE_ERROR (POOR_QUALITY)
      │
      ├─ backend.assessPad(frame, signals) → PadVerdict
      │   └─ attackDetected → PAD_BLOCKED (terminal, distinct audit event)
      │
      ├─ backend.scorePassiveLiveness(frame, signals) → score
      │
      ├─ State = SCORING:
      │   ├─ Add score to sliding window
      │   ├─ Enough frames? → compute median, decide:
      │   │   ├─ median >= threshold → PASSED
      │   │   └─ median < threshold → ESCALATED_TO_ACTIVE (or FAILED)
      │   └─ Not enough → return SCORING
      │
      └─ State = ESCALATED → return ESCALATED_TO_ACTIVE
```

### 2.3 Active Challenge Flow

```
requestChallenge(sessionId)
  │
  ├─ Assert not terminal, state == ESCALATED
  ├─ selector.next(allowedChallenges) → randomized Challenge
  ├─ issueChallenge(now) → state = IN_CHALLENGE
  └─ Return Challenge

validateChallenge(sessionId, frames)
  │
  ├─ Assert not terminal, state == IN_CHALLENGE
  ├─ checkChallengeDeadline()
  │
  ├─ For each frame:
  │   ├─ backend.analyzeFrame(f) → signals
  │   ├─ faceCount == 0 → failChallenge (face_lost)
  │   ├─ backend.assessPad(f, signals) → if attack → PAD_BLOCKED
  │   └─ Collect signals into sequence
  │
  ├─ ChallengeEvaluators.evaluate(challenge, sequence) → boolean
  │
  ├─ If passed:
  │   ├─ challengesPassed++
  │   ├─ remaining = minChallengeCount - challengesPassed
  │   ├─ remaining == 0 → markPassed(true), return finalPass
  │   └─ remaining > 0 → awaitNextChallenge(), return partialPass
  │
  └─ If failed:
      └─ failChallenge()
          ├─ retriesUsed++
          ├─ budget exhausted? → applyRepeatedFailurePolicy (hard fail)
          └─ budget left? → awaitNextChallenge(), return retryAvailable
```

---

## 3. Passive → Active Decision Architecture

### 3.1 Passive Liveness (Stage 1)

Applies to both execution paths; the HTTP path (`DecisionEngineService`) and the
engine library (`FaceLivenessEngine`) share `LivenessDecisionLogic`, so the rules
below are identical for the running service and for desktop/Android embedding.

- **Score source**: `PassiveScoringService` — the live-class probability of
  **MiniFASNet-V2** (`onnx-minifasnet-v2`, bundled under `resources/models/`,
  Apache-2.0, checksum-verified on load) when the model loads
  (`mosip.liveness.backend: auto`), otherwise the OpenCV quality heuristic
  (`heuristic` mode / graceful fallback). The active scorer id is reported by
  the calibration endpoint.
- **Cold start**: until `passiveMinFrames` (5) scored frames exist, the window
  is *undecidable* — every response is the non-terminal `retry_passive`
  ("Checking face liveness..."). Autofocus, exposure and pose settle during
  the first second of a capture; a verdict on one or two frames is noise.
- **Temporal median voting**: once warm, the **median** of the last
  `passiveWindowFrames` (7) scores is compared to the threshold.
  `LivenessDecisionLogic.decidePassiveWindow()` implements this, and the
  calibration sweep evaluates that *same* function, so measured BPCER/APCER
  describe deployed behaviour rather than a single-frame approximation.
- **Median** (not mean) rejects outliers (motion blur, a blink, an exposure
  shift) without a second model.
- **Decision**: median ≥ threshold → proceed (session `PASSED`); median <
  threshold → **automatic initiation of active liveness** (Stage 2), or a hard
  fail when `activeLivenessEnabled=false`.
- **PAD runs on every frame** alongside passive scoring — the FFT/texture/
  brightness heuristics **OR** the MiniFASNet print/replay verdict. An attack is
  terminal (no retry), but it must be **confirmed across `PAD_CONFIRM_FRAMES` (2)
  consecutive frames**: a genuine screen replay is consistent, while a one-off
  model flip (motion blur, exposure, a large/edge face crop) is not — this is
  what caused device-specific false rejects on some cameras.
- **Challenge lock**: the `PASSIVE → ACTIVE` transition is atomic with issuing
  the challenge (pessimistic row lock on `liveness_sessions`,
  `LivenessSessionRepository.findByIdForUpdate`). While a challenge is open,
  frame submissions are answered with the *same* open challenge
  (`escalate_to_active`, idempotent for the client): a late-arriving high-score
  frame can neither pass the session without the action being performed nor
  issue a duplicate challenge. Final verdicts are written only from the
  passive stage (while unlocked) or from challenge validation.

### 3.2 Active Liveness (Stage 2)

- **Triggered automatically** when passive liveness score is below threshold.
- **System-selected challenges** using `ChallengeSelector`:
  - Shuffled bag per cycle (SecureRandom) — every type appears once before repeat.
  - No challenge is issued twice in a row while alternatives remain.
  - LOOK_DIRECTION targets are randomly chosen from {up, down, left, right}.
- **Rule-based evaluation** (`ChallengeEvaluators`):
  - **Blink**: EAR (Eye Aspect Ratio) dips below close threshold, then recovers above open threshold.
  - **Smile**: smile score exceeds threshold at least once in the window.
  - **Head turn**: yaw reaches target angle (≥ 12° for right, ≤ -12° for left).
  - **Gaze direction**: normalized gaze vector stays within tolerance of target for ≥ 50% of frames.
- **Retry policy**: configurable maxRetries; after exhaustion, applies `RepeatedFailureAction` (LOCK_OUT / FALLBACK / ESCALATE_TO_OPERATOR).

---

## 4. Device Adapter Pattern

```
┌──────────────────────┐     ┌─────────────────────────┐
│  L0/L1 Device SDK    │────▶│  Device Adapter          │
│  (vendor-specific)   │     │  implements:             │
│                      │     │  • open() / close()      │
│                      │     │  • startCapture()        │
│                      │     │  • onFrame(callback)     │
│                      │     │  • Frame → io.mosip.     │
│                      │     │    liveness.core.Frame   │
└──────────────────────┘     └──────────┬──────────────┘
                                        │
                                        ▼
                               LivenessPipeline.pushFrame()
```

The `Frame` class is format-agnostic (RGB_GRAY, RGB_888, NV21, YUV420). Device adapters normalize raw camera output into `Frame` objects. The engine and backend never touch device-specific APIs.

---

## 5. ISO/IEC 30107 Alignment

| ISO/IEC 30107 Concept | Implementation |
|------------------------|---------------|
| APCER (Attack Presentation Classification Error Rate) | `PadMetrics.apcer()` — proportion of attack presentations accepted as bona fide |
| BPCER (Bona Fide Presentation Classification Error Rate) | `PadMetrics.bpcer()` — proportion of bona fide presentations rejected as attacks |
| ACER | `PadMetrics.acer()` — arithmetic mean of APCER and BPCER |
| PAD granularity | Per-frame PAD verdict; attack type classified (PRINTED_PHOTO, SCREEN_REPLAY, VIDEO_REPLAY, OTHER) |
| PAD is terminal | PAD failure blocks session immediately — no retry, distinct audit event |
| Evaluation harness | `AttackScenarioHarness` — runs labeled presentations through the full pipeline |

### Evaluation Methodology

1. **Dataset**: labeled presentations (bona fide + 3 attack classes) with varied quality.
2. **Pipeline execution**: end-to-end through `FaceLivenessEngine` with `MockLivenessBackend` (or real model backend).
3. **Metrics**: APCER, BPCER, ACER, FAR, FRR, escalation rate, average latency.
4. **Simulated imperfections**: configurable attack-miss rate (default 3%) and bona-fide FP rate (default 1%).

---

## 6. Sequence Diagram: Full Liveness Check

```
Host                Engine              Backend           Audit
  │                   │                   │                 │
  │─ initSession() ──▶│                   │                 │
  │                   │─ initialize() ───▶│                 │
  │                   │◀──────────────────│                 │
  │◀── sessionId ─────│                   │                 │
  │                   │─────────────────────────────────log─▶
  │                   │                   │                 │
  │─ pushFrame(f1) ──▶│                   │                 │
  │                   │─ analyzeFrame() ─▶│                 │
  │                   │◀── signals ───────│                 │
  │                   │─ assessPad() ────▶│                 │
  │                   │◀── verdict ───────│                 │
  │                   │─ scorePassive() ─▶│                 │
  │                   │◀── 0.92 ──────────│                 │
  │◀── SCORING ───────│                   │                 │
  │                   │                   │                 │
  │  ... (repeat pushFrame) ...           │                 │
  │                   │                   │                 │
  │◀── ESCALATED ─────│                   │                 │
  │                   │─────────────────────────────────log─▶
  │                   │                   │                 │
  │─ requestChallenge()▶                  │                 │
  │◀── Challenge(BLINK)│                  │                 │
  │                   │─────────────────────────────────log─▶
  │                   │                   │                 │
  │─ validateChallenge(frames)──▶          │                 │
  │                   │─ analyzeFrame() ─▶│                 │
  │                   │─ assessPad() ────▶│                 │
  │                   │─ evaluate() ──────│                 │
  │◀── partialPass(1) │                   │                 │
  │                   │                   │                 │
  │─ requestChallenge()▶                  │                 │
  │◀── Challenge(SMILE)│                  │                 │
  │                   │                   │                 │
  │─ validateChallenge(frames)──▶          │                 │
  │◀── finalPass ─────│                   │                 │
  │                   │─────────────────────────────────log─▶
  │                   │                   │                 │
  │─ closeSession() ─▶│                   │                 │
  │◀── SessionSummary │                   │                 │
  │                   │─────────────────────────────────log─▶
```

---

## 7. Thread Safety

- `FaceLivenessEngine` uses `ConcurrentHashMap` for session storage.
- `MetricsCollector` methods are `synchronized`.
- `StructuredAuditLogger` synchronizes on output.
- `LivenessSession` is not thread-safe — single-session-per-thread is assumed (typical for desktop/Android UI threads).
