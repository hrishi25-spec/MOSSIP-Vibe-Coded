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

### 1. With Docker (recommended)

```bash
cp .env.example .env      # edit credentials as needed
docker compose up --build
```

API available at `http://localhost:8000`, docs at `http://localhost:8000/swagger-ui.html`.

### 2. Without Docker

```bash
# Prerequisites: Java 17+, PostgreSQL (or use dev profile for H2)

# Build the JAR
mvn clean package -DskipTests

# Run with PostgreSQL
java -jar target/pad-liveness-backend-1.0.0-SNAPSHOT.jar

# Or run with dev profile (H2 in-memory DB, no PostgreSQL needed)
java -jar target/pad-liveness-backend-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev

# Or run with Maven directly
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

### 3. Database migrations (Flyway)

Flyway runs automatically on startup. For manual migration management:

```bash
# Generate migration (requires a running DB with Hibernate auto-ddl)
mvn flyway:migrate

# Check migration status
mvn flyway:info
```

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
