package io.mosip.liveness.services;

import io.mosip.liveness.config.EffectivePolicy;
import io.mosip.liveness.core.ChallengeType;
import io.mosip.liveness.core.RepeatedFailureAction;
import io.mosip.liveness.core.WorkflowType;
import io.mosip.liveness.crud.ConfigPolicyRepository;
import io.mosip.liveness.models.entity.ConfigPolicy;
import io.mosip.liveness.models.enums.FailurePolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Bridges the DB-stored {@link ConfigPolicy} with the engine's
 * {@link EffectivePolicy}. Reads fresh config on every call so that
 * runtime updates via the config API take effect immediately.
 *
 * Handles conversion between the two enum families:
 * - {@code io.mosip.liveness.core.*} (engine layer)
 * - {@code io.mosip.liveness.models.enums.*} (API/DB layer)
 */
@Service
@RequiredArgsConstructor
public class ConfigService {

    private final ConfigPolicyRepository configRepo;

    /**
     * Get the effective policy for a given workflow (core enum).
     * Reads directly from the database so API updates are immediate.
     */
    public EffectivePolicy getEffectivePolicy(WorkflowType workflow) {
        ConfigPolicy db = configRepo.findByWorkflowType(toDbWorkflow(workflow)).orElse(null);
        if (db == null) {
            return defaultEffectivePolicy();
        }
        return mapToEffectivePolicy(db);
    }

    /**
     * Convert a DB ConfigPolicy entity to the engine's EffectivePolicy record.
     */
    public EffectivePolicy mapToEffectivePolicy(ConfigPolicy db) {
        Set<ChallengeType> challenges = db.getChallengeTypes() != null
                ? db.getChallengeTypes().stream()
                    .map(String::toUpperCase)
                    .map(this::toCoreChallenge)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(ChallengeType.class)))
                : EnumSet.of(ChallengeType.BLINK);

        if (challenges.isEmpty()) {
            challenges = EnumSet.of(ChallengeType.BLINK);
        }

        return new EffectivePolicy(
                db.getLivenessEnabled() != null ? db.getLivenessEnabled() : true,
                db.getActiveLivenessEnabled() != null ? db.getActiveLivenessEnabled() : true,
                db.getPassiveThreshold() != null ? db.getPassiveThreshold() : 0.75,
                0.50,  // minFaceQuality (not yet in DB schema)
                5,     // passiveMinFrames (not yet in DB schema)
                7,     // passiveWindowFrames (not yet in DB schema)
                db.getMinChallengeCount() != null ? db.getMinChallengeCount() : 1,
                db.getMaxRetryCount() != null ? db.getMaxRetryCount() : 3,
                db.getChallengeTimeoutMs() != null ? db.getChallengeTimeoutMs() : 8000L,
                challenges,
                mapFailurePolicy(db.getOnRepeatedFailure()),
                // v3 fields
                -1.0,  // passiveThresholdActive (sentinel: use passiveThreshold)
                30000L, // maxSessionDurationMs
                1,      // frameSamplingRate
                10,     // frameSamplingMinFps
                0.6,    // combinedPassiveWeight
                0.4);   // combinedActiveWeight
    }

    // ---- Enum conversions between core and models layers ----

    /** Convert models.enums.WorkflowType → core.WorkflowType */
    public WorkflowType toCoreWorkflow(io.mosip.liveness.models.enums.WorkflowType db) {
        return switch (db) {
            case RESIDENT -> WorkflowType.RESIDENT_REGISTRATION;
            case OPERATOR -> WorkflowType.OPERATOR_AUTH;
            case SUPERVISOR -> WorkflowType.SUPERVISOR_AUTH;
        };
    }

    /** Convert core.WorkflowType → models.enums.WorkflowType */
    public io.mosip.liveness.models.enums.WorkflowType toDbWorkflow(WorkflowType core) {
        return switch (core) {
            case RESIDENT_REGISTRATION -> io.mosip.liveness.models.enums.WorkflowType.RESIDENT;
            case OPERATOR_AUTH -> io.mosip.liveness.models.enums.WorkflowType.OPERATOR;
            case SUPERVISOR_AUTH -> io.mosip.liveness.models.enums.WorkflowType.SUPERVISOR;
        };
    }

    /** Convert a string challenge type name to core.ChallengeType */
    private ChallengeType toCoreChallenge(String name) {
        return switch (name) {
            case "BLINK" -> ChallengeType.BLINK;
            case "SMILE" -> ChallengeType.SMILE;
            case "TURN_LEFT" -> ChallengeType.TURN_HEAD_LEFT;
            case "TURN_RIGHT" -> ChallengeType.TURN_HEAD_RIGHT;
            case "LOOK_DIRECTION" -> ChallengeType.LOOK_DIRECTION;
            case "LOOK_UP" -> ChallengeType.LOOK_UP;
            case "LOOK_DOWN" -> ChallengeType.LOOK_DOWN;
            case "LOOK_LEFT" -> ChallengeType.LOOK_LEFT;
            case "LOOK_RIGHT" -> ChallengeType.LOOK_RIGHT;
            default -> ChallengeType.BLINK; // safe fallback
        };
    }

    /** Convert core.ChallengeType → models.enums.ChallengeType */
    public io.mosip.liveness.models.enums.ChallengeType toDbChallenge(ChallengeType core) {
        return switch (core) {
            case BLINK -> io.mosip.liveness.models.enums.ChallengeType.BLINK;
            case SMILE -> io.mosip.liveness.models.enums.ChallengeType.SMILE;
            case TURN_HEAD_LEFT -> io.mosip.liveness.models.enums.ChallengeType.TURN_LEFT;
            case TURN_HEAD_RIGHT -> io.mosip.liveness.models.enums.ChallengeType.TURN_RIGHT;
            case LOOK_DIRECTION -> io.mosip.liveness.models.enums.ChallengeType.LOOK_DIRECTION;
            case LOOK_UP -> io.mosip.liveness.models.enums.ChallengeType.LOOK_UP;
            case LOOK_DOWN -> io.mosip.liveness.models.enums.ChallengeType.LOOK_DOWN;
            case LOOK_LEFT -> io.mosip.liveness.models.enums.ChallengeType.LOOK_LEFT;
            case LOOK_RIGHT -> io.mosip.liveness.models.enums.ChallengeType.LOOK_RIGHT;
        };
    }

    private RepeatedFailureAction mapFailurePolicy(FailurePolicy db) {
        if (db == null) return RepeatedFailureAction.LOCK_OUT;
        return switch (db) {
            case LOCK -> RepeatedFailureAction.LOCK_OUT;
            case ESCALATE -> RepeatedFailureAction.ESCALATE_TO_OPERATOR;
            case ALLOW_RETRY -> RepeatedFailureAction.FALLBACK;
        };
    }

    private EffectivePolicy defaultEffectivePolicy() {
        return new EffectivePolicy(
                true, true, 0.75, 0.50, 5, 7,
                1, 3, 8000L,
                EnumSet.of(ChallengeType.BLINK, ChallengeType.SMILE,
                        ChallengeType.TURN_HEAD_LEFT, ChallengeType.TURN_HEAD_RIGHT),
                RepeatedFailureAction.LOCK_OUT,
                // v3 fields
                -1.0,   // passiveThresholdActive
                30000L, // maxSessionDurationMs
                1,      // frameSamplingRate
                10,     // frameSamplingMinFps
                0.6,    // combinedPassiveWeight
                0.4);   // combinedActiveWeight
    }
}
