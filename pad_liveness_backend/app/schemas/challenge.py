import uuid
from datetime import datetime
from pydantic import BaseModel, Field

from app.models.enums import ChallengeType, ChallengeStatus


class ChallengeValidateRequest(BaseModel):
    challenge_id: uuid.UUID
    frames_base64: list[str] = Field(
        ..., min_length=1, description="Ordered frame stream captured while the challenge was active"
    )


class ChallengeResponse(BaseModel):
    id: uuid.UUID
    session_id: uuid.UUID
    challenge_type: ChallengeType
    status: ChallengeStatus
    attempt_number: int
    timeout_ms: int
    issued_at: datetime
    completed_at: datetime | None

    class Config:
        from_attributes = True


class ChallengeValidationResult(BaseModel):
    session_id: uuid.UUID
    challenge: ChallengeResponse
    passed: bool
    action: str = Field(..., description="One of: proceed | retry_challenge | reject")
    message: str
