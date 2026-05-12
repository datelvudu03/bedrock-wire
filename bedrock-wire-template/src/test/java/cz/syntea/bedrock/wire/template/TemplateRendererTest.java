package cz.syntea.bedrock.wire.template;

import cz.syntea.bedrock.wire.template.exception.TemplateNotFoundException;
import cz.syntea.bedrock.wire.template.exception.TemplateRenderException;
import cz.syntea.bedrock.wire.template.source.Params;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TemplateRendererTest {

    private final TemplateRenderer renderer = TemplateRenderer.create();
    @TempDir
    Path tempDir;

    @Test
    void shouldRenderInlineString() {
        var result = renderer.render("Hello ${name}!",
                Params.of(Map.of("name", "world")));
        assertEquals("Hello world!", result);
    }

    @Test
    void shouldRenderFromFile() throws IOException {
        var file = writeTemplate("<req><id>${userId}</id></req>");
        var result = renderer.render(file, Params.of(Map.of("userId", "hejny")));
        assertEquals("<req><id>hejny</id></req>", result);
    }

    @Test
    void shouldThrowOnMissingFile() {
        var missing = tempDir.resolve("does-not-exist.xml");
        assertThrows(TemplateNotFoundException.class,
                () -> renderer.render(missing, Params.of(Map.of())));
    }

    @Test
    void shouldThrowOnInvalidUtf8File() throws IOException {
        var file = tempDir.resolve("invalid.xml");
        // 0xC0 0xAF — overlong UTF-8 encoding of '/', rejected as malformed
        Files.write(file, new byte[]{(byte) 0xC0, (byte) 0xAF});

        assertThrows(TemplateNotFoundException.class,
                () -> renderer.render(file, Params.of(Map.of())));
    }

    @Test
    void shouldIncludeAvailableParamsInRenderError() {
        var ex = assertThrows(TemplateRenderException.class,
                () -> renderer.render("${missing}",
                        Params.of(Map.of("a", "1", "b", "2"))));
        assertTrue(ex.getMessage().contains("availableParams="),
                "error must list available params: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("a"));
        assertTrue(ex.getMessage().contains("b"));
    }

    @Test
    void shouldThrowOnNullTemplateFile() {
        assertThrows(TemplateNotFoundException.class,
                () -> renderer.render((Path) null, Params.of(Map.of())));
    }

    @Test
    void shouldRenderConcurrentlyWithDistinctUuids() throws InterruptedException {
        int threads = 8;
        int iterations = 32;
        int total = threads * iterations;
        var template = "${statics['java.util.UUID'].randomUUID()}";
        Set<String> seen = ConcurrentHashMap.newKeySet();

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(total);
        try {
            for (int i = 0; i < total; i++) {
                pool.submit(() -> {
                    seen.add(renderer.render(template, Params.of(Map.of())));
                    latch.countDown();
                });
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS),
                    "concurrent renders did not complete in time");
        } finally {
            pool.shutdownNow();
        }
        assertEquals(total, seen.size(), "all renders must produce distinct UUIDs");
    }

    private Path writeTemplate(String content) throws IOException {
        var file = tempDir.resolve("template.xml");
        Files.writeString(file, content);
        return file;
    }
}