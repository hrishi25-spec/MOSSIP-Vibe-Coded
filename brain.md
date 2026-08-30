# 🧠 Brain.md — MOSIP Face Liveness & PAD Service

> **The single document that tells any AI agent or human everything about this codebase:
> what it does, how it works internally, every API endpoint, every tool, every language, and every file.**

---

## 1. What This Project Is

This is a **Face Liveness Detection and Presentation Attack Detection (PAD)** service built for the [MOSIP](https://mosip.io/) Registration Client. It verifies that the face being captured belongs to a **live person physically present at the camera** — not a printed photo, screen replay, or video replay.

It covers three workflows:
- **Resident Registration** — verifying a resident's face during ID enrollment
- **Operator Authentication** — verifying the operator logging into the system
- **Supervisor Authentication** — verifying the supervisor approving registrations

The service is designed to run **entirely offline** (no mandatory cloud calls) on both **Desktop** (JavaFX/Java) and **Android** (Flutter) registration clients.

---

## 2. Project Structure (at a glance)

```
.
├── src/                                    # Java Spring Boot backend (THE ACTIVE CODEBASE)
│   ├── main/java/io/mosip/liveness/
│   │   ├── PadLivenessApplication.java     # Spring Boot entry point
│   │   ├── app/config/AppConfig.java       # Bean wiring, OpenCV init, CORS
│   │   ├── api/                            # REST controllers (HTTP endpoints)
│   │   ├── dto/                            # Request/response DTOs
│   │   ├── services/                       # Business logic layer
│   │   ├── crud/                           # JPA repositories (DB access)
│   │   ├── core/                           # Domain types (Frame, FaceSignals, enums)
│   │   ├── engine/                         # Liveness engine state machine
│   │   ├── backend/                        # Pluggable liveness/PAD backends
│   │   ├── challenge/                      # Active challenge evaluation logic
│   │   ├── config/                         # Configuration and policy merging
│   │   ├── audit/                          # Structured audit logging + metrics
│   │   ├── device/                         # Device adapter layer (frame sources)
│   │   └── eval/                           # ISO 30107 attack-scenario harness
│   ├── main/resources/
│   │   ├── application.yml                 # Main Spring config (PostgreSQL)
│   │   ├── application-dev.yml             # Dev profile (H2 in-memory DB)
│   │   └── db/migration/V1__init_schema.sql  # Flyway DB migration
│   └── test/java/io/mosip/liveness/       # 20 test classes
│
├── pad_liveness_backend/                   # Python FastAPI ORIGINAL reference backend
│   ├── app/                                # FastAPI application (35 .py files)
│   ├── alembic/                            # DB migrations (Alembic)
│   ├── requirements.txt                    # Python dependencies
│   ├── Dockerfile                          # Python container
│   └── docker-compose.yml                  # Python service stack
│
├── docs/                                   # Documentation
│   ├── design.md                           # Architecture diagrams + sequence flows
│   ├── status-report.md                    # Project status and feasibility findings
│   ├── backend-swapping.md                 # How to swap liveness/PAD backends
│   └── configuration.md                    # Configuration reference
│
├── pom.xml                                 # Maven build (Java backend)
├── Dockerfile                              # Java container (Eclipse Temurin 17)
├── docker-compose.yml                      # Java service stack (PostgreSQL + API)
├── .env.example                            # Environment variable template
└── brain.md                                # ← YOU ARE HERE
```

---

## 3. Programming Languages Used

| Language | Where | Lines of Code | Purpose |
|----------|-------|---------------|---------|
| **Java 17** | `src/` | ~9,900 | Main backend: engine, API, device adapters, backends, tests |
| **Python 3.11+** | `pad_liveness_backend/` | ~1,390 | Original FastAPI reference implementation (kept for comparison) |
| **SQL** | `V1__init_schema.sql` | ~60 | PostgreSQL schema (Flyway migration) |
| **YAML** | `application.yml`, `application-dev.yml` | ~50 | Spring Boot configuration |
| **Dockerfile** | 2 files | ~20 | Containerization (Java + Python) |

---

## 4. Tools & Technologies

### Core Framework
| Tool | Version | Purpose |
|------|---------|---------|
| **Java** | 17 | Runtime language |
| **Spring Boot** | 3.3.3 | Web framework, DI, JPA, validation |
| **Spring Data JPA** | (via starter) | ORM / database access |
| **Hibernate** | (via starter) | JPA implementation |
| **Flyway** | (via starter) | Database migration management |
| **Lombok** | (latest) | Boilerplate reduction (@Data, @Builder, etc.) |
| **SpringDoc OpenAPI** | 2.6.0 | Swagger UI / API documentation |

### Computer Vision & ML
| Tool | Version | Purpose |
|------|---------|---------|
| **OpenCV** (org.openpnp) | 4.9.0-0 | Face detection (Haar cascades), image processing, FFT analysis |
| **TensorFlow Lite** | (reflection-loaded) | ML inference for PAD model (MiniFASNet) and FaceMesh landmarks |
| **MediaPipe FaceMesh** | (TFLite model) | 468-point 3D facial landmark extraction |

### Database
| Tool | Version | Purpose |
|------|---------|---------|
| **PostgreSQL** | 16-alpine | Production database |
| **H2** | (dev profile) | In-memory database for local development |

### Testing
| Tool | Version | Purpose |
|------|---------|---------|
| **JUnit Jupiter** | 5.10.2 | Unit and integration testing |
| **Spring Boot Test** | (via starter) | MockMvc, @WebMvcTest, test context |
| **Maven Surefire** | 3.2.5 | Test runner |

### DevOps
| Tool | Version | Purpose |
|------|---------|---------|
| **Docker** | — | Containerization |
| **Docker Compose** | 3.9 | Multi-service orchestration |
| **Eclipse Temurin** | 17-jre-alpine | JRE base image |
| **Maven** | — | Build tool |

### Python Backend (reference)
| Tool | Version | Purpose |
|------|---------|---------|
| **FastAPI** | — | Python REST framework (original reference) |
| **SQLAlchemy** | — | Python ORM |
| **Alembic** | — | Python DB migrations |
| **Uvicorn** | — | Python ASGI server |

---

## 5. API Endpoints (Java Spring Boot Backend)

Base URL: `http://localhost:8000`

### Health Check
```
GET /health
→ { "status": "ok", "service": "MOSIP Face Liveness & PAD Service" }
```

### Session Lifecycle
```
POST /api/v1/sessions
  Body: { "workflowType": "RESIDENT|OPERATOR|SUPERVISOR", "deviceId": "string", "subjectRef": "string?", "online": true }
  → 201: { id, workflowType, deviceId, status, currentStage, retryCount, ... }

GET /api/v1/sessions/{sessionId}
  → 200: SessionResponse (full session state)

POST /api/v1/sessions/{sessionId}/close
  → 200: SessionSummary { id, status, finalResult, totalFramesEvaluated, challengesIssued, durationMs, ... }
```

### Frame Submission
```
POST /api/v1/sessions/{sessionId}/frames
  Body: { "frameBase64": "<base64-encoded JPEG>" }
  → FrameProcessResult {
      sessionId, stage, faceDetected, multipleFaces, faceQuality,
      livenessScore, padFlag, padAttackType,
      action: "proceed" | "escalate_to_active" | "reject" | "retry_passive",
      challenge: { challengeId, challengeType, timeoutMs, attemptNumber },
      message: "generic user-facing message"
    }
```

**Frame submission flow:**
1. Decodes base64 → OpenCV Mat
2. Runs face detection (Haar cascade)
3. Runs PAD check (frequency analysis + texture variance + brightness)
4. If no face / multiple faces → retry
5. If PAD attack detected → reject (terminal, session FAILED)
6. Compute passive liveness score
7. Score ≥ threshold → `proceed` (session PASSED)
8. Score < threshold → `escalate_to_active` + return a challenge

### Challenge Validation
```
POST /api/v1/sessions/{sessionId}/challenges/validate
  Body: { "challengeId": "UUID", "framesBase64": ["<f1>", "<f2>", "<f3>"] }
  → ChallengeValidationResult {
      sessionId, challenge: { id, challengeType, status, ... },
      passed: true/false,
      action: "proceed" | "retry_challenge" | "reject",
      message: "generic user-facing message"
    }
```

**Challenge validation flow:**
1. Re-run PAD on all challenge frames (attack = immediate reject)
2. Evaluate the specific challenge type across the frame sequence
3. If passed + enough challenges completed → `proceed` (PASSED)
4. If passed + more challenges needed → `retry_challenge`
5. If failed → retry with different challenge (up to maxRetries)
6. If retries exhausted → `reject`

### Configuration
```
GET /api/v1/config/{workflowType}
  → ConfigPolicyResponse { workflowType, livenessEnabled, passiveThreshold, ... }

GET /api/v1/config/{workflowType}/effective
  → EffectivePolicy (computed merge of DB config + engine defaults)

PUT /api/v1/config/{workflowType}
  Body: { "passiveThreshold": 0.85, "maxRetryCount": 3, ... }
  → ConfigPolicyResponse (updated)
```

**Config fields:**
| Field | Type | Range | Default |
|-------|------|-------|---------|
| livenessEnabled | boolean | — | true |
| passiveThreshold | double | 0.0–1.0 | 0.75 |
| activeLivenessEnabled | boolean | — | true |
| minChallengeCount | int | 1–5 | 1 |
| challengeTypes | string[] | — | ["blink","smile","turn_left","turn_right"] |
| challengeTimeoutMs | int | 1000–60000 | 8000 |
| maxRetryCount | int | 0–10 | 3 |
| onRepeatedFailure | string | LOCK\|ESCALATE\|ALLOW_RETRY | LOCK |

### Metrics
```
GET /api/v1/metrics
  → OperationalMetrics {
      totalSessions, passedSessions, failedSessions, activeSessions,
      passRate, avgPassiveToActiveEscalationRate,
      avgFramesPerSession, avgChallengesPerSession,
      livenessRetryRate, livenessFailureRate, padRejectionRate
    }
```

### Audit Trail
```
GET /api/v1/sessions/{sessionId}/audit
  → [ { id, eventType, details: {key: value, ...}, createdAt }, ... ]
```

**Audit event types:** `SESSION_STARTED`, `SESSION_CLOSED`, `SESSION_ABORTED`, `FRAME_REJECTED`, `FRAME_SCORED`, `PASSIVE_PASSED`, `LIVENESS_FAILED`, `ESCALATED_TO_ACTIVE`, `CHALLENGE_ISSUED`, `CHALLENGE_PASSED`, `CHALLENGE_FAILED`, `CHALLENGE_TIMEOUT`, `MAX_RETRIES_EXCEEDED`, `REPEATED_FAILURE_ACTION`, `PAD_BLOCKED`, `INTERNAL_ERROR`

### Error Response Format
All errors return:
```json
{
  "error": "MACHINE_READABLE_CODE",
  "message": "Generic, safe user-facing message (never reveals detection internals)",
  "status": 422,
  "timestamp": "2026-08-27T12:00:00Z"
}
```

---

## 6. How the Engine Works Internally

### 6.1 The Core Pipeline (Passive → Active → PAD)

```
┌─────────────────────────────────────────────────────────────────┐
│                    FaceLivenessEngine                            │
│                                                                 │
│  ┌─────────┐    ┌───────────┐    ┌──────────────┐             │
│  │ PASSIVE  │───▶│  ACTIVE   │───▶│    FINAL     │             │
│  │  STAGE   │    │  STAGE    │    │   DECISION   │             │
│  └─────────┘    └───────────┘    └──────────────┘             │
│                                                                 │
│  Scoring window:    Challenge:        Pass/Fail/              │
│  N frames →         blink/smile/      PAD blocked              │
│  median →           turn/gaze         Retry policy             │
│  threshold          (randomized)      Lock out                 │
└─────────────────────────────────────────────────────────────────┘
```

### 6.2 Passive Liveness Stage

1. **Frame sampling**: Process every Nth frame (configurable `frameSamplingRate`, default 1 = every frame). Always checks session timeout even on skipped frames.
2. **Face detection**: OpenCV Haar cascade `haarcascade_frontalface_default.xml`. Rejects if no face or multiple faces.
3. **Face quality**: Rejects if quality < `minFaceQuality` (0.50 default).
4. **PAD check**: Runs on every frame. FFT energy analysis (screen replay proxy), texture variance (print attack proxy), brightness check (video replay proxy). Any attack = terminal session failure.
5. **Passive liveness scoring**: Backend-specific. Mock backend uses scriptable score. Real backends use temporal analysis (EAR variance, blink detection, pose micro-movements, smile variation, gaze variance).
6. **Temporal median voting**: Collects `passiveMinFrames` (5) scores in a sliding window of `passiveWindowFrames` (7). Computes **median** (not mean — rejects outliers).
7. **Decision**: Median ≥ `passiveThreshold` (0.80) → PASS. Below → escalate to active (or fail if active disabled).

### 6.3 Active Challenge Stage

1. **Challenge selection**: `ChallengeSelector` uses a shuffled-bag algorithm with `SecureRandom`. Every challenge type appears once before repeating. No back-to-back repeats while alternatives remain.
2. **Available challenges**: `BLINK`, `SMILE`, `TURN_HEAD_LEFT`, `TURN_HEAD_RIGHT`, `LOOK_DIRECTION` (with random direction parameters), `LOOK_UP`, `LOOK_DOWN`, `LOOK_LEFT`, `LOOK_RIGHT`
3. **Challenge evaluation** (`ChallengeEvaluators`):
   - **BLINK**: EAR (Eye Aspect Ratio) drops below 0.22, then recovers above 0.27
   - **SMILE**: smile score exceeds 0.55 at least once
   - **TURN_HEAD_LEFT/RIGHT**: yaw reaches ±12°
   - **LOOK_***: gaze vector within 0.35 tolerance of target for ≥50% of frames
4. **Real-time UI feedback** (v3): Frame-by-frame evaluation produces `ChallengeProgress`:
   - `AWAITING_ACTION` → UI shows "Please continue"
   - `ACTION_DETECTED` → UI shows "Action detected"
   - `HOLD_STILL` → UI shows "Hold still"
5. **Passive re-evaluation during active** (v3): During challenges, each frame is also scored by the passive liveness scorer. If the passive median drops below `passiveThresholdActive`, the session fails — catches presentation attacks that can simulate the challenge action.
6. **Combined scoring** (v3): Weighted blend of passive (60%) + active (40%) components.

### 6.4 Session Lifecycle

```
initSession(workflow)
  → state: SCORING
  → pushFrame() × N
     → state: SCORING (accumulating passive scores)
     → state: ESCALATED (passive below threshold)
        → requestChallenge()
        → state: IN_CHALLENGE
           → pushFrame() × M (frame-by-frame evaluation)
           → validateChallenge(frames) (batch evaluation)
           → CHALLENGE_PASSED → state: ESCALATED (if more needed)
           → CHALLENGE_FAILED → retry or hard fail
        → state: PASSED or FAILED
closeSession()
  → SessionSummary with audit data
```

**Terminal states**: `PASSED`, `FAILED`, `BYPASSED`

**Session timeout**: `maxSessionDurationMs` (30s default). Checked on every frame, even skipped ones.

### 6.5 Error Codes

| Code | Constant | User Message | Retryable? |
|------|----------|-------------|------------|
| E101 | DEVICE_UNAVAILABLE | "Biometric device is unavailable." | No |
| E102 | DEVICE_CONNECTION_FAILURE | "Could not connect to the biometric device." | No |
| E201 | FACE_NOT_DETECTED | "No face detected. Please look at the camera." | Yes |
| E202 | MULTIPLE_FACES_DETECTED | "Multiple faces detected..." | Yes |
| E203 | POOR_FACE_QUALITY | "Face is not clear..." | Yes |
| E301 | LIVENESS_SCORE_BELOW_THRESHOLD | "Liveness check incomplete..." | No |
| E401 | PAD_FAILURE | "Face verification could not be completed..." | **No (terminal)** |
| E501 | ACTIVE_CHALLENGE_FAILURE | "Verification action was not completed..." | Yes |
| E502 | CHALLENGE_TIMEOUT | "Verification timed out..." | Yes |
| E503 | MAX_RETRIES_EXCEEDED | "Verification could not be completed..." | No |
| E504 | SESSION_TIMEOUT | "Face verification timed out..." | No |
| E505 | MODEL_INTEGRITY | "Model integrity check failed." | No |
| E506 | ACTIVE_REEVAL_FAILED | "Liveness score dropped during active challenge..." | No |
| E601 | INVALID_FRAME_DATA | "Invalid capture data received." | No |
| E901 | ENGINE_INTERNAL_ERROR | "An internal error occurred." | No |

---

## 7. Backend Architecture (Pluggable Liveness/PAD)

### 7.1 The `LivenessBackend` Interface

All inference is behind one interface. The engine never calls vendor SDKs directly:

```java
public interface LivenessBackend {
    String id();
    void initialize(Map<String, String> options);
    FaceSignals analyzeFrame(Frame frame);           // face count, quality, EAR, pose, gaze, smile
    double scorePassiveLiveness(Frame frame, FaceSignals signals);  // 0.0-1.0
    PadVerdict assessPad(Frame frame, FaceSignals signals);         // attack/bonaFide + type
    void shutdown();
}
```

### 7.2 Built-in Backends

| Backend | Class | Model | What It Does | When to Use |
|---------|-------|-------|-------------|-------------|
| **Mock** | `MockLivenessBackend` | None | Scriptable test double. `Subject` simulates face, blink, smile, head turn, PAD verdicts. | Unit tests, CI, attack-scenario harness |
| **TFLite MiniFASNet** | `TfLiteMiniFasNetBackend` | `minifasnet.tflite` (~1.5MB) | Real PAD: classifies live vs print/screen-replay. Uses OpenCV Haar cascades for face/eye detection. | Real PAD when TFLite is on classpath |
| **MediaPipe FaceMesh** | `MediaPipeFaceMeshBackend` | `face_mesh.tflite` (~5MB) | 468-point 3D facial landmark extraction. Precise EAR, smile, head pose (solvePnP + Rodrigues), iris-based gaze. Temporal liveness scoring via rolling buffer. | High-fidelity active challenge evaluation |

### 7.3 Key Backend Details

**MockLivenessBackend:**
- `Subject` class is the test control surface: `blink()`, `smileBig()`, `turnLeft(18)`, `attack(PRINTED_PHOTO, 0.95)`
- `enqueueSignal(FaceSignals)` for per-frame signal override sequences
- Configurable `livenessScore`, `scoreNoise` (adds random jitter)

**TfLiteMiniFasNetBackend:**
- Loads TFLite via reflection (no compile-time TFLite dependency)
- OpenCV face detection via Haar cascade → crop → resize to 80×80 → TFLite inference
- Options: `modelPath`, `inputSize`, `threads`, `delegate` (cpu/nnapi/gpu), `faceCascadePath`, `eyeCascadePath`

**MediaPipeFaceMeshBackend:**
- OpenCV face detection → crop + pad (30% expansion) → resize to 192×192 → FaceMesh TFLite inference
- 468 landmarks → EAR from 6 eye-contour points, smile from mouth width/face width ratio
- Head pose via `solvePnP` with 6 reference 3D model points (nose tip, chin, eye corners, temples)
- Gaze from iris center position (478-point model) or geometric fallback
- Temporal liveness scoring: rolling buffer of 15 frames, computing EAR variance, blink count, pose variance, smile variance, gaze variance — weighted blend (30% EAR + 25% blink + 20% pose + 15% smile + 10% gaze) × 40% + spatial quality × 60%

---

## 8. Device Adapter Layer

### 8.1 Interface

```java
public interface DeviceAdapter extends AutoCloseable {
    String id();
    DeviceCapabilities capabilities();
    void open();
    void startCapture(FrameListener listener);
    void stopCapture();
    boolean isCapturing();
    void close();
}
```

### 8.2 Implementations

| Adapter | What It Does |
|---------|-------------|
| `SbiStreamDeviceAdapter` | Consumes L1 device's MJPEG STREAM endpoint (`GET /stream`, multipart/x-mixed-replace). HTTP client with reconnect backoff (200ms→4s, max 5 attempts). Throttles to maxFps (15). |
| `BurstRCaptureDeviceAdapter` | Polls L0 devices via discrete `rCapture` calls at target FPS (10). For devices without STREAM support. |
| `MockL0L1Device` | In-process simulated device. Renders `SyntheticScene` (gradient background + face-like subject). Supports failure injection (`failOnOpen`, `corruptEveryNthFrame`, `disconnectAfterFrames`). Exposes built-in SBI HTTP server (`/info`, `/stream`). |

### 8.3 Supporting Classes

| Class | Purpose |
|-------|---------|
| `MjpegDecoder` | Incremental JPEG extractor from multipart byte stream. Scans for SOI (FFD8) / EOI (FFD9) markers. |
| `PixelFormats` | `BufferedImage` → `RGB_888` / `RGB_GRAY` / `NV21` / `YUV420` conversion |
| `SyntheticScene` | Deterministic Java2D scene: gradient background + face oval + eyes (open/closed) + mouth line. Scriptable via `setBlinking()`, `setBrightness()`. |
| `FrameListener` | Callback: `onFrame(Frame)` on capture thread, `onDeviceError(LivenessException)` |
| `CaptureSource` | Functional interface: `capture(sequenceNumber, timestampMillis) → Frame` |
| `DeviceCapabilities` | Record: deviceId, deviceSubType, streamingSupported, burstCaptureSupported, supportedFormats, maxFps |

---

## 9. Core Domain Types

### `Frame`
Immutable captured frame: `byte[] data`, `int width`, `int height`, `Format format` (RGB_GRAY/RGB_888/NV21/YUV420), `long timestampMillis`, `int sequenceNumber`.

### `FaceSignals`
Per-frame facial analysis: `int faceCount`, `double qualityScore`, `Double eyeAspectRatioLeft/Right`, `Double smileScore`, `Double yawDegrees`, `Double pitchDegrees`, `Double gazeX/gazeY`. Factory methods: `noFace(quality)`, `live(faceCount, quality, ear, smile, yaw, pitch, gazeX, gazeY)`.

### `PadVerdict`
`boolean attackDetected`, `PadAttackType attackType` (PRINTED_PHOTO/SCREEN_REPLAY/VIDEO_REPLAY/OTHER), `double confidence`. Factory: `bonaFide(confidence)`, `attack(type, confidence)`.

### `Challenge`
`ChallengeType type`, `long timeoutMs`, `Map<String, Double> parameters`. Types: BLINK, SMILE, TURN_HEAD_LEFT, TURN_HEAD_RIGHT, LOOK_DIRECTION, LOOK_UP, LOOK_DOWN, LOOK_LEFT, LOOK_RIGHT.

### `ChallengeProgress` (v3)
`AWAITING_ACTION`, `ACTION_DETECTED`, `HOLD_STILL` — real-time UI feedback during active challenges.

### `CombinedLivenessScore` (v3)
`double passiveComponent`, `double activeComponent`, `double combined` — per-frame dual score during active challenges.

### `ActiveFrameResult` (v3)
`boolean actionDetected`, `ChallengeProgress progress`, `double passiveLivenessScore`, `double combinedScore`, `PadVerdict padVerdict` — result of evaluating one frame during active challenge.

---

## 10. Configuration System

### `LivenessConfig` (Builder pattern, immutable)
Base configuration with all defaults. Created once at startup. Supports per-workflow overrides via `Map<WorkflowType, LivenessPolicy>`.

### `LivenessPolicy` (per-workflow overrides)
Every field is nullable (null = inherit from base). Applied via `LivenessConfig.effectivePolicy(workflow)`.

### `EffectivePolicy` (resolved for one session)
Record with all fields fully resolved (base + override merged). Passed to each `LivenessSession`.

**Key config constants:**
| Constant | Default | Description |
|----------|---------|-------------|
| `DEFAULT_PASSIVE_THRESHOLD` | 0.80 | Median score ≥ this → pass |
| `DEFAULT_MIN_FACE_QUALITY` | 0.50 | Minimum face quality |
| `DEFAULT_PASSIVE_MIN_FRAMES` | 5 | Min frames before passive decision |
| `DEFAULT_PASSIVE_WINDOW_FRAMES` | 7 | Sliding window size |
| `DEFAULT_MIN_CHALLENGE_COUNT` | 2 | Challenges required to pass |
| `DEFAULT_CHALLENGE_TIMEOUT_MS` | 10,000 | Per-challenge timeout |
| `DEFAULT_MAX_RETRIES` | 2 | Retries before hard fail |
| `DEFAULT_MAX_SESSION_DURATION_MS` | 30,000 | Total session timeout |
| `DEFAULT_FRAME_SAMPLING_RATE` | 1 | Process every frame (production: 2) |
| `DEFAULT_COMBINED_PASSIVE_WEIGHT` | 0.6 | Passive weight in combined score |
| `DEFAULT_COMBINED_ACTIVE_WEIGHT` | 0.4 | Active weight in combined score |
| `DEFAULT_PASSIVE_THRESHOLD_ACTIVE` | -1 (sentinel) | During active, defaults to passiveThreshold |

---

## 11. Database Schema

5 tables, managed by Flyway migration `V1__init_schema.sql`:

```sql
liveness_sessions     -- id(UUID), workflow_type, device_id, status, current_stage,
                       -- retry_count, online, final_result, failure_reason, timestamps

frame_events          -- id(UUID), session_id(FK), stage, face_detected, multiple_faces,
                       -- face_quality, liveness_score, pad_flag, pad_attack_type, pad_confidence

challenges            -- id(UUID), session_id(FK), challenge_type, status, attempt_number,
                       -- timeout_ms, issued_at, completed_at

config_policies       -- id(UUID), workflow_type(UNIQUE), liveness_enabled, passive_threshold,
                       -- active_liveness_enabled, min_challenge_count, challenge_types(JSONB),
                       -- challenge_timeout_ms, max_retry_count, on_repeated_failure

audit_logs            -- id(UUID), session_id(FK), event_type, details(JSONB), created_at
```

Seed data: default policies for RESIDENT, OPERATOR, SUPERVISOR workflows.

---

## 12. Audit & Metrics System

### `StructuredAuditLogger`
Writes key=value structured lines to stdout (or any `PrintStream`):
```
ts=2026-08-27T12:00:00Z event=FRAME_SCORED session=abc-123 workflow=RESIDENT_REGISTRATION median=0.85 threshold=0.80
```
Never emits raw frames or model internals — only decisions and scores.

### `MetricsCollector`
Thread-safe counters: `framesProcessed`, `totalProcessingMs`, `challengesCompleted`, `retries`, `escalations`, `padBlocks`, `sessionsFailed`. Produces `MetricsSnapshot` for monitoring dashboards.

### `AttackScenarioHarness`
Automated evaluation framework for ISO/IEC 30107-3 style testing:
- Runs labeled presentations (BONA_FIDE, PRINTED_PHOTO, SCREEN_REPLAY, VIDEO_REPLAY)
- Through the real engine pipeline
- Reports APCER, BPCER, ACER, FAR, FRR, escalation rate
- Configurable attack-miss rate (3%) and bona-fide FP rate (1%)

---

## 13. The Python Reference Backend

`pad_liveness_backend/` contains the **original FastAPI reference implementation** that preceded the Java rewrite. It implements the same API contract and decision flow:

| Component | Python | Java Equivalent |
|-----------|--------|----------------|
| `app/main.py` | FastAPI app entry | `PadLivenessApplication.java` |
| `app/api/routes/` | 6 route files | 8 controller classes |
| `app/services/decision_engine.py` | Orchestrator | `DecisionEngineService.java` |
| `app/services/liveness_engine.py` | Passive + active scoring | `LivenessEngineService.java` |
| `app/services/pad_engine.py` | PAD heuristics | `PadEngineService.java` |
| `app/services/challenge_selector.py` | Challenge selection | `ChallengeSelectorService.java` |
| `app/schemas/` | Pydantic DTOs | `dto/` package |
| `app/crud/` | DB access | `crud/` package |
| `alembic/` | Migrations | `V1__init_schema.sql` (Flyway) |

The Java version is a **complete superset** of the Python version — it adds the standalone engine library, device adapters, pluggable backends, v3 features (passive re-evaluation during active, real-time challenge progress, session timeout, combined scoring), and the evaluation harness.

---

## 14. Test Suite

**20 test classes** covering:

| Test | What It Validates |
|------|-------------------|
| `EngineFlowTest` | Full passive→pass workflow |
| `EngineEscalationTest` | Passive→active escalation, multi-challenge flow, retry exhaustion |
| `PadBlockingTest` | PAD attack detection blocks session immediately |
| `ErrorConditionsTest` | No face, multiple faces, bad quality, invalid state |
| `LivenessDecisionLogicTest` | Median calculation, threshold decisions |
| `ChallengeEvaluatorsTest` | All 9 challenge types (batch + frame-by-frame) |
| `ChallengeSelectorTest` | No back-to-back repeats, shuffled bag, parameters |
| `AttackScenarioHarnessTest` | 240 presentations, APCER/BPCER/ACER computation |
| `ConfigOverrideTest` | Per-workflow policy merging |
| `SessionsControllerTest` | Session CRUD endpoints |
| `FramesControllerTest` | Frame submission + decision flow |
| `ConfigControllerTest` | Config read/update |
| `MetricsControllerTest` | Metrics aggregation |
| `AuditControllerTest` | Audit trail retrieval |
| `GlobalExceptionHandlerTest` | Error response formatting |
| `HealthControllerTest` | Health check |
| `DeviceAdapterTest` | All device adapter components |

Supporting test infrastructure: `MutableClock` (deterministic time), `RecordingAuditLogger` (capture audit events), `TestConfig` (test Spring context).

---

## 15. Docker Deployment

### `docker-compose.yml`
```yaml
services:
  postgres:        # PostgreSQL 16-alpine on port 5432
  api:             # Java backend on port 8000, depends on postgres
```

### `Dockerfile` (Java)
```dockerfile
FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache libstdc++
COPY target/pad-liveness-backend-*.jar app.jar
EXPOSE 8000
CMD ["java", "-jar", "app.jar"]
```

### Running
```bash
cp .env.example .env    # Set POSTGRES_USER, POSTGRES_PASSWORD, POSTGRES_DB
docker compose up --build
# API: http://localhost:8000
# Swagger UI: http://localhost:8000/swagger-ui.html
```

---

## 16. Key Design Decisions

1. **Client-side engine (not delegated to MDS)** — liveness/PAD runs inside the Registration Client. The biometric device vendor's MDS is treated as a dumb frame source.

2. **Zero SBI/MDS protocol changes** — no existing device DTOs are modified. Every certified L0/L1 device works unmodified.

3. **No mandatory cloud call** — all inference is on-device using bundled/local models.

4. **One engine, two platforms** — the same Java engine JAR is consumed by both Desktop (Spring DI) and Android (Pigeon bridge). Identical decision logic guarantees consistent behavior.

5. **Pluggable backend SPI** — swap `MockLivenessBackend` for a real model or vendor SDK without touching engine, UI, or config code.

6. **PAD is terminal** — a detected presentation attack immediately fails the session. No retry, distinct audit event. This is a security requirement.

7. **Median, not mean** for temporal scoring — rejects outlier frames (motion blur, momentary occlusion) without a second neural net.

8. **ISO/IEC 30107 taxonomy-aligned** — attack types map to the standard's species. Structured audit telemetry enables APCER/BPCER computation. The engine is certification-ready, not self-certifying.

9. **User messages are generic** — never reveal whether the system detected a screen replay, video replay, etc. This prevents attackers from learning what the system looks for.

10. **Per-workflow policy overrides** — resident capture, operator auth, and supervisor auth can each have different thresholds, challenge counts, and retry policies.

---

## 17. File Inventory

### Java Source (83 files, ~9,900 LOC)

**Entry Point (1)**
- `PadLivenessApplication.java`

**API Layer — Controllers (8)**
- `SessionsController.java` — POST/GET sessions, POST close
- `FramesController.java` — POST frames
- `ChallengesController.java` — POST validate
- `ConfigController.java` — GET/PUT config
- `MetricsController.java` — GET metrics
- `AuditController.java` — GET audit trail
- `HealthController.java` — GET health
- `GlobalExceptionHandler.java` — @RestControllerAdvice for all error types

**DTOs (12)**
- `SessionCreateRequest`, `SessionResponse`, `SessionSummary`
- `FrameSubmitRequest`, `FrameProcessResult`
- `ChallengeValidateRequest`, `ChallengeValidationResult`, `ChallengeResponse`
- `ConfigPolicyUpdate`, `ConfigPolicyResponse`
- `OperationalMetrics`, `AuditLogEntry`

**Services (6)**
- `DecisionEngineService` — orchestrates passive→active→accept/reject
- `LivenessEngineService` — face observation, passive scoring, active challenge validation (OpenCV-based)
- `PadEngineService` — PAD via FFT + texture + brightness heuristics
- `ChallengeSelectorService` — dynamic challenge selection with SecureRandom
- `ConfigService` — bridges DB config with engine EffectivePolicy
- `ImageUtils` — frame decode (base64→Mat), face/eye detection, sharpness/brightness scoring

**CRUD Repositories (5)**
- `LivenessSessionRepository`, `FrameEventRepository`, `ChallengeRepository`, `AuditLogRepository`, `ConfigPolicyRepository`

**Core Domain Types (13)**
- `Frame`, `FaceSignals`, `Challenge`, `ChallengeType`, `ChallengeProgress`
- `PadVerdict`, `PadAttackType`
- `CombinedLivenessScore`, `ActiveFrameResult`
- `WorkflowType`, `RepeatedFailureAction`
- `LivenessException`, `LivenessErrorCode`

**Engine (8)**
- `FaceLivenessEngine` — main engine: session lifecycle, frame processing, challenge management
- `LivenessPipeline` — interface exposed to host applications
- `LivenessSession` — mutable session state (single-thread)
- `LivenessDecisionLogic` — pure functions: median, threshold decision
- `FrameAssessment` — frame processing result
- `ValidationResult` — challenge validation result
- `SessionSummary` — close-session audit record
- `AssessmentStatus` — enum of frame assessment states

**Backends (4)**
- `LivenessBackend` — interface
- `MockLivenessBackend` — scriptable test double
- `TfLiteMiniFasNetBackend` — real PAD via TFLite + OpenCV
- `MediaPipeFaceMeshBackend` — 468-point FaceMesh landmarks via TFLite + OpenCV

**Challenge (2)**
- `ChallengeEvaluators` — rule-based evaluators for all 9 challenge types (batch + frame-by-frame)
- `ChallengeSelector` — shuffled-bag selection with SecureRandom

**Config (3)**
- `LivenessConfig` — full config with builder
- `EffectivePolicy` — resolved policy record
- `LivenessPolicy` — per-workflow override (all fields nullable)

**Audit (6)**
- `AuditEvent`, `AuditEventType`, `AuditLogger`, `StructuredAuditLogger`
- `MetricsCollector`, `MetricsSnapshot`

**Device Adapters (10)**
- `DeviceAdapter` — interface
- `SbiStreamDeviceAdapter` — L1 STREAM consumer
- `BurstRCaptureDeviceAdapter` — L0 burst capture
- `MockL0L1Device` — simulated device + HTTP server
- `FrameListener` — callback interface
- `CaptureSource` — functional interface
- `DeviceCapabilities` — record
- `MjpegDecoder` — incremental JPEG extractor
- `PixelFormats` — buffer conversion utilities
- `SyntheticScene` — deterministic scene renderer

**Evaluation (4)**
- `AttackScenarioHarness` — automated attack-scenario test runner
- `PadMetrics` — APCER/BPCER/ACER/FAR/FRR computation
- `ScenarioReport` — aggregated evaluation report
- `PresentationLabel` — BONA_FIDE/PRINTED_PHOTO/SCREEN_REPLAY/VIDEO_REPLAY

**App Config (1)**
- `AppConfig` — Spring @Configuration: bean wiring, OpenCV init, CORS

### Java Tests (20 files)
- 7 engine tests, 3 challenge tests, 1 config test, 7 API tests, 1 device test, 1 eval test
- 2 test utilities (`MutableClock`, `RecordingAuditLogger`)

### Python Reference Backend (35 files, ~1,390 LOC)
- FastAPI app with identical API contract
- SQLAlchemy + Alembic for DB
- Same decision flow, pluggable engines, audit logging

### SQL (1 file)
- `V1__init_schema.sql` — 5 tables, indexes, seed data

### Documentation (5 files)
- `design.md`, `status-report.md`, `backend-swapping.md`, `configuration.md`, `README.md`

---

## 18. How to Extend This Codebase

### Add a New Backend
1. Implement `LivenessBackend` interface
2. Pass it to `FaceLivenessEngine` constructor
3. Done — no engine/UI/config changes needed

### Add a New Challenge Type
1. Add value to `ChallengeType` enum
2. Add evaluator in `ChallengeEvaluators.evaluate()` and `evaluateFrame()`
3. Add parameter generation in `ChallengeSelector.parametersFor()`

### Add a New Workflow
1. Add value to `WorkflowType` enum (core + models.enums)
2. Add per-workflow override in config
3. Add DB seed row in migration

### Integrate with Real Device
1. Implement `DeviceAdapter` for the vendor SDK
2. Or use `SbiStreamDeviceAdapter` / `BurstRCaptureDeviceAdapter` for SBI-compliant devices
3. Feed frames into `FaceLivenessEngine.pushFrame()`

---

*Last updated: 2026-08-30*
*Branch: development*
*Artifact: `io.mosip.liveness:pad-liveness-backend:1.0.0-SNAPSHOT`*
