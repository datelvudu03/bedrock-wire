package cz.syntea.bedrock.wire.monitor.validation;

import cz.syntea.bedrock.wire.monitor.model.ValidationVerdict;
import cz.syntea.bedrock.wire.monitor.spi.MonitorResult;
import cz.syntea.bedrock.wire.monitor.spi.TransportStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MaxDurationValidatorTest {

    private final MaxDurationValidator validator = new MaxDurationValidator();

    @Test
    void shouldPassWhenUnderThreshold() {
        var result = validator.validate(
                result(Duration.ofMillis(500)),
                Map.of("maxDuration", "1s"));
        assertEquals(ValidationVerdict.PASS, result.getVerdict());
    }

    @Test
    void shouldWarnWhenOverThreshold() {
        var result = validator.validate(
                result(Duration.ofSeconds(10)),
                Map.of("maxDuration", "5s"));
        assertEquals(ValidationVerdict.WARN, result.getVerdict());
    }

    @Test
    void shouldPassWhenExactlyAtThreshold() {
        var result = validator.validate(
                result(Duration.ofSeconds(5)),
                Map.of("maxDuration", "5s"));
        assertEquals(ValidationVerdict.PASS, result.getVerdict());
    }

    @Test
    void shouldPassWhenDurationNull() {
        MonitorResult monResult = MonitorResult.builder()
                .transportStatus(TransportStatus.RESPONSE_RECEIVED)
                .httpStatus(200)
                .transportDuration(null)
                .build();
        var result = validator.validate(monResult, Map.of("maxDuration", "5s"));
        assertEquals(ValidationVerdict.PASS, result.getVerdict());
    }

    @Test
    void shouldFailWhenParameterMissing() {
        var result = validator.validate(result(Duration.ofSeconds(1)), Map.of());
        assertEquals(ValidationVerdict.FAIL, result.getVerdict());
    }

    private MonitorResult result(Duration duration) {
        return MonitorResult.builder()
                .transportStatus(TransportStatus.RESPONSE_RECEIVED)
                .httpStatus(200)
                .transportDuration(duration)
                .build();
    }
}
