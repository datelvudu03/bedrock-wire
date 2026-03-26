package cz.syntea.bedrock.wire.classic.spring;

import cz.syntea.bedrock.wire.classic.config.HttpClientConfig;
import cz.syntea.bedrock.wire.classic.observability.TraceHeaderPropagator;
import lombok.Data;

import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Property-bindable HTTP client configuration.
 *
 * <p>Maps 1:1 to {@link HttpClientConfig} fields. Bound from either Spring Environment
 * ({@code bedrock.wire.client.clients.<n>.*}) or parsed from {@code .param} file
 * ({@code wire.client.<n>.*}).
 *
 * @since 1.1
 */
@Data
public class ClientProperties {

    /**
     * Base URL of the service (scheme + host + port only).
     */
    private String baseUrl;

    /**
     * TCP + TLS + DNS connection timeout.
     */
    private Duration connectTimeout = Duration.ofSeconds(5);

    /**
     * Timeout for receiving the first response byte.
     */
    private Duration responseTimeout = Duration.ofSeconds(30);

    /**
     * Inter-byte idle timeout during response body read.
     */
    private Duration readTimeout = Duration.ofSeconds(10);

    /**
     * Maximum response body size in bytes.
     */
    private int maxResponseBodySize = 1024 * 1024;

    /**
     * Maximum TCP connections per transport target.
     */
    private int maxConnections = 50;

    /**
     * TLS profile name referencing a {@link TlsProfileProperties} entry.
     */
    private String tlsProfile;

    /**
     * Default HTTP headers. Each key maps to a single header value.
     */
    private Map<String, String> headers = new LinkedHashMap<>();

    /**
     * Converts this property object to an {@link HttpClientConfig} domain object
     * with the default (no-op) trace header propagator.
     *
     * @param clientId the client identifier (derived from the property map key)
     * @return the corresponding {@link HttpClientConfig}; never {@code null}
     * @throws IllegalArgumentException if {@code baseUrl} is {@code null} or invalid
     */
    public HttpClientConfig toHttpClientConfig(String clientId) {
        return toHttpClientConfig(clientId, null);
    }

    /**
     * Converts this property object to an {@link HttpClientConfig} domain object.
     *
     * @param clientId        the client identifier (derived from the property map key)
     * @param tracePropagator the trace header propagator to inject; {@code null} uses the
     *                        default no-op propagator from {@link HttpClientConfig}
     * @return the corresponding {@link HttpClientConfig}; never {@code null}
     * @throws IllegalArgumentException if {@code baseUrl} is {@code null} or invalid
     */
    public HttpClientConfig toHttpClientConfig(String clientId, TraceHeaderPropagator tracePropagator) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException(
                    "baseUrl is required for client '" + clientId + "'");
        }

        HttpClientConfig.HttpClientConfigBuilder builder = HttpClientConfig.builder()
                .clientId(clientId)
                .baseUrl(URI.create(baseUrl))
                .connectTimeout(connectTimeout)
                .responseTimeout(responseTimeout)
                .readTimeout(readTimeout)
                .maxResponseBodySize(maxResponseBodySize)
                .maxConnections(maxConnections);

        if (tlsProfile != null) {
            builder.tlsConfigName(tlsProfile);
        }

        if (tracePropagator != null) {
            builder.traceHeaderPropagator(tracePropagator);
        }

        headers.forEach((name, value) -> builder.defaultHeader(name, List.of(value)));

        return builder.build();
    }

}