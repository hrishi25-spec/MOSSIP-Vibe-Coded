import base64

import cv2
import numpy as np
import pytest
from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from app.db.base import Base
from app.db.session import get_db
from app.main import app
from app.models.enums import ChallengeType
from app.services.liveness_engine import FaceObservation
from app.services.pad_engine import PADResult


@pytest.fixture
def client():
    engine = create_engine(
        "sqlite+pysqlite:///:memory:",
        connect_args={"check_same_thread": False},
        poolclass=StaticPool,
    )
    Base.metadata.create_all(engine)
    session_factory = sessionmaker(bind=engine, autoflush=False, autocommit=False)

    def override_get_db():
        with session_factory() as db:
            yield db

    app.dependency_overrides[get_db] = override_get_db
    try:
        with TestClient(app) as test_client:
            yield test_client
    finally:
        app.dependency_overrides.pop(get_db, None)
        Base.metadata.drop_all(engine)
        engine.dispose()


class FakeLivenessEngine:
    def __init__(self, score=0.9, active_passes=True, face_detected=True):
        self.score = score
        self.active_passes = active_passes
        self.face_detected = face_detected
        self.score_calls = 0

    def observe_face(self, frame):
        return FaceObservation(
            face_detected=self.face_detected,
            multiple_faces=False,
            face_quality=0.9 if self.face_detected else None,
            bbox=(0, 0, 32, 32) if self.face_detected else None,
        )

    def score_passive(self, frame, observation):
        self.score_calls += 1
        return self.score

    def validate_active(self, challenge_type, frames):
        return self.active_passes


class FakePADEngine:
    def __init__(self, result=None):
        self.result = result or PADResult(is_attack=False, attack_type=None, confidence=0.95)

    def detect(self, frame):
        return self.result


@pytest.fixture
def engine_mocks(monkeypatch):
    import app.services.decision_engine as decision_engine

    liveness = FakeLivenessEngine()
    pad = FakePADEngine()
    monkeypatch.setattr(decision_engine, "get_liveness_engine", lambda: liveness)
    monkeypatch.setattr(decision_engine, "get_pad_engine", lambda: pad)
    return liveness, pad


@pytest.fixture
def frame_base64():
    image = np.full((64, 64, 3), 128, dtype=np.uint8)
    encoded, data = cv2.imencode(".png", image)
    assert encoded
    return base64.b64encode(data).decode("ascii")


def create_session(client):
    response = client.post(
        "/api/v1/sessions",
        json={"workflow_type": "resident", "device_id": "test-camera"},
    )
    assert response.status_code == 201
    return response.json()["id"]


def test_passive_live_frame_passes_session_and_updates_metrics(client, engine_mocks, frame_base64):
    session_id = create_session(client)

    response = client.post(
        f"/api/v1/sessions/{session_id}/frames",
        json={"frame_base64": frame_base64},
    )

    assert response.status_code == 200
    result = response.json()
    assert result["action"] == "proceed"
    assert result["stage"] == "completed"
    assert result["liveness_score"] == pytest.approx(0.9)
    assert client.get(f"/api/v1/sessions/{session_id}").json()["status"] == "passed"

    audit = client.get(f"/api/v1/sessions/{session_id}/audit").json()
    assert {event["event_type"] for event in audit} == {"SESSION_CREATED", "SESSION_PASSED"}
    metrics = client.get("/api/v1/metrics").json()
    assert metrics["total_sessions"] == 1
    assert metrics["passed_sessions"] == 1
    assert metrics["avg_frames_per_session"] == pytest.approx(1.0)


def test_low_passive_score_escalates_and_successful_challenge_passes(client, engine_mocks, frame_base64, monkeypatch):
    import app.services.decision_engine as decision_engine

    liveness, _ = engine_mocks
    liveness.score = 0.4
    monkeypatch.setattr(decision_engine, "select_challenge", lambda allowed, previous: ChallengeType.BLINK)
    session_id = create_session(client)

    frame_response = client.post(
        f"/api/v1/sessions/{session_id}/frames",
        json={"frame_base64": frame_base64},
    )

    assert frame_response.status_code == 200
    challenge = frame_response.json()["challenge"]
    assert frame_response.json()["action"] == "escalate_to_active"
    assert frame_response.json()["stage"] == "active"
    assert challenge["challenge_type"] == "blink"
    assert challenge["attempt_number"] == 1

    validation = client.post(
        f"/api/v1/sessions/{session_id}/challenges/validate",
        json={"challenge_id": challenge["challenge_id"], "frames_base64": [frame_base64, frame_base64]},
    )

    assert validation.status_code == 200
    assert validation.json()["passed"] is True
    assert validation.json()["action"] == "proceed"
    assert validation.json()["challenge"]["status"] == "passed"
    assert client.get(f"/api/v1/sessions/{session_id}").json()["status"] == "passed"
    assert {event["event_type"] for event in client.get(f"/api/v1/sessions/{session_id}/audit").json()} == {
        "SESSION_CREATED", "CHALLENGE_ISSUED", "CHALLENGE_PASSED", "SESSION_PASSED"
    }


def test_pad_attack_rejects_before_passive_scoring_and_records_audit(client, engine_mocks, frame_base64):
    liveness, pad = engine_mocks
    pad.result = PADResult(is_attack=True, attack_type="screen_replay", confidence=0.99)
    session_id = create_session(client)

    response = client.post(
        f"/api/v1/sessions/{session_id}/frames",
        json={"frame_base64": frame_base64},
    )

    assert response.status_code == 200
    assert response.json()["action"] == "reject"
    assert response.json()["pad_flag"] is True
    assert "Please try again" in response.json()["message"]
    assert liveness.score_calls == 0
    session = client.get(f"/api/v1/sessions/{session_id}").json()
    assert session["status"] == "failed"
    assert session["failure_reason"] == "presentation_attack:screen_replay"
    audit = client.get(f"/api/v1/sessions/{session_id}/audit").json()
    assert {event["event_type"] for event in audit} == {
        "SESSION_CREATED", "PAD_REJECTED", "SESSION_FAILED"
    }


def test_invalid_image_is_rejected_without_processing_a_frame(client, engine_mocks):
    session_id = create_session(client)

    response = client.post(
        f"/api/v1/sessions/{session_id}/frames",
        json={"frame_base64": "%%%"},
    )

    assert response.status_code == 422
    assert response.json()["detail"] == "Empty frame payload"
    assert client.get(f"/api/v1/sessions/{session_id}").json()["status"] == "active"
    assert client.get("/api/v1/metrics").json()["avg_frames_per_session"] == 0.0


def test_closing_active_session_expires_it_and_blocks_further_frames(client, engine_mocks, frame_base64):
    session_id = create_session(client)

    closed = client.post(f"/api/v1/sessions/{session_id}/close")

    assert closed.status_code == 200
    assert closed.json()["status"] == "expired"
    rejected = client.post(
        f"/api/v1/sessions/{session_id}/frames",
        json={"frame_base64": frame_base64},
    )
    assert rejected.status_code == 409
