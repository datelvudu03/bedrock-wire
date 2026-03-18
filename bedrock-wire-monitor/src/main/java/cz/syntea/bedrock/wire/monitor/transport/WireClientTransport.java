package cz.syntea.bedrock.wire.monitor.transport;

import cz.syntea.bedrock.wire.classic.config.HttpClientConfig;
import cz.syntea.bedrock.wire.classic.exception.PoolAcquisitionTimeoutException;
import cz.syntea.bedrock.wire.classic.exception.ReadTimeoutException;
import cz.syntea.bedrock.wire.classic.exception.RedirectNotSupportedException;
import cz.syntea.bedrock.wire.classic.exception.RequestTimeoutException;
import cz.syntea.bedrock.wire.classic.exception.ResponseSizeExceededException;
import cz.syntea.bedrock.wire.classic.exception.TransportException;
import cz.syntea.bedrock.wire.classic.model.HttpRequest;
import cz.syntea.bedrock.wire.classic.model.HttpResponse;
import cz.syntea.bedrock.wire.classic.model.TlsConfig;
import cz.syntea.bedrock.wire.classic.registry.HttpClient;
import cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry;
import cz.syntea.bedrock.wire.monitor.config.ServiceConfig;
import cz.syntea.bedrock.wire.monitor.config.TlsProfileConfig;
import cz.syntea.bedrock.wire.monitor.spi.MonitorRequest;
import cz.syntea.bedrock.wire.monitor.spi.MonitorResult;
import cz.syntea.bedrock.wire.monitor.spi.MonitorTransport;
import cz.syntea.bedrock.wire.monitor.spi.TransportStatus;
import lombok.extern.slf4j.Slf4j;

