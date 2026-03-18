package cz.syntea.bedrock.wire.monitor.validation;

import cz.syntea.bedrock.wire.monitor.model.ValidationVerdict;
import cz.syntea.bedrock.wire.monitor.spi.MonitorResult;
import cz.syntea.bedrock.wire.monitor.spi.TransportStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContainsValidatorTest {

    private final ContainsValidator validator = new ContainsValidator();

    @Test
    void shouldPassWhenSubstringPresent() {
        var result = validator.validate(
                result("<status>OK</status>"),
                Map.of("contains", "<status>OK</status>"));
        assertEquals(ValidationVerdict.PASS, result.getVerdict());
    }

    @Test
    void shouldFailWhenSubstringAbsent() {
        var result = validator.validate(
                result("<status>ERROR</status>"),
                Map.of("contains", "<status>OK</status>"));
        assertEquals(ValidationVerdict.FAIL, result.getVerdict());
    }

    @Test
    void shouldBeCaseSensitive() {
        var result = validator.validate(
                result("<Status>OK</Status>"),
                Map.of("contains", "<status>OK</status>"));
        assertEquals(ValidationVerdict.FAIL, result.getVerdict());
    }

    @Test
    void shouldFailOnEmptyBody() {
        var result = validator.validate(
                result(""),
                Map.of("contains", "something"));
        assertEquals(ValidationVerdict.FAIL, result.getVerdict());
    }

    @Test
    void shouldFailWhenParameterMissing() {
        var result = validator.validate(result("body"), Map.of());
        assertEquals(ValidationVerdict.FAIL, result.getVerdict());
    }

    private MonitorResult result(String body) {
        return MonitorResult.builder()
                .transportStatus(TransportStatus.RESPONSE_RECEIVED)
                .httpStatus(200)
                .responseBody(body)
                .build();
    }
}
