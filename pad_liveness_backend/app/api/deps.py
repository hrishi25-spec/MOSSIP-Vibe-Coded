import uuid

from fastapi import Depends, HTTPException, status
from sqlalchemy.orm import Session as DBSession

from app.db.session import get_db
from app.crud import session as session_crud
from app.models.liveness_session import LivenessSession
from app.models.enums import SessionStatus


def get_active_session(session_id: uuid.UUID, db: DBSession = Depends(get_db)) -> LivenessSession:
    sess = session_crud.get_session(db, session_id)
    if sess is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Session not found")
    if sess.status != SessionStatus.ACTIVE:
        raise HTTPException(
            status_code=status.HTTP_409_CONFLICT,
            detail=f"Session is not active (status={sess.status.value})",
        )
    return sess
