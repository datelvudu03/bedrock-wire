package cz.syntea.bedrock.wire.classic.config;

import cz.syntea.bedrock.wire.classic.model.TlsConfig;
import cz.syntea.bedrock.wire.classic.model.TransportTarget;
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
 * Immutable per-client configuration, identified by {@link #clientId} within an
 * {@link cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry}.
 *
 * <p>{@code clientId} must be unique, stable, and human-readable (not a UUID).
 * {@code baseUrl} is scheme + host + port only — no path, no trailing slash.
 * Clients with the same {@link TransportTarget} share one connection pool.
 * Immutable; changing config requires a new {@code clientId}.
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
     * <p>The response body is streamed chunk-by-chunk; the byte count is checked
     * as data arrives. If the limit is exceeded, the download is cancelled
     * immediately — no oversized body is allocated on the heap.
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