package cz.syntea.bedrock.wire.monitor.model;

import lombok.Value;

/**
 * Result of a single {@link cz.syntea.bedrock.wire.monitor.validation.Validator}
 * execution, combining a {@link ValidationVerdict} with an optional diagnostic message.
 */
@Value
public class ValidationResult {

    /**
     * The validation outcome.
     */
    ValidationVerdict verdict;

    /**
     * Diagnostic message explaining the verdict. MAY be {@code null} for {@code PASS}.
     * SHOULD be non-null for {@code WARN} and {@code FAIL} to aid troubleshooting.
     */
    String message;

    /**
     * Creates a {@code PASS} result with no message.
     *
     * @return a passing validation result
     */
    public static ValidationResult pass() {
        return new ValidationResult(ValidationVerdict.PASS, null);
    }

    /**
     * Creates a {@code WARN} result with a diagnostic message.
     *
     * @param message diagnostic message; should not be {@code null}
     * @return a warning validation result
     */
    public static ValidationResult warn(String message) {
        return new ValidationResult(ValidationVerdict.WARN, message);
    }

    /**
     * Creates a {@code FAIL} result with a diagnostic message.
     *
     * @param message diagnostic message; should not be {@code null}
     * @return a failing validation result
     */
    public static ValidationResult fail(String message) {
        return new ValidationResult(ValidationVerdict.FAIL, message);
    }
}
