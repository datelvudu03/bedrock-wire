package cz.syntea.bedrock.wire.monitor.json;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Shared JSON formatting utility for {@code toString()} implementations.
 *
 * <p>Uses a single pre-configured {@link ObjectMapper} with:
 * <ul>
 *   <li>{@link JavaTimeModule} for {@code Instant}, {@code Duration}, etc.</li>
 *   <li>Pretty-print enabled for readable log output.</li>
 * </ul>
 *
 * <p>Thread-safe — {@link ObjectMapper} is safe for concurrent reads after configuration.
 */
public final class JsonFormat {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(SerializationFeature.INDENT_OUTPUT);

    private JsonFormat() {
    }

    /**
     * Serializes the given object to a pretty-printed JSON string.
     * Falls back to {@link Object#toString()} if serialization fails.
     *
     * @param obj the object to serialize
     * @return JSON string, or default toString on error
     */
    public static String toJson(Object obj) {
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            return obj.getClass().getSimpleName() + "[JSON serialization failed: " + e.getMessage() + "]";
        }
    }
}