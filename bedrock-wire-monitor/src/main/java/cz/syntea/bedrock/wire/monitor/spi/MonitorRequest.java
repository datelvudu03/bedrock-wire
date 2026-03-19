package cz.syntea.bedrock.wire.monitor.spi;

import cz.syntea.bedrock.wire.monitor.json.JsonFormat;
import cz.syntea.bedrock.wire.monitor.model.HttpMethod;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Description of a single HTTP request to be executed by a {@link MonitorTransport}.
 *
 * <p>Built by the monitor engine from {@code CheckConfig} and {@code ServiceConfig}.
 * The transport implementation uses {@link #getServiceName()} to look up the
 * appropriate HTTP client and connection pool.
 *
 * <h3>URL contract</h3>
 * {@link #getUrl()} is always a relative URI starting with {@code /}.
 * The transport implementation prepends the service base URL.
 */
@Value
@Builder(toBuilder = true)
public class MonitorRequest {

    /**
     * Identifies the target service. Used as a lookup key by the transport
     * to select the appropriate HTTP client.
     */
    String serviceName;

    /**
     * HTTP method for this request.
     */
    HttpMethod method;

    /**
     * Relative URI (path + optional query string). MUST start with {@code /}.
     * Example: {@code /api/health?format=xml}.
     */
    URI url;

    /**
     * HTTP headers for this request. Each header name maps to a list of values.
     * The map is the result of merging default, service, and check-level headers.
     */
    @Singular("header")
    Map<String, List<String>> headers;

    /**
     * Request body as UTF-8 text. {@code null} means no body.
     * For {@code GET} requests the transport SHOULD ignore this field.
     */
    String body;

    @Override
    public String toString() {
        return JsonFormat.toJson(this);
    }
}