package cz.syntea.bedrock.wire.classic.model;

import cz.syntea.bedrock.wire.classic.json.JsonFormat;
import lombok.Builder;
import lombok.Value;

import java.time.Duration;
import java.util.List;
import java.util.Map;
/**
 * Result of a completed HTTP request returned by
 * {@link cz.syntea.bedrock.wire.classic.registry.HttpClient#execute(HttpRequest)}.
 *
 * <h3>Status code</h3>
 * Represents the actual HTTP status returned by the server.
 * If no response was received (transport error), {@code execute()} emits
 * {@code Mono.error(...)} — a zero {@code statusCode} is never used.
 *
 * <h3>Redirect handling</h3>
 * HTTP 3xx responses (301, 302, 303, 307, 308) are NOT followed; the implementation
 * emits {@code Mono.error(RedirectNotSupportedException)}.
 * HTTP 304 is treated as a normal response.
 *
 * <h3>Response body</h3>
 * Always UTF-8 text. Empty string when the response contains no body (never {@code null}).
 *
 * <h3>Duration</h3>
 * Time from sending the request to receiving the <em>last byte</em> of the response body.
 */
@Value
@Builder
public class HttpResponse {

    /**
     * HTTP status code (e.g. 200, 404). Never zero.
     */
    int statusCode;

    /**
     * Response headers as returned by the server. Never {@code null}.
     */
    Map<String, List<String>> headers;

    /**
     * Response body decoded as UTF-8.
     * Empty string when there is no body; never {@code null}.
     *
     * <p><strong>Streaming:</strong> the response body is consumed chunk-by-chunk via
     * {@code bodyToFlux(DataBuffer.class)}. Each chunk's byte count is checked against
     * {@link cz.syntea.bedrock.wire.classic.config.HttpClientConfig#getMaxResponseBodySize()}.
     * If the running total exceeds the limit, the download is cancelled immediately —
     * no oversized body is allocated on the heap.
     *
     * <p>For payloads within the configured limit, the final {@code String} is decoded
     * from the accumulated raw bytes in a single UTF-8 pass, avoiding multi-byte
     * character boundary issues.
     */
    String responseBody;

    /**
     * Elapsed time from sending the request to receiving the last byte of the
     * response body (i.e. total round-trip time including body transfer).
     */
    Duration duration;

    /**
     * Pretty-printed JSON representation for logging.
     * Falls back to class name + error if serialization fails.
     */
    @Override
    public String toString() {
        return JsonFormat.toJson(this);
    }
}