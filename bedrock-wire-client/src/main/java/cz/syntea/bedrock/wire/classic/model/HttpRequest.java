package cz.syntea.bedrock.wire.classic.model;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Description of a single HTTP request passed to
 * {@link cz.syntea.bedrock.wire.classic.registry.HttpClient#execute(HttpRequest)}.
 *
 * <h3>URL rules</h3>
 * <ul>
 *   <li>{@code url} MUST be relative (start with {@code /}).</li>
 *   <li>Absolute URLs cause {@code execute()} to throw {@link IllegalArgumentException}
 *       immediately (fail-fast — no silent normalization).</li>
 * </ul>
 *
 * <h3>Header merge semantics</h3>
 * Headers in this object take priority over {@code defaultHeaders} from
 * {@link cz.syntea.bedrock.wire.classic.config.HttpClientConfig}. When the same header
 * key (case-insensitive per RFC 7230) exists in both, the request-level value
 * <em>replaces</em> (not appends) the default value.
 *
 * <h3>Body</h3>
 * {@code null} is treated as an empty request body. For {@code HEAD} requests the body
 * is silently discarded regardless of this field.
 */
@Value
@Builder(toBuilder = true)
public class HttpRequest {

    /**
     * HTTP method. Case-insensitive; the implementation normalizes to uppercase.
     * Required.
     */
    HttpMethod method;

    /**
     * Relative URL starting with {@code /}.
     * The final URL is: {@code baseUrl + url}.
     * Required.
     */
    URI url;

    /**
     * Per-request headers. Must not be {@code null}; an empty map is allowed.
     * Use {@code header("Name", List.of("value"))} in the builder to add entries.
     */
    @Singular("header")
    Map<String, List<String>> headers;

    /**
     * Request body as UTF-8 text. {@code null} is treated as an empty body.
     * Binary payloads are not supported.
     */
    String body;
}