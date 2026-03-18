package cz.syntea.bedrock.wire.monitor.validation;

import cz.syntea.bedrock.wire.monitor.model.ValidationResult;
import cz.syntea.bedrock.wire.monitor.spi.MonitorResult;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates that the response body matches a regular expression.
 *
 * <p>Reads the {@code regex} parameter. Uses {@link Matcher#find()} semantics —
 * the pattern does not need to match the entire body, only a subsequence.
 * Compiled {@link Pattern} instances are cached per regex string for performance.
 *
 * <p>Returns {@link cz.syntea.bedrock.wire.monitor.model.ValidationVerdict#FAIL}
 * if the regex does not match or if the regex syntax is invalid.
 */
public class RegexValidator implements Validator {

    private static final String ALIAS = "regex";

    private final Map<String, Pattern> patternCache = new ConcurrentHashMap<>();

    @Override
    public String alias() {
        return ALIAS;
    }

    @Override
    public ValidationResult validate(MonitorResult result, Map<String, String> params) {
        String regex = params.get(ALIAS);
        if (regex == null || regex.isEmpty()) {
            return ValidationResult.fail(
                    "Validator 'regex' is configured but parameter 'regex' is missing");
        }

        Pattern pattern;
        try {
            pattern = patternCache.computeIfAbsent(regex, Pattern::compile);
        } catch (PatternSyntaxException e) {
            return ValidationResult.fail(
                    "Invalid regex pattern: '" + regex + "': " + e.getMessage());
        }

        String body = result.getResponseBody();
        if (body != null && pattern.matcher(body).find()) {
            return ValidationResult.pass();
        }

        return ValidationResult.fail(
                "Response body does not match regex: '" + regex + "'");
    }
}
