package io.mosip.liveness.engine;

import io.mosip.liveness.core.LivenessErrorCode;
import io.mosip.liveness.core.PadAttackType;
import io.mosip.liveness.core.PadVerdict;

/** Result of {@code pushFrame}: status, liveness score, PAD flag, safe message. */
public final class FrameAssessment {

    private final AssessmentStatus status;
    private final LivenessErrorCode errorCode;   // null when no error
    private final double livenessScore;          // NaN when not scored
    private final boolean padFlagged;
    private final PadAttackType padAttackType;   // null when no attack detected
    private final String userMessage;            // safe-to-display

    private FrameAssessment(AssessmentStatus status, LivenessErrorCode errorCode, double livenessScore,
                            boolean padFlagged, PadAttackType padAttackType, String userMessage) {
        this.status = status;
        this.errorCode = errorCode;
        this.livenessScore = livenessScore;
        this.padFlagged = padFlagged;
        this.padAttackType = padAttackType;
        this.userMessage = userMessage;
    }

    static FrameAssessment scoring(double score) {
        return new FrameAssessment(AssessmentStatus.SCORING, null, score, false, null, null);
    }

    static FrameAssessment passed(double score) {
        return new FrameAssessment(AssessmentStatus.PASSED, null, score, false, null, null);
    }

    static FrameAssessment bypassed() {
        return new FrameAssessment(AssessmentStatus.PASSED, null, Double.NaN, false, null,
                "Liveness verification disabled.");
    }

    static FrameAssessment escalated(double medianScore) {
        return new FrameAssessment(AssessmentStatus.ESCALATED_TO_ACTIVE, null, medianScore, false, null,
                "Please follow the on-screen instructions.");
    }

    static FrameAssessment challengeInProgress() {
        return new FrameAssessment(AssessmentStatus.CHALLENGE_IN_PROGRESS, null, Double.NaN, false, null,
                "Please perform the requested action.");
    }

    static FrameAssessment padBlocked(PadVerdict verdict) {
        return new FrameAssessment(AssessmentStatus.PAD_BLOCKED, LivenessErrorCode.PAD_FAILURE,
                Double.NaN, true, verdict.attackType(),
                LivenessErrorCode.PAD_FAILURE.userMessage());
    }

    static FrameAssessment failed(LivenessErrorCode code, double score) {
        return new FrameAssessment(AssessmentStatus.FAILED, code, score, false, null, code.userMessage());
    }

    static FrameAssessment retryableError(LivenessErrorCode code) {
        return new FrameAssessment(AssessmentStatus.RETRYABLE_ERROR, code, Double.NaN, false, null,
                code.userMessage());
    }

    public AssessmentStatus status() { return status; }
    public LivenessErrorCode errorCode() { return errorCode; }
    public double livenessScore() { return livenessScore; }
    public boolean padFlagged() { return padFlagged; }
    public PadAttackType padAttackType() { return padAttackType; }
    public String userMessage() { return userMessage; }
}
