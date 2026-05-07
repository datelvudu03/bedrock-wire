package cz.syntea.bedrock.wire.template.engine;

import cz.syntea.bedrock.wire.template.exception.TemplateRenderException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreeMarkerEngineTest {

    private final FreeMarkerEngine engine = new FreeMarkerEngine(
            FreeMarkerEngineConfig.builder().build());

    @Test
    void shouldExposeAlias() {
        assertEquals("freemarker", engine.alias());
    }

    @Test
    void shouldSubstituteVariable() {
        assertEquals("Hello world!",
                engine.render("Hello ${name}!", Map.of("name", "world")));
    }

    @Test
    void shouldUseDefaultValueWhenMissing() {
        assertEquals("DEV", engine.render("${mode!'DEV'}", Map.of()));
    }

    @Test
    void shouldEvaluateConditional() {
        assertEquals("yes", engine.render(
                "<#if mode == 'PROD'>yes<#else>no</#if>", Map.of("mode", "PROD")));
        assertEquals("no", engine.render(
                "<#if mode == 'PROD'>yes<#else>no</#if>", Map.of("mode", "DEV")));
    }

    @Test
    void shouldIterateList() {
        var result = engine.render(
                "<#list items as i>${i}<#sep>,</#list>",
                Map.of("items", List.of("a", "b", "c")));
        assertEquals("a,b,c", result);
    }

    @Test
    void shouldRenderNowAsIsoUtc() {
        var result = engine.render("${.now?iso_utc}", Map.of());
        // FreeMarker iso_utc: e.g. 2026-05-07T12:34:56Z (no fractional seconds)
        assertTrue(result.matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z"),
                "Expected ISO 8601 UTC format, got: " + result);
    }

    @Test
    void shouldRenderUuidViaStatics() {
        var result = engine.render(
                "${statics['java.util.UUID'].randomUUID()}", Map.of());
        var uuid = UUID.fromString(result);
        assertEquals(4, uuid.version());
    }

    @Test
    void shouldFailWhenStaticsDisabled() {
        var noStatics = new FreeMarkerEngine(
                FreeMarkerEngineConfig.builder().exposeStaticMethods(false).build());
        assertThrows(TemplateRenderException.class, () -> noStatics.render(
                "${statics['java.util.UUID'].randomUUID()}", Map.of()));
    }

    @Test
    void shouldThrowOnUndefinedVariable() {
        var ex = assertThrows(TemplateRenderException.class,
                () -> engine.render("${missing}", Map.of("a", "1", "b", "2")));
        assertTrue(ex.getMessage().contains("availableParams="),
                "error must list available params: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("a") && ex.getMessage().contains("b"),
                "error must include parameter names: " + ex.getMessage());
    }

    @Test
    void shouldThrowOnSyntaxError() {
        assertThrows(TemplateRenderException.class,
                () -> engine.render("${unterminated", Map.of()));
    }
}