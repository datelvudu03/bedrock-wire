package cz.syntea.bedrock.wire.template.source;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import cz.syntea.bedrock.wire.template.exception.TemplateParamException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link Params} source loaded from a JSON file. The root JSON value MUST be an object;
 * nested JSON objects become nested {@link Map} instances accessible via dot-notation
 * inside templates.
 *
 * <p>The file is read lazily — the first call to {@link #asMap()} triggers the read,
 * subsequent calls return a defensive copy of the cached map.
 *
 * <p>Thread-safe: synchronized lazy initialization.
 *
 * @since 1.0
 */
public final class JsonParams implements Params {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Path jsonFile;
    private volatile Map<String, Object> cached;

    /**
     * Creates a new instance pointing to the given JSON file. The file is NOT read at
     * construction; it is read on first {@link #asMap()} call.
     *
     * @param jsonFile path to the JSON file; never {@code null}
     * @throws TemplateParamException if {@code jsonFile} is {@code null}
     */
    public JsonParams(Path jsonFile) {
        if (jsonFile == null) {
            throw new TemplateParamException("JsonParams: jsonFile MUST NOT be null");
        }
        this.jsonFile = jsonFile;
    }

    /**
     * {@inheritDoc}
     *
     * <p>On first call: reads the file as UTF-8 and parses it as a JSON object. On
     * subsequent calls: returns a defensive copy of the cached map.
     *
     * @return resolved parameter map
     * @throws TemplateParamException if the file does not exist, cannot be read, or
     *                                contains invalid JSON / a non-object root
     */
    @Override
    public Map<String, Object> asMap() {
        Map<String, Object> local = cached;
        if (local == null) {
            synchronized (this) {
                local = cached;
                if (local == null) {
                    local = load();
                    cached = local;
                }
            }
        }
        return new LinkedHashMap<>(local);
    }

    private Map<String, Object> load() {
        if (!Files.isRegularFile(jsonFile)) {
            throw new TemplateParamException(
                    "JsonParams: file not found or not a regular file: " + jsonFile);
        }
        try (var in = Files.newInputStream(jsonFile)) {
            Map<String, Object> result = MAPPER.readValue(in, MAP_TYPE);
            if (result == null) {
                throw new TemplateParamException(
                        "JsonParams: file " + jsonFile + " parsed to null (root must be a JSON object)");
            }
            return result;
        } catch (IOException ex) {
            throw new TemplateParamException(
                    "JsonParams: failed to read or parse " + jsonFile + ": " + ex.getMessage(), ex);
        }
    }
}