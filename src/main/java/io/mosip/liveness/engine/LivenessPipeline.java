package io.mosip.liveness.engine;

import io.mosip.liveness.core.Challenge;
import io.mosip.liveness.core.Frame;
import io.mosip.liveness.core.WorkflowType;

import java.util.List;

/**
 * The clean processing interface exposed to host applications (desktop
 * registration client, Android capture SDK, Flutter platform channel).
 * Identical liveness/PAD logic across all three workflows.
 */
public interface LivenessPipeline {

    /** Start a session; the effective policy is resolved for the given workflow. */
    String initSession(WorkflowType workflow);

    /**
     * Push one frame into the pipeline.
     * @return assessment with status, passive liveness score and PAD flag
     */
    FrameAssessment pushFrame(String sessionId, Frame frame);

    /** Engine-selected (never user-chosen) next active challenge. */
    Challenge requestChallenge(String sessionId);

    /** Validate frames captured during the challenge window. */
    ValidationResult validateChallenge(String sessionId, List<Frame> frames);

    /** Close the session and return the audit summary record. */
    SessionSummary closeSession(String sessionId);
}
