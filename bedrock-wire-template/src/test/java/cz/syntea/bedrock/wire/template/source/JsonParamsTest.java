package cz.syntea.bedrock.wire.template.source;

import cz.syntea.bedrock.wire.template.exception.TemplateParamException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JsonParamsTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldLoadFlatJson() throws IOException {
        var file = writeJson("""
                {"clientId": "monitor-prod", "mode": "PROD"}
                """);
        var map = new JsonParams(file).asMap();
        assertEquals("monitor-prod", map.get("clientId"));
        assertEquals("PROD", map.get("mode"));
    }

    @Test
    void shouldExposeNestedObjectsAsNestedMaps() throws IOException {
        var file = writeJson("""
                {
                  "deployment": {
                    "env": "production",
                    "region": "eu-central"
                  }
                }
                """);
        var map = new JsonParams(file).asMap();
        var deployment = assertInstanceOf(Map.class, map.get("deployment"));
        assertEquals("production", deployment.get("env"));
        assertEquals("eu-central", deployment.get("region"));
    }

    @Test
    void shouldCacheAfterFirstLoad() throws IOException {
        var file = writeJson("""
                {"a": "1"}
                """);
        var params = new JsonParams(file);

        params.asMap();
        Files.delete(file);
        var second = params.asMap();

        assertEquals("1", second.get("a"));
    }

    @Test
    void shouldThrowOnMissingFile() {
        var params = new JsonParams(tempDir.resolve("does-not-exist.json"));
        assertThrows(TemplateParamException.class, params::asMap);
    }

    @Test
    void shouldThrowOnInvalidJson() throws IOException {
        var file = writeJson("{this is not valid json}");
        var params = new JsonParams(file);
        assertThrows(TemplateParamException.class, params::asMap);
    }

    @Test
    void shouldThrowOnNullPath() {
        assertThrows(TemplateParamException.class, () -> new JsonParams(null));
    }

    private Path writeJson(String content) throws IOException {
        var file = tempDir.resolve("params.json");
        Files.writeString(file, content);
        return file;
    }
}