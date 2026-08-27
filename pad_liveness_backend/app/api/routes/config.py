from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session as DBSession

from app.db.session import get_db
from app.crud.config import get_or_seed_policy, update_policy
from app.models.enums import WorkflowType
from app.schemas.config import ConfigPolicyResponse, ConfigPolicyUpdate

router = APIRouter(prefix="/config", tags=["config"])


@router.get("/{workflow_type}", response_model=ConfigPolicyResponse)
def get_policy(workflow_type: WorkflowType, db: DBSession = Depends(get_db)):
    """Get the liveness/PAD policy currently applied to a workflow
    (resident, operator, or supervisor)."""
    return get_or_seed_policy(db, workflow_type)


@router.put("/{workflow_type}", response_model=ConfigPolicyResponse)
def set_policy(workflow_type: WorkflowType, update: ConfigPolicyUpdate, db: DBSession = Depends(get_db)):
    """
    Update the liveness/PAD policy for a workflow. Takes effect on the next
    session immediately (no Registration Client redeploy required); model
    or config changes that require connectivity are still synced by the
    client when it is next online.
    """
    return update_policy(db, workflow_type, update)
