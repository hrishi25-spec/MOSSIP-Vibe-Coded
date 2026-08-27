"""
Declarative base + import hub so Alembic autogenerate can see all models.
"""
from sqlalchemy.orm import DeclarativeBase


class Base(DeclarativeBase):
    pass


# Import all models here so Base.metadata is aware of them for Alembic autogenerate.
from app.models.liveness_session import LivenessSession  # noqa: E402,F401
from app.models.frame_event import FrameEvent  # noqa: E402,F401
from app.models.challenge import Challenge  # noqa: E402,F401
from app.models.config_policy import ConfigPolicy  # noqa: E402,F401
from app.models.audit_log import AuditLog  # noqa: E402,F401
