package cz.syntea.bedrock.wire.template;

import cz.syntea.bedrock.wire.template.source.Params;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end smoke test using the real B1WS SOAP template fixture.
 *
 * <p>Verifies the full happy path: file loading, FreeMarker rendering, statics expansion,
 * built-in {@code .now}, default-value operator, and JSON + Spring + Map source merge.
 */
class B1WSTemplateSmokeTest {

    private static final Path TEMPLATE = Path.of("src/test/resources/templates/B1WS-2023-01-request.xml");
    private static final Path JSON_PARAMS = Path.of("src/test/resources/templates/health-check.json");

    private final TemplateRenderer renderer = TemplateRenderer.create();

    private static String extractAttribute(String xml, String attrName) {
        Matcher m = Pattern.compile(attrName + "=\"([^\"]*)\"").matcher(xml);
        return m.find() ? m.group(1) : null;
    }

    @Test
    void shouldRenderB1WSEnvelopeWithMapParams() {
        var output = renderer.render(TEMPLATE,
                Params.of(Map.of("mode", "PROD", "userId", "hejny")));

        assertTrue(output.contains("<s:Envelope"));
        assertTrue(output.contains("Mode=\"PROD\""));
        assertTrue(output.contains("UserID=\"hejny\""));
        assertTrue(output.contains("Flow=\"B1WS_Get\""), "default value for missing 'flow' must apply");

        var ident = extractAttribute(output, "IdentZpravy");
        assertNotNull(ident, "IdentZpravy attribute must be present");
        var uuid = UUID.fromString(ident);
        assertEquals(4, uuid.version(), "statics['java.util.UUID'].randomUUID() must yield UUIDv4");

        var ts = extractAttribute(output, "Timestamp");
        assertNotNull(ts, "Timestamp attribute must be present");
        // FreeMarker iso_utc with no fractional seconds: 2026-05-07T12:34:56Z
        assertTrue(ts.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"),
                "Timestamp must be ISO 8601 UTC: " + ts);
        Instant.parse(ts);  // throws if not parseable
    }

    @Test
    void shouldOverrideDefaultFlowFromMap() {
        var output = renderer.render(TEMPLATE,
                Params.of(Map.of("mode", "DEV", "userId", "tester", "flow", "B1WS_Custom")));

        assertTrue(output.contains("Flow=\"B1WS_Custom\""));
    }

    @Test
    void shouldMergeJsonAndMapSourcesWithLastWinning() {
        var output = renderer.render(TEMPLATE,
                Params.combined(
                        Params.fromJson(JSON_PARAMS),
                        Params.of(Map.of("mode", "PROD", "userId", "hejny"))));

        // JSON contributes nothing the template uses directly, but must not break the render
        assertTrue(output.contains("Mode=\"PROD\""));
        assertTrue(output.contains("UserID=\"hejny\""));
    }

    @Test
    void shouldMergeSpringEnvWithStrippedPrefix() {
        var env = new MockEnvironment()
                .withProperty("monitor.check.b1wsPing.mode", "PROD")
                .withProperty("monitor.check.b1wsPing.userId", "hejny")
                .withProperty("monitor.check.b1wsPing.flow", "B1WS_Spring");

        var output = renderer.render(TEMPLATE,
                Params.fromSpring(env, "monitor.check.b1wsPing."));

        assertTrue(output.contains("Mode=\"PROD\""));
        assertTrue(output.contains("UserID=\"hejny\""));
        assertTrue(output.contains("Flow=\"B1WS_Spring\""));
    }

    @Test
    void shouldProduceDistinctIdentZpravyAcrossRenders() {
        var params = Params.of(Map.of("mode", "PROD", "userId", "hejny"));
        var first = extractAttribute(renderer.render(TEMPLATE, params), "IdentZpravy");
        var second = extractAttribute(renderer.render(TEMPLATE, params), "IdentZpravy");

        assertNotNull(first);
        assertNotNull(second);
        assertTrue(!first.equals(second), "successive renders must produce distinct UUIDs");
    }
}