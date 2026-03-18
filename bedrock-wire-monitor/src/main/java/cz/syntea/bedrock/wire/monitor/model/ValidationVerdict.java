package cz.syntea.bedrock.wire.monitor.model;

/**
 * Outcome of a single {@link cz.syntea.bedrock.wire.monitor.validation.Validator} execution.
 *
 * <p>When multiple validators run, the overall verdict follows worst-case aggregation:
 * at least one {@code FAIL} → {@code FAIL}; at least one {@code WARN} (without FAIL) →
 * {@code WARN}; otherwise → {@code PASS}.
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
    FAIL
}
