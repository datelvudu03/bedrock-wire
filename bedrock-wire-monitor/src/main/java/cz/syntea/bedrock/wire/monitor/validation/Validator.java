package cz.syntea.bedrock.wire.monitor.validation;

import cz.syntea.bedrock.wire.monitor.model.ValidationResult;
import cz.syntea.bedrock.wire.monitor.spi.MonitorResult;

import java.util.Map;

/**
 * Evaluates an HTTP response from a monitor check.
 *
 * <p>Validators are invoked in the order defined in
 * {@code validation.validators} configuration, and only when
 * {@link cz.syntea.bedrock.wire.monitor.spi.TransportStatus#RESPONSE_RECEIVED}
 * is the transport outcome.
 *
 * <h3>Thread safety</h3>
 * Implementations MUST be thread-safe and stateless. A single instance is shared
 * across all check runs.
 *
 * <h3>Registration</h3>
 * Each validator is registered under its {@link #alias()} in the
 * {@link ValidatorRegistry}. The alias is referenced in configuration
 * (e.g. {@code validation.validators = httpStatus,contains}).
 */
public interface Validator {

    /**
     * Returns the unique alias used to reference this validator in configuration.
     *
     * <p>Examples: {@code "httpStatus"}, {@code "contains"}, {@code "regex"},
     * {@code "maxDuration"}, {@code "xpath"}.
     *
     * @return the alias string; never {@code null} or empty
     */
    String alias();

    /**
     * Validates the given transport result.
     *
     * @param result the transport result to validate; never {@code null};
     *               guaranteed to have
     *               {@link cz.syntea.bedrock.wire.monitor.spi.TransportStatus#RESPONSE_RECEIVED}
     * @param params validator-specific parameters from configuration
     *               (keys without the {@code validation.} prefix);
     *               never {@code null}, may be empty
     * @return the validation result; never {@code null}
     */
    ValidationResult validate(MonitorResult result, Map<String, String> params);
}
