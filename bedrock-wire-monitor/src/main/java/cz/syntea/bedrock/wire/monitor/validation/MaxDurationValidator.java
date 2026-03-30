package cz.syntea.bedrock.wire.monitor.validation;

import cz.syntea.bedrock.wire.monitor.config.PropertiesFileConfigProvider;
import cz.syntea.bedrock.wire.monitor.model.ValidationResult;
import cz.syntea.bedrock.wire.monitor.spi.MonitorResult;

import java.time.Duration;
import java.util.Map;

/**
 * Validates that the transport duration does not exceed a configured threshold.
 *
 * <p>Reads the {@code maxDuration} parameter (Duration format with time unit,
 * e.g. {@code 5s}, {@code 500ms}). Unlike other validators, this returns
 * {@link cz.syntea.bedrock.wire.monitor.model.ValidationVerdict#WARN}
 * (not FAIL) when the threshold is exceeded, because slow responses are
 * degraded performance, not functional failures.
 */
public class MaxDurationValidator implements Validator {

    private static final String ALIAS = "maxDuration";

    @Override
    public String alias() {
        return ALIAS;
    }

    @Override
    public ValidationResult validate(MonitorResult result, Map<String, String> params) {
        String maxDurationStr = params.get(ALIAS);
        if (maxDurationStr == null || maxDurationStr.isBlank()) {
            return ValidationResult.fail(
                    "Validator 'maxDuration' is configured but parameter 'maxDuration' is missing");
        }

        Duration threshold = PropertiesFileConfigProvider.parseDuration(maxDurationStr, ALIAS);
        Duration actual = result.getTransportDuration();

        if (actual == null) {
            return ValidationResult.pass();
        }

        if (actual.compareTo(threshold) > 0) {
            return ValidationResult.warn(
                    "Transport duration " + actual.toMillis() + "ms exceeded threshold "
                            + threshold.toMillis() + "ms");
        }

        return ValidationResult.pass();
    }
}
