package cz.syntea.bedrock.wire.monitor.validation;

import cz.syntea.bedrock.wire.monitor.model.ValidationVerdict;
import cz.syntea.bedrock.wire.monitor.spi.MonitorResult;
import cz.syntea.bedrock.wire.monitor.spi.TransportStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpStatusValidatorTest {

    private final HttpStatusValidator validator = new HttpStatusValidator();

    private MonitorResult result(int status) {
        return MonitorResult.builder()
                .transportStatus(TransportStatus.RESPONSE_RECEIVED)
                .httpStatus(status)
                .build();
    }

    @ParameterizedTest
    @CsvSource({
            "200, 200, PASS",
            "200, 201, FAIL",
            "200, 404, FAIL",
    })
    void shouldValidateSingleCode(String spec, int actual, ValidationVerdict expected) {
        var result = validator.validate(result(actual), Map.of("httpStatus", spec));
        assertEquals(expected, result.getVerdict());
    }

    @ParameterizedTest
    @CsvSource({
            "'200,204', 200, PASS",
            "'200,204', 204, PASS",
            "'200,204', 201, FAIL",
    })
    void shouldValidateSet(String spec, int actual, ValidationVerdict expected) {
        var result = validator.validate(result(actual), Map.of("httpStatus", spec));
        assertEquals(expected, result.getVerdict());
    }

    @ParameterizedTest
    @CsvSource({
            "200-299, 200, PASS",
            "200-299, 250, PASS",
            "200-299, 299, PASS",
            "200-299, 300, FAIL",
            "200-299, 199, FAIL",
    })
    void shouldValidateRange(String spec, int actual, ValidationVerdict expected) {
        var result = validator.validate(result(actual), Map.of("httpStatus", spec));
        assertEquals(expected, result.getVerdict());
    }

    @ParameterizedTest
    @CsvSource({
            "'200-204,301', 200, PASS",
            "'200-204,301', 204, PASS",
            "'200-204,301', 301, PASS",
            "'200-204,301', 205, FAIL",
            "'200-204,301', 302, FAIL",
    })
    void shouldValidateCombination(String spec, int actual, ValidationVerdict expected) {
        var result = validator.validate(result(actual), Map.of("httpStatus", spec));
        assertEquals(expected, result.getVerdict());
    }

    @Test
    void shouldFailWhenParameterMissing() {
        var result = validator.validate(result(200), Map.of());
        assertEquals(ValidationVerdict.FAIL, result.getVerdict());
    }
}
