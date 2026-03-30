package cz.syntea.bedrock.wire.monitor.validation;

import cz.syntea.bedrock.wire.monitor.model.ValidationResult;
import cz.syntea.bedrock.wire.monitor.spi.MonitorResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates that the HTTP response status code matches the configured expectation.
 *
 * <p>Reads the {@code httpStatus} parameter, which supports the following formats:
 * <ul>
 *   <li>Single code: {@code 200}</li>
 *   <li>Set: {@code 200,204}</li>
 *   <li>Range: {@code 200-299}</li>
 *   <li>Combination: {@code 200-204,301}</li>
 * </ul>
 *
 * <p>Returns {@link cz.syntea.bedrock.wire.monitor.model.ValidationVerdict#FAIL} if
 * the actual HTTP status does not match any of the specified codes or ranges.
 */
public class HttpStatusValidator implements Validator {

    private static final String ALIAS = "httpStatus";

    /**
     * Parses the status specification into a list of matchers.
     *
     * @param spec comma-separated list of single codes and/or ranges
     * @return list of matchers
     * @throws IllegalArgumentException if the spec is malformed
     */
    static List<StatusMatcher> parse(String spec) {
        List<StatusMatcher> matchers = new ArrayList<>();
        String[] parts = spec.split(",");
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int dash = trimmed.indexOf('-');
            if (dash >= 0) {
                int from = Integer.parseInt(trimmed.substring(0, dash).trim());
                int to = Integer.parseInt(trimmed.substring(dash + 1).trim());
                matchers.add(new RangeMatcher(from, to));
            } else {
                matchers.add(new SingleMatcher(Integer.parseInt(trimmed)));
            }
        }
        return matchers;
    }

    @Override
    public String alias() {
        return ALIAS;
    }

    @Override
    public ValidationResult validate(MonitorResult result, Map<String, String> params) {
        String expected = params.get(ALIAS);
        if (expected == null || expected.isBlank()) {
            return ValidationResult.fail("Validator 'httpStatus' is configured but parameter 'httpStatus' is missing");
        }

        int actual = result.getHttpStatus();
        List<StatusMatcher> matchers = parse(expected.trim());

        for (StatusMatcher matcher : matchers) {
            if (matcher.matches(actual)) {
                return ValidationResult.pass();
            }
        }

        return ValidationResult.fail(
                "HTTP status " + actual + " does not match expected: " + expected);
    }

    // ── Matcher types ───────────────────────────────────────────────────────

    sealed interface StatusMatcher permits SingleMatcher, RangeMatcher {
        boolean matches(int status);
    }

    record SingleMatcher(int code) implements StatusMatcher {
        @Override
        public boolean matches(int status) {
            return status == code;
        }
    }

    record RangeMatcher(int from, int to) implements StatusMatcher {
        @Override
        public boolean matches(int status) {
            return status >= from && status <= to;
        }
    }
}
