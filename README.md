# Face Liveness & PAD Service (Spring Boot)

Backend service implementing the Face Liveness Detection and Presentation
Attack Detection (PAD) requirements for the MOSIP Registration Client
(Desktop and Android), covering resident registration, operator
authentication, and supervisor authentication.

## Architecture

```
src/main/java/io/mosip/liveness/
  app/
    PadLivenessApplication.java     # Spring Boot entry point
    config/AppConfig.java           # Bean config, CORS, OpenCV init
  api/                              # REST controllers (HTTP endpoints)
    SessionsController.java         # POST /sessions, GET /sessions/{id}, POST /close
    FramesController.java           # POST /sessions/{id}/frames
    ChallengesController.java       # POST /sessions/{id}/challenges/validate
    ConfigController.java           # GET/PUT /config/{workflow_type}
    MetricsController.java          # GET /metrics
    AuditController.java            # GET /sessions/{id}/audit
    HealthController.java           # GET /health
  dto/                              # Request/response schemas (Pydantic-like)
    SessionCreateRequest.java
    SessionResponse.java
    FrameSubmitRequest.java
    FrameProcessResult.java
    ChallengeValidateRequest.java
    ChallengeValidationResult.java
    ConfigPolicyUpdate.java
    ConfigPolicyResponse.java
    OperationalMetrics.java
    AuditLogEntry.java
  models/
    enums/                          # Database enums
      WorkflowType.java             # RESIDENT | OPERATOR | SUPERVISOR
      SessionStatus.java            # ACTIVE | PASSED | FAILED | EXPIRED
      LivenessStage.java            # PASSIVE | ACTIVE | COMPLETED
      ChallengeType.java            # BLINK | SMILE | TURN_LEFT | TURN_RIGHT
      ChallengeStatus.java          # ISSUED | PASSED | FAILED | TIMEOUT
      FailurePolicy.java            # LOCK | ESCALATE | ALLOW_RETRY
    entity/                         # JPA ORM models
      LivenessSession.java
      FrameEvent.java
      ChallengeEntity.java
      AuditLog.java
      ConfigPolicy.java
  crud/                             # JPA repositories (DB access)
    LivenessSessionRepository.java
    FrameEventRepository.java
    ChallengeRepository.java
    AuditLogRepository.java
    ConfigPolicyRepository.java
  services/                         # Business logic
    DecisionEngineService.java      # Orchestrates passive -> active -> accept/reject
    LivenessEngineService.java      # Passive scoring + active challenge validation
    PadEngineService.java           # Presentation attack detection
    ChallengeSelectorService.java   # Dynamic/unpredictable challenge selection
    ImageUtils.java                 # Frame decode, face detect, quality scoring
  core/                             # (existing) Engine types, config, backends
    ...
  engine/                           # (existing) FaceLivenessEngine, LivenessSession
    ...
  backend/                          # (existing) LivenessBackend interface + impls
    ...
  audit/                            # (existing) StructuredAuditLogger, MetricsCollector
    ...
  device/                           # (existing) Device adapter layer
    ...
src/main/resources/
  application.yml                   # Spring Boot config (DB, liveness params)
  application-dev.yml               # Dev profile (H2 in-memory DB)
  db/migration/V1__init_schema.sql  # Flyway migration (replaces Alembic)
```

### Pluggable liveness/PAD engines

`BaseLivenessEngine` and `BasePADEngine` (in `app/services/`) are the
contracts the rest of the system depends on. Ship-provided implementations
(`MockLivenessEngine`, `MockPADEngine`) use cheap OpenCV heuristics
(sharpness, frequency-domain analysis, motion/eye detection) as
**placeholders only** — replace them with a real, ISO/IEC 30107-3 evaluated
model or vendor SDK before production use. Nothing else in the codebase
needs to change: implement the base class and update `get_liveness_engine()`
/ `get_pad_engine()`.

### Decision flow (matches spec)

1. `POST /api/v1/sessions` — start a session for a workflow (`resident` |
   `operator` | `supervisor`).
2. `POST /api/v1/sessions/{id}/frames` — push a frame. Runs passive liveness + PAD.
   - PAD attack detected → immediate reject, session `FAILED`.
   - Score ≥ configured threshold → `proceed`, session `PASSED`.
   - Score < threshold → `escalate_to_active`, returns a dynamically
     selected `challenge` (blink / smile / turn / look direction).
3. `POST /api/v1/sessions/{id}/challenges/validate` — submit the frame stream
   captured during the challenge. Returns `proceed` / `retry_challenge`
   (new challenge issued) / `reject` (after `max_retry_count` exceeded).
4. `POST /api/v1/sessions/{id}/close` — explicit close + summary.
5. `GET /api/v1/sessions/{id}/audit` — full structured audit trail.
6. `GET/PUT /api/v1/config/{workflow_type}` — read/update per-workflow policy
   (threshold, challenge types, timeout, retry count, failure policy) —
   no redeploy required.
7. `GET /api/v1/metrics` — anonymized operational metrics (pass rate, escalation
   rate, retry rate, PAD rejection rate, etc.).

All decisions are logged to the `audit_logs` table (session created, each
frame's verdict is in `frame_events`, challenges issued/passed/failed,
PAD rejections, final outcome) without exposing model internals to the
end-user response (per the spec's UI/UX guidance — generic failure
messages only).

## Running locally

### 1. One command — `start.sh` / `start.bat`

Builds if needed, starts the service, waits until it reports healthy, then opens
the browser console. Runs on **macOS, Linux and Windows** and needs only a
**JDK 17+** — Maven is not required, because the bundled Maven wrapper fetches it.

