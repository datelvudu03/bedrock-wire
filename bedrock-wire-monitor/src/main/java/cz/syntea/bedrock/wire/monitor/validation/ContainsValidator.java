package cz.syntea.bedrock.wire.monitor.validation;

import cz.syntea.bedrock.wire.monitor.model.ValidationResult;
import cz.syntea.bedrock.wire.monitor.spi.MonitorResult;

import java.util.Map;

/**
 * Validates that the response body contains a specified substring.
 *
 * <p>Reads the {@code contains} parameter. The check is case-sensitive.
 * Returns {@link cz.syntea.bedrock.wire.monitor.model.ValidationVerdict#FAIL}
 * if the substring is not found in the response body.
 */
public class ContainsValidator implements Validator {

    private static final String ALIAS = "contains";

    @Override
    public String alias() {
        return ALIAS;
    }

    @Override
    public ValidationResult validate(MonitorResult result, Map<String, String> params) {
        String expected = params.get(ALIAS);
        if (expected == null || expected.isEmpty()) {
            return ValidationResult.fail(
                    "Validator 'contains' is configured but parameter 'contains' is missing");
        }

        String body = result.getResponseBody();
        if (body != null && body.contains(expected)) {
            return ValidationResult.pass();
        }

        return ValidationResult.fail(
                "Response body does not contain expected substring: '" + expected + "'");
    }
}
