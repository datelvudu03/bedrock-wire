package cz.syntea.bedrock.wire.monitor.engine;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateProcessorTest {

    private final TemplateProcessor processor = new TemplateProcessor();

    @TempDir
    Path tempDir;

    @Test
    void shouldSubstituteStaticParams() throws IOException {
        Path file = writeTemplate("<req><id>$(clientId)</id><region>$(region)</region></req>");

        String result = processor.process(file.toString(), Map.of("clientId", "test", "region", "eu"));
        assertEquals("<req><id>test</id><region>eu</region></req>", result);
    }

    @Test
    void shouldSubstituteUuid() throws IOException {
        Path file = writeTemplate("<req>$(uuid)</req>");

        String result1 = processor.process(file.toString(), Map.of());
        String result2 = processor.process(file.toString(), Map.of());

        assertTrue(result1.matches("<req>[a-f0-9-]{36}</req>"));
        assertNotEquals(result1, result2, "Each invocation should produce a unique UUID");
    }

    @Test
    void shouldSubstituteTimestamp() throws IOException {
        Path file = writeTemplate("<req>$(timestamp)</req>");

        String result = processor.process(file.toString(), Map.of());
        // ISO 8601 UTC format: e.g. 2026-03-14T12:00:00Z
        assertTrue(result.matches("<req>\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*Z</req>"));
    }

    @Test
    void shouldLeaveUnresolvedVariables() throws IOException {
        Path file = writeTemplate("<req>$(missing)</req>");

        String result = processor.process(file.toString(), Map.of());
        assertEquals("<req>$(missing)</req>", result);
    }

    @Test
    void shouldHandleNoVariables() throws IOException {
        Path file = writeTemplate("<req>plain text</req>");

        String result = processor.process(file.toString(), Map.of());
        assertEquals("<req>plain text</req>", result);
    }

    @Test
    void shouldThrowOnMissingFile() {
        assertThrows(TemplateProcessor.TemplateException.class, () ->
                processor.process("/nonexistent/file.xml", Map.of()));
    }

    @Test
    void shouldThrowOnInvalidUtf8() throws IOException {
        Path file = tempDir.resolve("invalid.xml");
        Files.write(file, new byte[]{(byte) 0xC0, (byte) 0xAF}); // invalid UTF-8

        assertThrows(TemplateProcessor.TemplateException.class, () ->
                processor.process(file.toString(), Map.of()));
    }

    @Test
    void shouldProcessRealTemplate() {
        // Test with the actual test template if available
        Path testTemplate = Path.of("src/test/resources/templates/health-check.xml");
        if (Files.exists(testTemplate)) {
            String result = assertDoesNotThrow(() ->
                    processor.process(testTemplate.toString(),
                            Map.of("clientId", "prod-1", "region", "eu-west")));

            assertTrue(result.contains("<clientId>prod-1</clientId>"));
            assertTrue(result.contains("<region>eu-west</region>"));
            assertTrue(result.contains("<requestId>")); // UUID substituted
            assertTrue(result.contains("<timestamp>")); // timestamp substituted
        }
    }

    private Path writeTemplate(String content) throws IOException {
        Path file = tempDir.resolve("template.xml");
        Files.writeString(file, content);
        return file;
    }
}
