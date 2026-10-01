import enum


class WorkflowType(str, enum.Enum):
    RESIDENT = "resident"
    OPERATOR = "operator"
    SUPERVISOR = "supervisor"


class SessionStatus(str, enum.Enum):
    ACTIVE = "active"          # session open, awaiting frames
    PASSED = "passed"          # liveness + PAD satisfied, workflow may proceed
    FAILED = "failed"          # max retries exceeded / hard PAD rejection
    EXPIRED = "expired"        # session timed out / abandoned


class LivenessStage(str, enum.Enum):
    PASSIVE = "passive"
    ACTIVE = "active"
    COMPLETED = "completed"


class ChallengeType(str, enum.Enum):
    BLINK = "blink"
    SMILE = "smile"
    TURN_LEFT = "turn_left"
    TURN_RIGHT = "turn_right"
    LOOK_DIRECTION = "look_direction"


class ChallengeStatus(str, enum.Enum):
    ISSUED = "issued"
    PASSED = "passed"
    FAILED = "failed"
    TIMEOUT = "timeout"


class FailurePolicy(str, enum.Enum):
    LOCK = "lock"                # block further attempts until manual reset
    ESCALATE = "escalate"        # route to supervisor / manual review
    ALLOW_RETRY = "allow_retry"  # keep allowing retries (subject to max_retry_count)
