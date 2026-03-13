package cz.syntea.bedrock.wire.classic.registry;

import cz.syntea.bedrock.wire.classic.config.HttpClientConfig;
import cz.syntea.bedrock.wire.classic.config.HttpClientRegistryConfig;
import cz.syntea.bedrock.wire.classic.exception.InsecureConfigurationException;
import cz.syntea.bedrock.wire.classic.exception.RegistryCapacityException;
import cz.syntea.bedrock.wire.classic.model.TlsConfig;
import cz.syntea.bedrock.wire.classic.model.TransportTarget;
import cz.syntea.bedrock.wire.classic.observability.NoOpWireMetricsCollector;
import cz.syntea.bedrock.wire.classic.observability.WireMetricsCollector;
import io.netty.channel.ChannelOption;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.tcp.SslProvider;

import javax.net.ssl.SSLParameters;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Production {@link HttpClientRegistry} implementation.
 *
 * <p>One {@link ConnectionProvider} per {@link TransportTarget}; clients sharing the
 * same target share that pool. TLS is resolved lazily at first pool creation (fail-fast).
 *
 * <p>Thread safety via {@link ConcurrentHashMap#compute} — capacity and config-conflict
 * checks are atomic with client creation.
 *
 * <p>Logging via {@link WireLogger} ({@code bedrock.wire.client}). Pool metrics via
 * {@link PoolMetricsReporter}. Use {@code @PreDestroy} or the auto-configuration for shutdown.
 */
public class HttpClientRegistryImpl implements HttpClientRegistry {

    private static final String BEDROCK_READ_TIMEOUT = "bedrock.readTimeout";
    private final HttpClientRegistryConfig registryConfig;
    private final WireMetricsCollector metricsCollector;
    private final PoolMetricsReporter poolMetricsReporter;
    /**
     * Registered TLS profiles, keyed by {@code TlsConfig.configName}.
     */
    private final ConcurrentHashMap<String, TlsConfig> tlsConfigs = new ConcurrentHashMap<>();

    /**
     * One connection pool per {@link TransportTarget}.
     */
    private final ConcurrentHashMap<TransportTarget, ConnectionProvider> connectionProviders =
            new ConcurrentHashMap<>();

    /**
     * Original configs keyed by {@code clientId}, used to detect
     * conflicting re-registrations with the same {@code clientId}.
     */
    private final ConcurrentHashMap<String, HttpClientConfig> registeredConfigs =
            new ConcurrentHashMap<>();

    /**
     * Cached {@link HttpClient} instances keyed by {@code clientId}.
     */
    private final ConcurrentHashMap<String, HttpClient> clients = new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger inFlightCount = new AtomicInteger(0);


    public HttpClientRegistryImpl() {
        this(HttpClientRegistryConfig.defaults(), new NoOpWireMetricsCollector());
    }

    public HttpClientRegistryImpl(HttpClientRegistryConfig registryConfig) {
        this(registryConfig, new NoOpWireMetricsCollector());
    }

    public HttpClientRegistryImpl(HttpClientRegistryConfig registryConfig,
                                  WireMetricsCollector metricsCollector) {
        this.registryConfig = Objects.requireNonNull(registryConfig, "registryConfig");
        this.metricsCollector = Objects.requireNonNull(metricsCollector, "metricsCollector");
        this.poolMetricsReporter = new PoolMetricsReporter(metricsCollector);
        this.poolMetricsReporter.start();
    }

    // ── HttpClientRegistry ────────────────────────────────────────────────────

    @Override
    public void registerTlsConfig(TlsConfig config) {
        Objects.requireNonNull(config, "TlsConfig must not be null");
        assertNotClosed("registerTlsConfig");

        // Security guard: hostnameVerification=false requires explicit opt-in
        if (!config.isHostnameVerification() && !config.isAllowInsecureInProduction()) {
            throw new InsecureConfigurationException(
                    "hostnameVerification=false requires allowInsecureInProduction=true "
                            + "for configName=" + config.getConfigName());
        }

        // Spec §1.12: TLS warning — WARN with MDC field configName
        if (!config.isHostnameVerification()) {
            WireLogger.tlsHostnameVerificationDisabled(config.getConfigName());
        }

        TlsConfig existing = tlsConfigs.putIfAbsent(config.getConfigName(), config);
        if (existing != null) {
            throw new IllegalStateException(
                    "TlsConfig already registered: configName=" + config.getConfigName());
        }
    }

    @Override
    public HttpClient get(HttpClientConfig config) {
        Objects.requireNonNull(config, "HttpClientConfig must not be null");

        if (closed.get()) {
            // Spec §1.12: Registry already closed on get() — ERROR with MDC field clientId
            WireLogger.registryClosedOnGet(config.getClientId());
            throw new IllegalStateException("Registry is closed");
        }

        // Validate TLS reference up-front (does not need atomicity with client creation)
        if (config.getTlsConfigName() != null
                && !tlsConfigs.containsKey(config.getTlsConfigName())) {
            throw new IllegalArgumentException(
                    "TlsConfig not registered: tlsConfigName=" + config.getTlsConfigName()
                            + " (clientId=" + config.getClientId() + ")");
        }

        // All validation that must be atomic with client creation is inside compute().
        // compute() holds the segment lock for this key, so capacity checks and
        // config-conflict detection cannot race with concurrent get() calls.
        //
        // RuntimeExceptions thrown from the remapping function propagate to the caller
        // and leave the map unchanged (no partial entry is stored).
        return clients.compute(config.getClientId(), (id, existing) -> {

            // Fast path: client already cached — validate config consistency
            if (existing != null) {
                HttpClientConfig prev = registeredConfigs.get(id);
                if (prev != null && !prev.equals(config)) {
                    throw new IllegalArgumentException(
                            "clientId '" + id
                                    + "' is already registered with a different HttpClientConfig");
                }
                return existing;
            }

            // New client: capacity check is now atomic with insertion
            if (clients.size() >= registryConfig.getMaxClients()) {
                // Spec §1.12: maxClients threshold exceeded — WARN
                WireLogger.maxClientsExceeded(clients.size(), registryConfig.getMaxClients());
                throw new RegistryCapacityException(registryConfig.getMaxClients());
            }

            TransportTarget target = TransportTarget.from(config);

            ConnectionProvider pool = getOrCreateConnectionProvider(target, config);
            reactor.netty.http.client.HttpClient nettyClient = buildNettyClient(config, target, pool);

            WebClient webClient = WebClient.builder()
                    .clientConnector(new ReactorClientHttpConnector(nettyClient))
                    .build();

            // Store config only after successful client creation — if pool or TLS
            // initialization fails above, no orphaned entry is left in registeredConfigs.
            registeredConfigs.put(id, config);

            // Pass TransportTarget to HttpClientImpl so it can include it in
            // pool-exhausted log events (spec §1.12: transportTarget + pendingRequests).
            return new HttpClientImpl(config, target, webClient, metricsCollector,
                    closed::get, inFlightCount);
        });
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return; // idempotent
        }

        poolMetricsReporter.stop();

        int inFlight = inFlightCount.get();
        if (inFlight > 0) {
            // Spec §1.12: close() while in-flight — ERROR with MDC field inFlightCount
            WireLogger.closeWithInFlightRequests(inFlight);
        }

        connectionProviders.forEach((target, provider) -> {
            // Spec §1.12: Pool closed — INFO with MDC field transportTarget
            WireLogger.poolClosed(target.toPoolName());
            provider.dispose();
        });

        connectionProviders.clear();
        clients.clear();
    }

    // ── Internal pool / client construction ───────────────────────────────────

    private ConnectionProvider getOrCreateConnectionProvider(
            TransportTarget target, HttpClientConfig config) {

        return connectionProviders.computeIfAbsent(target, t -> {
            // Spec §1.12: Pool created — INFO with MDC fields transportTarget, clientId
            WireLogger.poolCreated(target.toPoolName(), config.getClientId());

            // Get a MeterRegistrar that will capture the ConnectionPoolMetrics handle
            // when Reactor Netty initialises the pool. No Micrometer dependency is required —
            // the registrar overload does not check for Micrometer on the classpath.
            ConnectionProvider.MeterRegistrar registrar =
                    poolMetricsReporter.createRegistrarFor(target);

            return ConnectionProvider.builder(target.toPoolName())
                    .maxConnections(config.getMaxConnections())
                    .pendingAcquireMaxCount(config.getMaxPendingRequests())
                    .pendingAcquireTimeout(config.getPoolAcquisitionTimeout())
                    .maxIdleTime(config.getKeepAliveTimeout())
                    // metrics(true, supplier) captures pool state via MeterRegistrar callback;
                    // does NOT require Micrometer on the classpath (unlike metrics(true) alone).
                    .metrics(true, () -> registrar)
                    .build();
        });
    }

    private reactor.netty.http.client.HttpClient buildNettyClient(
            HttpClientConfig config,
            TransportTarget target,
            ConnectionProvider pool) {

        final long connectTimeoutMs = config.getConnectTimeout().toMillis();
        final long readTimeoutMs = config.getReadTimeout().toMillis();

        reactor.netty.http.client.HttpClient nettyClient =
                reactor.netty.http.client.HttpClient.create(pool)
                        // connectTimeout: TCP connect + TLS handshake + DNS resolution
                        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) connectTimeoutMs)
                        // responseTimeout: time from sending a request to the first byte of response
                        .responseTimeout(config.getResponseTimeout())
                        // readTimeout: per-byte idle time during body transfer.
                        // ReadTimeoutHandler is injected per-request and removed after body
                        // is fully consumed, so it fires on byte-level idle — not a total deadline.
                        .doOnRequest((req, conn) ->
                                conn.addHandlerLast(
                                        BEDROCK_READ_TIMEOUT,
                                        new ReadTimeoutHandler(readTimeoutMs, TimeUnit.MILLISECONDS)))
                        .doAfterResponseSuccess((res, conn) ->
                                conn.removeHandler(BEDROCK_READ_TIMEOUT))
                        // Safety net: doAfterResponseSuccess does NOT fire on error paths
                        // (read timeout, body-decoding failure, etc.). If the connection is
                        // disconnected while the handler is still in the pipeline, remove it
                        // here to prevent handler accumulation. In practice, Reactor Netty
                        // disposes errored connections rather than returning them to the pool,
                        // but this guards against edge cases.
                        .doOnDisconnected(conn -> {
                            try {
                                if (conn.channel().pipeline().get(BEDROCK_READ_TIMEOUT) != null) {
                                    conn.removeHandler(BEDROCK_READ_TIMEOUT);
                                }
                            } catch (Exception ignored) {
                                // Connection or pipeline may already be destroyed
                            }
                        });

        // ── TLS ────────────────────────────────────────────────────────────
        TlsConfig tlsConfig = config.getTlsConfigName() != null
                ? tlsConfigs.get(config.getTlsConfigName())
                : null;

        if (tlsConfig != null) {
            SslContext sslContext = TlsSslContextFactory.build(tlsConfig);
            boolean hostnameVerification = tlsConfig.isHostnameVerification();

            // handlerConfigurator is on SslProvider.Builder, NOT on SslContextSpec.
            SslProvider.Builder sslProviderBuilder = SslProvider.builder()
                    .sslContext(sslContext);

            if (!hostnameVerification) {
                sslProviderBuilder.handlerConfigurator(sslHandler -> {
                    SSLParameters params = sslHandler.engine().getSSLParameters();
                    params.setEndpointIdentificationAlgorithm("");
                    sslHandler.engine().setSSLParameters(params);
                });
            }

            nettyClient = nettyClient.secure(sslProviderBuilder.build());

        } else if ("https".equalsIgnoreCase(target.scheme())) {
            // No custom TlsConfig → use JVM-default TLS
            nettyClient = nettyClient.secure();
        }

        return nettyClient;
    }

    private void assertNotClosed(String operation) {
        if (closed.get()) {
            throw new IllegalStateException("Registry is closed; cannot call " + operation);
        }
    }
}

/*
•.,¸,.•*`•.,¸¸,.•*¯ ╭━━━━╮
•.,¸,.•*¯`•.,¸,.•*¯.|:::::::::: /\___/\
•.,¸,.•*¯`•.,¸,.•* <|:::::::::(｡ ●ω●｡) ᵐᵉᵒʷ ᵐᵉᵒʷ ᵐᵉᵒʷ
•.,¸,.•¯•.,¸,.•╰ * >し------し---Ｊ
*/