```bash
./start.sh                 # macOS / Linux / Git Bash
```

```bat
start.bat                  rem Windows
```

| Option | Effect |
|---|---|
| `--no-build` | skip Maven and relaunch the existing jar (fast restart) |
| `--rebuild` | clean build plus the full test suite |
| `--test` | run tests during the build |
| `--port N` | serve on port N (default 8000) |
| `--profile NAME` | Spring profile: `dev` (H2, default) or `default` (PostgreSQL) |
| `--no-browser` | do not open a browser |

A successful launch looks like:

```
✓ JDK 21 → /opt/homebrew/Cellar/openjdk@21/.../bin/java
✓ Build complete
✓ Port 8000 is free
✓ Service is up (started in 5s)
  Console      http://localhost:8000/
  API docs     http://localhost:8000/swagger-ui/index.html
  Health       http://localhost:8000/health
  Engine       available (OpenCV native library loaded)
```

Press `Ctrl+C` (or any key in the Windows window) to stop the service cleanly.
Both scripts fail fast with an actionable message if the JDK is too old, the port
is taken, the build breaks, or the service never becomes healthy.

### 2. Browser console

`http://localhost:8000/` serves a **zero-dependency test console** that drives the
real API — no build step and no CDN, so it works fully offline. It creates a
session, captures webcam frames, runs passive liveness, performs the active
challenge, and shows the decision log, audit trail and operational metrics side
by side.

It must be opened at `localhost`: browsers only expose the camera on a secure
origin, and `localhost` counts as one.

### 3. Without Docker — manual quick start (H2)

Requires only **JDK 17+** (verified on JDK 21). No PostgreSQL, no Docker.

```bash
# Build the executable jar (also runs the full test suite)
./mvnw clean package

# Run with the dev profile: in-memory H2, schema created by Hibernate
java -jar target/pad-liveness-backend-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev
```

Then check it is up:

```bash
curl localhost:8000/health
# {"status":"ok","service":"MOSIP Face Liveness & PAD Service","engine":"available"}
```

OpenAPI UI: `http://localhost:8000/swagger-ui/index.html`

### 4. Against PostgreSQL (default profile)

Set the `POSTGRES_*` variables (or create `.env` from `.env.example`) and run
**without** `--spring.profiles.active=dev`. Flyway applies
`db/migration/V1__init_schema.sql` on startup and Hibernate validates the schema
(`ddl-auto: validate`) rather than creating it:

```bash
POSTGRES_HOST=localhost POSTGRES_USER=mosip POSTGRES_PASSWORD=... POSTGRES_DB=pad_liveness \
  java -jar target/pad-liveness-backend-1.0.0-SNAPSHOT.jar
```

There is no Flyway Maven plugin configured; migrations are applied by the
application at startup, and `spring.flyway.baseline-on-migrate: true` is set.

### 5. Haar cascade data (bundled)

Face detection needs OpenCV's `haarcascade_frontalface_default.xml` and
`haarcascade_eye.xml`. OpenCV's Java bindings do not ship them, so they are
**vendored in `src/main/resources/`** and loaded from the classpath (with a
filesystem fallback). Both files retain their original Intel/OpenCV BSD-3-Clause
licence header.

If they are ever removed, the service does not fail — it silently reports
`faceDetected: false` for every frame and no session can pass, logging a single
clear warning on the first detection attempt. `ImageUtilsCascadeTest` fails the
build in that case rather than letting it regress.

A real face passes the pipeline end to end:

```
{"faceDetected": true, "faceQuality": 0.87, "livenessScore": 0.90,
 "padFlag": false, "action": "proceed", "message": "Liveness verified."}
```

### 6. With Docker (alternative)

```bash
cp .env.example .env      # edit credentials as needed
docker compose up --build
```

API available at `http://localhost:8000`, docs at `http://localhost:8000/swagger-ui.html`.

## Example flow (curl)

```bash
# 1. Create a session
SESSION_ID=$(curl -s -X POST localhost:8000/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{"workflowType":"RESIDENT","deviceId":"L1-CAM-01"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")

# 2. Submit a frame (base64 JPEG)
curl -s -X POST localhost:8000/api/v1/sessions/$SESSION_ID/frames \
  -H "Content-Type: application/json" \
  -d "{\"frameBase64\":\"$(base64 -w0 face.jpg)\"}"

# If action == "escalate_to_active", prompt the user with `message`,
# capture a short frame burst, then:
curl -s -X POST localhost:8000/api/v1/sessions/$SESSION_ID/challenges/validate \
  -H "Content-Type: application/json" \
  -d "{\"challengeId\":\"<from previous response>\",\"framesBase64\":[\"<f1>\",\"<f2>\",\"<f3>\"]}"

# 3. Check the audit trail
curl -s localhost:8000/api/v1/sessions/$SESSION_ID/audit
```

## Notes on the spec's non-functional requirements

- **Offline support**: this service is designed to run as a local process
  on the Desktop/Android Registration Client host (or an embedded service
  reachable without WAN connectivity) so liveness/PAD enforcement never
  depends on network availability; only policy/model updates require
  connectivity, and should be synced through a separate secure update
  channel (out of scope for this scaffold).
- **Vendor independence**: device integration is expected to sit upstream
  of this service (Common Face Capture Device Interface / Device Adapter
  pattern on the Registration Client side) — this service only consumes
  decoded frames, so it works with any compliant L0/L1 device.
- **ISO/IEC 30107 alignment**: replace the mock engines with a properly
  evaluated model, and use `app/services/*_engine.py` as the seam for
  reporting APCER/BPCER/ACER during evaluation.
