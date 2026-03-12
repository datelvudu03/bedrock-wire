package cz.syntea.bedrock.wire.classic.config;

import cz.syntea.bedrock.wire.classic.model.TlsConfig;
import cz.syntea.bedrock.wire.classic.observability.NoOpTraceHeaderPropagator;
import cz.syntea.bedrock.wire.classic.observability.TraceHeaderPropagator;
import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Immutable configuration for a single {@link cz.syntea.bedrock.wire.classic.client.HttpClient}
 * instance. Identified by {@link #clientId} within an
 * {@link cz.syntea.bedrock.wire.classic.client.HttpClientRegistry}.
 *
 * <h3>clientId</h3>
 * <ul>
 *   <li>MUST be unique within a single registry.</li>
 *   <li>SHOULD be a stable, human-readable identifier — not a generated UUID.</li>
 *   <li>SHOULD match the target service name to avoid misconfiguration.</li>
 * </ul>
 *
 * <h3>baseUrl</h3>
 * MUST contain only {@code scheme + host + port} — no path, no trailing slash.
 * The registry normalizes the URL to canonical form when computing the
 * {@link cz.syntea.bedrock.wire.classic.model.TransportTarget}.
 *
 * <h3>Connection pool sharing</h3>
 * Multiple {@code HttpClientConfig} instances with the same
 * {@code TransportTarget} (same host + port + {@code tlsConfigName}) share one
 * underlying TCP connection pool; they may differ in timeouts.
 *
 * <h3>Configuration changes</h3>
 * {@code HttpClientConfig} MUST be immutable. Changing configuration requires
 * a new {@code clientId} and a new registry instance.
 *
 * <h3>Timeout semantics</h3>
 * <ul>
 *   <li>{@code connectTimeout} — DNS resolution + TCP connect + TLS handshake</li>
 *   <li>{@code poolAcquisitionTimeout} — max wait for a free pool slot</li>
 *   <li>{@code responseTimeout} — time from sending the request to receiving the <em>first byte</em></li>
 *   <li>{@code readTimeout} — max time between consecutive bytes during body transfer</li>
 *   <li>{@code keepAliveTimeout} — max idle time before a pooled connection is evicted</li>
 * </ul>
 * {@code poolAcquisitionTimeout} and {@code responseTimeout} are independent and may
 * both fire on the same request.
 */
@Value
@Builder(toBuilder = true, buildMethodName = "buildInternal")
public class HttpClientConfig {

    /**
     * Unique identifier for this transport client. Required.
     */
    String clientId;

    /**
     * Target base URL: {@code scheme + host + port} only. No path, no trailing slash.
     * Example: {@code https://payments.example.com} or {@code https://payments.example.com:8443}.
     * Required.
     */
    URI baseUrl;

    /**
     * TCP connect + TLS handshake + DNS resolution. Default: 5 s.
     */
    @Builder.Default
    Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * Max wait for a free slot in a full connection pool.
     * On expiry: {@link cz.syntea.bedrock.wire.classic.exception.PoolAcquisitionTimeoutException}.
     * Default: 5 s.
     */
    @Builder.Default
    Duration poolAcquisitionTimeout = Duration.ofSeconds(5);

    /**
     * Time from sending the HTTP request to receiving the first byte of the response.
     * Does NOT include DNS, TCP connect, or TLS handshake.
     * On expiry: {@link cz.syntea.bedrock.wire.classic.exception.RequestTimeoutException}.
     * Default: 30 s.
     */
    @Builder.Default
    Duration responseTimeout = Duration.ofSeconds(30);

    /**
     * Max time allowed between consecutive bytes during response body transfer.
     * Applies after the first byte is received.
     * On expiry: {@link cz.syntea.bedrock.wire.classic.exception.ReadTimeoutException}.
     * Default: 10 s.
     */
    @Builder.Default
    Duration readTimeout = Duration.ofSeconds(10);

    /**
     * Max idle time for a pooled connection before eviction. Default: 60 s.
     */
    @Builder.Default
    Duration keepAliveTimeout = Duration.ofSeconds(60);

    /**
     * Max number of concurrent TCP connections per
     * {@link cz.syntea.bedrock.wire.classic.model.TransportTarget}.
     * Default: 50.
     */
    @Builder.Default
    int maxConnections = 50;

    /**
     * Max number of requests waiting for a pool slot.
     * Requests exceeding this limit immediately emit
     * {@link cz.syntea.bedrock.wire.classic.exception.PoolAcquisitionTimeoutException}.
     * Default: 100.
     */
    @Builder.Default
    int maxPendingRequests = 100;

    /**
     * Maximum allowed response body size in bytes.
     * Exceeding this limit causes
     * {@link cz.syntea.bedrock.wire.classic.exception.ResponseSizeExceededException}.
     * Default: 1 MB.
     *
     * <p><strong>Important:</strong> the check runs <em>after</em> the full body has been
     * buffered into a {@code String} in memory. Setting this value does not prevent heap
     * allocation for bodies up to this size — it only prevents the oversized body from being
     * returned to the caller. Keep this value well below the JVM heap limit.
     */
    @Builder.Default
    int maxResponseBodySize = 1024 * 1024;

    /**
     * Headers added to every request.
     * Per-request headers take priority over these defaults (replace, not append).
     * Use {@code .defaultHeader("Name", List.of("value"))} in the builder.
     */
    @Singular("defaultHeader")
    Map<String, List<String>> defaultHeaders;

    /**
     * Name of a {@link TlsConfig} registered in the registry.
     * {@code null} = JVM-default TLS (no custom certificates).
     */
    String tlsConfigName;

    /**
     * Injects outbound trace headers (W3C {@code traceparent}, B3, etc.) per request.
     * Do NOT inject trace headers via {@code defaultHeaders} — those are static and
     * would attach the same trace context to every request.
     * Default: no-op.
     */
    @Builder.Default
    TraceHeaderPropagator traceHeaderPropagator = new NoOpTraceHeaderPropagator();

    public static class HttpClientConfigBuilder {

        private static void validateBaseUrl(URI baseUrl) {
            if (baseUrl == null) {
                throw new IllegalArgumentException("HttpClientConfig.baseUrl must not be null");
            }
            if (baseUrl.getScheme() == null) {
                throw new IllegalArgumentException(
                        "HttpClientConfig.baseUrl must include a scheme (http/https): " + baseUrl);
            }
            if (baseUrl.getHost() == null) {
                throw new IllegalArgumentException(
                        "HttpClientConfig.baseUrl must include a host: " + baseUrl);
            }
            String path = baseUrl.getPath();
            if (path != null && !path.isEmpty() && !path.equals("/")) {
                throw new IllegalArgumentException(
                        "HttpClientConfig.baseUrl must not contain a path (scheme + host + port only): "
                                + baseUrl);
            }
            if (baseUrl.getQuery() != null) {
                throw new IllegalArgumentException(
                        "HttpClientConfig.baseUrl must not contain a query string: " + baseUrl);
            }
            if (baseUrl.getFragment() != null) {
                throw new IllegalArgumentException(
                        "HttpClientConfig.baseUrl must not contain a fragment: " + baseUrl);
            }
            if (baseUrl.toString().endsWith("/")) {
                throw new IllegalArgumentException(
                        "HttpClientConfig.baseUrl must not have a trailing slash: " + baseUrl);
            }
        }

        public HttpClientConfig build() {
            HttpClientConfig config = buildInternal();
            validateBaseUrl(config.baseUrl);
            return config;
        }
    }
}