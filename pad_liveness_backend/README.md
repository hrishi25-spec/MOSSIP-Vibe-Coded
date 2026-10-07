# Face Liveness & PAD Service (FastAPI + PostgreSQL)

Backend service implementing the Face Liveness Detection and Presentation
Attack Detection (PAD) requirements for the MOSIP Registration Client
(Desktop and Android), covering resident registration, operator
authentication, and supervisor authentication.

## Architecture

```
app/
  core/config.py         # env-driven settings
  db/                     # SQLAlchemy engine/session, Alembic base
  models/                 # ORM models: sessions, frame events, challenges,
                           # per-workflow config policy, audit log
  schemas/                 # Pydantic request/response contracts
  services/
    image_utils.py        # frame decode, face detect, quality scoring
    pad_engine.py          # BasePADEngine + pluggable implementation
    liveness_engine.py     # BaseLivenessEngine (passive score + active
                            # challenge validation) + pluggable implementation
    challenge_selector.py  # dynamic/unpredictable challenge selection
    decision_engine.py     # orchestrates passive -> active -> accept/reject
  crud/                    # DB access functions
  api/routes/               # HTTP endpoints
alembic/                   # migrations
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

1. `POST /sessions` — start a session for a workflow (`resident` |
   `operator` | `supervisor`).
2. `POST /sessions/{id}/frames` — push a frame. Runs passive liveness + PAD.
   - PAD attack detected → immediate reject, session `FAILED`.
   - Score ≥ configured threshold → `proceed`, session `PASSED`.
   - Score < threshold → `escalate_to_active`, returns a dynamically
     selected `challenge` (blink / smile / turn / look direction).
3. `POST /sessions/{id}/challenges/validate` — submit the frame stream
   captured during the challenge. Returns `proceed` / `retry_challenge`
   (new challenge issued) / `reject` (after `max_retry_count` exceeded).
4. `POST /sessions/{id}/close` — explicit close + summary.
5. `GET /sessions/{id}/audit` — full structured audit trail.
6. `GET/PUT /config/{workflow_type}` — read/update per-workflow policy
   (threshold, challenge types, timeout, retry count, failure policy) —
   no redeploy required.
7. `GET /metrics` — anonymized operational metrics (pass rate, escalation
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

API available at `http://localhost:8000`, docs at `http://localhost:8000/docs`.

### 2. Without Docker

```bash
python -m venv venv && source venv/bin/activate
pip install -r requirements.txt

# start a local Postgres however you prefer, then:
cp .env.example .env      # point POSTGRES_HOST etc. at your instance

# create tables + seed default policies (dev-only shortcut)
python -m app.db.init_db

# or, for a proper migration history:
alembic revision --autogenerate -m "init"
alembic upgrade head

uvicorn app.main:app --reload
```

## Running tests

The API behavior tests use an isolated in-memory SQLite database and stub
only the model engines, so they need neither PostgreSQL nor camera hardware.
Install the pinned test dependencies and run them from this directory:

```bash
python -m venv .venv && source .venv/bin/activate
pip install -r requirements-dev.txt
python -m pytest -q
```

## Example flow (curl)

```bash
# 1. Create a session
SESSION_ID=$(curl -s -X POST localhost:8000/api/v1/sessions \
  -H "Content-Type: application/json" \
  -d '{"workflow_type":"resident","device_id":"L1-CAM-01"}' | python3 -c "import sys,json;print(json.load(sys.stdin)['id'])")

# 2. Submit a frame (base64 JPEG)
curl -s -X POST localhost:8000/api/v1/sessions/$SESSION_ID/frames \
  -H "Content-Type: application/json" \
  -d "{\"frame_base64\":\"$(base64 -w0 face.jpg)\"}"

# If action == "escalate_to_active", prompt the user with `message`,
# capture a short frame burst, then:
curl -s -X POST localhost:8000/api/v1/sessions/$SESSION_ID/challenges/validate \
  -H "Content-Type: application/json" \
  -d "{\"challenge_id\":\"<from previous response>\",\"frames_base64\":[\"<f1>\",\"<f2>\",\"<f3>\"]}"

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