import java.net.ConnectException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link MonitorTransport} implementation built on {@code bedrock-wire-client}.
 *
 * <p>Uses the shared {@link HttpClientRegistry} bean from the Spring context.
 * Each monitored service gets its own {@link HttpClient} instance (cached by
 * {@code serviceName}) backed by the registry's shared connection pools.
 *
 * <h3>Lifecycle</h3>
 * <ul>
 *   <li>{@link #init(List)} — registers TLS profiles, creates {@code HttpClient}
 *       per service via the shared registry.</li>
 *   <li>{@link #execute(MonitorRequest)} — maps the monitor request to a wire-client
 *       {@code HttpRequest}, calls {@code block()}, and maps the result/exceptions
 *       to {@link MonitorResult}. Never throws.</li>
 *   <li>{@link #close(Duration)} — clears the internal client cache. Does NOT close
 *       the shared {@code HttpClientRegistry} (its lifecycle is managed by the
 *       wire-client auto-configuration).</li>
 * </ul>
 *
 * <h3>Exception mapping</h3>
 * All {@code BedrockWireException} subtypes are caught and mapped to the appropriate
 * {@link TransportStatus} per the spec's Appendix B.7.
 */
@Slf4j
public class WireClientTransport implements MonitorTransport {

    private static final java.util.Set<String> KNOWN_TRANSPORT_PROPERTIES = java.util.Set.of(
            "responseTimeout", "connectionTimeout", "readTimeout",
            "maxResponseBodySize", "maxConnections", "tlsProfile"
    );
    private final HttpClientRegistry registry;
    private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;

    /**
     * Creates a new transport using the shared registry.
     *
     * @param registry the shared {@link HttpClientRegistry}; never {@code null}
     */
    public WireClientTransport(HttpClientRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("HttpClientRegistry must not be null");
        }
        this.registry = registry;
    }

    @Override
    public void init(List<ServiceConfig> services) {
        if (initialized) {
            throw new IllegalStateException("WireClientTransport is already initialized");
        }

        for (ServiceConfig service : services) {
            HttpClientConfig clientConfig = mapToHttpClientConfig(service);
            HttpClient client = registry.get(clientConfig);
            clients.put(service.getServiceName(), client);

            log.info("Registered HttpClient for service '{}' → {}",
                    service.getServiceName(), service.getUrl());
        }

        initialized = true;
        log.info("WireClientTransport initialized with {} service clients", clients.size());
    }

    /**
     * Registers TLS profiles from the monitor configuration into the shared registry.
     *
     * <p>This method MUST be called before {@link #init(List)} if any service
     * references a TLS profile. Called by the auto-configuration or manually
     * during setup.
     *
     * @param tlsProfiles the TLS profiles to register; never {@code null}
     */
    public void registerTlsProfiles(List<TlsProfileConfig> tlsProfiles) {
        for (TlsProfileConfig profile : tlsProfiles) {
            TlsConfig tlsConfig = mapToTlsConfig(profile);
            registry.registerTlsConfig(tlsConfig);
            log.info("Registered TLS profile '{}'", profile.getProfileName());
        }
    }

    @Override
    public MonitorResult execute(MonitorRequest request) {
        HttpClient client = clients.get(request.getServiceName());
        if (client == null) {
            log.error("No HttpClient registered for service '{}'", request.getServiceName());
            return MonitorResult.builder()
                    .transportStatus(TransportStatus.IO_ERROR)
                    .httpStatus(0)
                    .errorMessage("No HttpClient for service: " + request.getServiceName())
                    .build();
        }

        HttpRequest wireRequest = mapToWireRequest(request);
        long startNanos = System.nanoTime();

        try {
            HttpResponse response = client.execute(wireRequest).block();
            Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);

            if (response == null) {
                return MonitorResult.builder()
                        .transportStatus(TransportStatus.IO_ERROR)
                        .httpStatus(0)
                        .errorMessage("Null response from HttpClient")
                        .transportDuration(duration)
                        .build();
            }

            return MonitorResult.builder()
                    .transportStatus(TransportStatus.RESPONSE_RECEIVED)
                    .httpStatus(response.getStatusCode())
                    .responseBody(response.getResponseBody() != null ? response.getResponseBody() : "")
                    .headers(response.getHeaders() != null ? response.getHeaders() : Map.of())
                    .transportDuration(response.getDuration() != null ? response.getDuration() : duration)
                    .build();

        } catch (RequestTimeoutException e) {
            return buildErrorResult(TransportStatus.TIMEOUT, "ResponseTimeout",
                    System.nanoTime() - startNanos);

        } catch (TransportException e) {
            TransportStatus status = classifyTransportException(e);
            return buildErrorResult(status, classifyTransportMessage(e),
                    System.nanoTime() - startNanos);

        } catch (ReadTimeoutException e) {
            return buildErrorResult(TransportStatus.IO_ERROR, "ReadTimeout",
                    System.nanoTime() - startNanos);

        } catch (ResponseSizeExceededException e) {
            return buildErrorResult(TransportStatus.IO_ERROR, "ResponseSizeExceeded",
                    System.nanoTime() - startNanos);

        } catch (PoolAcquisitionTimeoutException e) {
            return buildErrorResult(TransportStatus.POOL_EXHAUSTED, "PoolExhausted",
                    System.nanoTime() - startNanos);

        } catch (RedirectNotSupportedException e) {
            // Spec B.7: 3xx status propagated as RESPONSE_RECEIVED
            Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);
            return MonitorResult.builder()
                    .transportStatus(TransportStatus.RESPONSE_RECEIVED)
                    .httpStatus(e.getStatusCode())
                    .responseBody("")
                    .headers(Map.of())
                    .transportDuration(duration)
                    .build();

        } catch (Exception e) {
            log.error("Unexpected exception from HttpClient for service '{}': {}",
                    request.getServiceName(), e.getMessage(), e);
            return buildErrorResult(TransportStatus.IO_ERROR,
                    "Unexpected: " + e.getMessage(), System.nanoTime() - startNanos);
        }
    }

    // ── Mapping: ServiceConfig → HttpClientConfig ───────────────────────────

    @Override
    public void close(Duration timeout) {
        // Do NOT close the shared registry — its lifecycle is managed by
        // BedrockWireClientAutoConfiguration.closeRegistry() via @PreDestroy.
        clients.clear();
        initialized = false;
        log.info("WireClientTransport closed (clients cleared; shared registry NOT closed)");
    }

    private HttpClientConfig mapToHttpClientConfig(ServiceConfig service) {
        Map<String, String> props = service.getTransportProperties();

        HttpClientConfig.HttpClientConfigBuilder builder = HttpClientConfig.builder()
                .clientId(service.getServiceName())
                .baseUrl(service.getUrl());

        if (props.containsKey("responseTimeout")) {
            builder.responseTimeout(parseDuration(props.get("responseTimeout"), "responseTimeout"));
        }
        if (props.containsKey("connectionTimeout")) {
            builder.connectTimeout(parseDuration(props.get("connectionTimeout"), "connectionTimeout"));
        }
        if (props.containsKey("readTimeout")) {
            builder.readTimeout(parseDuration(props.get("readTimeout"), "readTimeout"));
        }
        if (props.containsKey("maxResponseBodySize")) {
            builder.maxResponseBodySize(Integer.parseInt(props.get("maxResponseBodySize")));
        }
        if (props.containsKey("maxConnections")) {
            builder.maxConnections(Integer.parseInt(props.get("maxConnections")));
        }
        if (props.containsKey("tlsProfile")) {
            builder.tlsConfigName(props.get("tlsProfile"));
        }

        // Warn on unknown keys
        for (String key : props.keySet()) {
            if (!isKnownTransportProperty(key)) {
                log.warn("Service '{}': unknown transport property '{}' (ignored)",
                        service.getServiceName(), key);
            }
        }

        return builder.build();
    }

    private boolean isKnownTransportProperty(String key) {
        return KNOWN_TRANSPORT_PROPERTIES.contains(key);
    }

    // ── Mapping: TlsProfileConfig → TlsConfig ──────────────────────────────

    private TlsConfig mapToTlsConfig(TlsProfileConfig profile) {
        TlsConfig.TlsConfigBuilder builder = TlsConfig.builder()
                .configName(profile.getProfileName())
                .hostnameVerification(profile.isHostnameVerification())
                .allowInsecureInProduction(profile.isAllowInsecureInProduction());

        if (profile.getClientCert() != null) {
            builder.clientCert(Path.of(profile.getClientCert()));
            builder.clientCertPassword(profile.getClientCertPassword());
            builder.clientCertType(profile.getClientCertType());
            if (profile.getClientCertAlias() != null) {
                builder.clientCertAlias(profile.getClientCertAlias());
            }
        }
        if (profile.getTrustStore() != null) {
            builder.trustStore(Path.of(profile.getTrustStore()));
            builder.trustStorePassword(profile.getTrustStorePassword());
            builder.trustStoreType(profile.getTrustStoreType());
        }

        return builder.build();
    }

    // ── Mapping: MonitorRequest → HttpRequest ───────────────────────────────

    private HttpRequest mapToWireRequest(MonitorRequest request) {
        cz.syntea.bedrock.wire.classic.model.HttpMethod wireMethod =
                cz.syntea.bedrock.wire.classic.model.HttpMethod.valueOf(request.getMethod().name());

        return HttpRequest.builder()
                .method(wireMethod)
                .url(request.getUrl())
                .headers(request.getHeaders())
                .body(request.getBody())
                .build();
    }

    // ── Exception classification ────────────────────────────────────────────

    /**
     * Classifies a {@link TransportException} into a {@link TransportStatus}
     * based on the wrapped cause.
     */
    private TransportStatus classifyTransportException(TransportException e) {
        Throwable cause = e.getCause();
        if (cause instanceof ConnectException) {
            return TransportStatus.CONNECT_ERROR;
        }
        if (cause instanceof java.net.ConnectException
                || (cause != null && cause.getClass().getSimpleName().contains("ConnectTimeout"))) {
            return TransportStatus.CONNECT_ERROR;
        }
        if (cause instanceof java.io.IOException) {
            return TransportStatus.IO_ERROR;
        }
        // Default: treat as CONNECT_ERROR for connection-level issues
        String msg = e.getMessage();
        if (msg != null && (msg.contains("Connection refused") || msg.contains("ConnectTimeout")
                || msg.contains("DNS") || msg.contains("TLS"))) {
            return TransportStatus.CONNECT_ERROR;
        }
        return TransportStatus.IO_ERROR;
    }

    private String classifyTransportMessage(TransportException e) {
        Throwable cause = e.getCause();
        if (cause instanceof ConnectException) {
            return "ConnectionRefused";
        }
        if (cause != null && cause.getClass().getSimpleName().contains("ConnectTimeout")) {
            return "ConnectTimeout";
        }
        if (cause != null && cause.getClass().getSimpleName().contains("PrematureClose")) {
            return "PrematureClose";
        }
        String msg = e.getMessage();
        if (msg != null && msg.contains("Connection refused")) {
            return "ConnectionRefused";
        }
        if (msg != null && msg.contains("ConnectTimeout")) {
            return "ConnectTimeout";
        }
        return "IOError";
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private MonitorResult buildErrorResult(TransportStatus status, String errorMessage, long elapsedNanos) {
        return MonitorResult.builder()
                .transportStatus(status)
                .httpStatus(0)
                .errorMessage(errorMessage)
                .transportDuration(Duration.ofNanos(elapsedNanos))
                .build();
    }

    private Duration parseDuration(String value, String paramName) {
        return cz.syntea.bedrock.wire.monitor.config.PropertiesFileConfigProvider
                .parseDuration(value, paramName);
    }
}
