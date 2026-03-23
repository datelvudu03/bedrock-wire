package cz.syntea.bedrock.wire.monitor.model;

/**
 * Outcome of a single {@link cz.syntea.bedrock.wire.monitor.validation.Validator} execution.
 *
 * <p>When multiple validators run, the overall verdict follows worst-case aggregation:
 * at least one {@code FAIL} → {@code FAIL}; at least one {@code WARN} (without FAIL) →
 * {@code WARN}; otherwise → {@code PASS}. An {@code ERROR} from a validator exception
 * immediately terminates validation and maps to {@code MonitorStatus.ERROR}.
 */
public enum ValidationVerdict {

    /**
     * Validation passed.
     */
    PASS,

    /**
     * Validation warning (non-critical).
     */
    WARN,

    /**
     * Validation failed.
     */
    FAIL,

    /**
     * Validator threw an exception. Maps to {@code MonitorStatus.ERROR}
     * (internal error, not a service health indicator).
     */
    ERROR
}