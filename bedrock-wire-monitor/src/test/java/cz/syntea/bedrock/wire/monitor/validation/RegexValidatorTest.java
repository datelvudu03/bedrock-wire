package cz.syntea.bedrock.wire.monitor.validation;

import cz.syntea.bedrock.wire.monitor.model.ValidationVerdict;
import cz.syntea.bedrock.wire.monitor.spi.MonitorResult;
import cz.syntea.bedrock.wire.monitor.spi.TransportStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegexValidatorTest {

    private final RegexValidator validator = new RegexValidator();

    @Test
    void shouldPassOnMatch() {
        var result = validator.validate(result("<r>OK</r>"), Map.of("regex", "<r>OK</r>"));
        assertEquals(ValidationVerdict.PASS, result.getVerdict());
    }

    @Test
    void shouldPassOnPartialMatch() {
        var result = validator.validate(result("prefix <r>OK</r> suffix"), Map.of("regex", "<r>OK</r>"));
        assertEquals(ValidationVerdict.PASS, result.getVerdict());
    }

    @Test
    void shouldFailOnNoMatch() {
        var result = validator.validate(result("<r>FAIL</r>"), Map.of("regex", "<r>OK</r>"));
        assertEquals(ValidationVerdict.FAIL, result.getVerdict());
    }

    @Test
    void shouldSupportRegexPatterns() {
        var result = validator.validate(result("status: 200"), Map.of("regex", "status:\\s*\\d{3}"));
        assertEquals(ValidationVerdict.PASS, result.getVerdict());
    }

    @Test
    void shouldFailOnInvalidRegex() {
        var result = validator.validate(result("body"), Map.of("regex", "[invalid"));
        assertEquals(ValidationVerdict.FAIL, result.getVerdict());
    }

    @Test
    void shouldFailWhenParameterMissing() {
        var result = validator.validate(result("body"), Map.of());
        assertEquals(ValidationVerdict.FAIL, result.getVerdict());
    }

    @Test
    void shouldCachePatterns() {
        // Call twice with same regex — second call should use cached Pattern
        validator.validate(result("abc"), Map.of("regex", "abc"));
        var result = validator.validate(result("abc"), Map.of("regex", "abc"));
        assertEquals(ValidationVerdict.PASS, result.getVerdict());
    }

    private MonitorResult result(String body) {
        return MonitorResult.builder()
                .transportStatus(TransportStatus.RESPONSE_RECEIVED)
                .httpStatus(200)
                .responseBody(body)
                .build();
    }
}
