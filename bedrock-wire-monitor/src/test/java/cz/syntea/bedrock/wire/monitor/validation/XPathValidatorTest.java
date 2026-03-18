package cz.syntea.bedrock.wire.monitor.validation;

import cz.syntea.bedrock.wire.monitor.model.ValidationVerdict;
import cz.syntea.bedrock.wire.monitor.spi.MonitorResult;
import cz.syntea.bedrock.wire.monitor.spi.TransportStatus;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class XPathValidatorTest {

    private final XPathValidator validator = new XPathValidator();

    @Test
    void shouldPassOnMatchingNodeSet() {
        var result = validator.validate(
                result("<root><status>OK</status></root>"),
                Map.of("xpath", "//status"));
        assertEquals(ValidationVerdict.PASS, result.getVerdict());
    }

    @Test
    void shouldFailOnEmptyNodeSet() {
        var result = validator.validate(
                result("<root><other>value</other></root>"),
                Map.of("xpath", "//status"));
        assertEquals(ValidationVerdict.FAIL, result.getVerdict());
    }

    @Test
    void shouldPassOnBooleanTrue() {
        var result = validator.validate(
                result("<root><status>OK</status></root>"),
                Map.of("xpath", "//status = 'OK'"));
        assertEquals(ValidationVerdict.PASS, result.getVerdict());
    }

    @Test
    void shouldFailOnBooleanFalse() {
        var result = validator.validate(
                result("<root><status>ERROR</status></root>"),
                Map.of("xpath", "//status = 'OK'"));
        assertEquals(ValidationVerdict.FAIL, result.getVerdict());
    }

    @Test
    void shouldPassOnNonZeroCount() {
        var result = validator.validate(
                result("<root><item/><item/></root>"),
                Map.of("xpath", "count(//item)"));
        assertEquals(ValidationVerdict.PASS, result.getVerdict());
    }

    @Test
    void shouldFailOnXmlParseError() {
        var result = validator.validate(
                result("not xml at all"),
                Map.of("xpath", "//anything"));
        assertEquals(ValidationVerdict.FAIL, result.getVerdict());
    }

    @Test
    void shouldFailOnEmptyBody() {
        var result = validator.validate(
                result(""),
                Map.of("xpath", "//status"));
        assertEquals(ValidationVerdict.FAIL, result.getVerdict());
    }

    @Test
    void shouldFailWhenParameterMissing() {
        var result = validator.validate(result("<root/>"), Map.of());
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
