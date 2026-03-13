package cz.syntea.bedrock.wire.classic.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.net.URI;
import java.time.Duration;

/**
 * Single structured logger for all {@code bedrock-wire-client} output.
 * Logger name: {@code bedrock.wire.client} (fixed per spec §1.12).
 * {@code @Slf4j} must not be used elsewhere in this module.
 *
 * <p>MDC fields are set in {@code try/finally} per method — caller's context is not disturbed.
 *
 * <pre>
 * Event                                  Level   MDC fields
 * Pool created                           INFO    transportTarget, clientId
 * Pool closed                            INFO    transportTarget
 * Request timeout (responseTimeout)      WARN    clientId, url, duration
 * Read timeout (readTimeout)             WARN    clientId, url, duration
 * Pool exhausted                         WARN    transportTarget, pendingRequests
 * TLS warning (hostnameVerification=f)   WARN    configName
 * maxClients threshold exceeded          WARN    clientCount, maxClients
 * close() while in-flight               ERROR   inFlightCount
 * Registry closed on get()               ERROR   clientId
 * </pre>
 */
public final class WireLogger {

    // MDC key constants — stable names used by operators for log filtering/parsing
    static final String MDC_TRANSPORT_TARGET = "transportTarget";
    static final String MDC_CLIENT_ID = "clientId";
    static final String MDC_URL = "url";
    static final String MDC_DURATION = "duration";
    static final String MDC_CONFIG_NAME = "configName";
    static final String MDC_CLIENT_COUNT = "clientCount";
    static final String MDC_MAX_CLIENTS = "maxClients";
    static final String MDC_IN_FLIGHT_COUNT = "inFlightCount";
    static final String MDC_PENDING_REQUESTS = "pendingRequests";
    /**
     * The single logger for the entire library. Logger name is fixed per spec §1.12.
     */
    private static final Logger LOG = LoggerFactory.getLogger("bedrock.wire.client");

    private WireLogger() {
    }

    // ── INFO events ───────────────────────────────────────────────────────────

    /**
     * Pool created — INFO | MDC: transportTarget, clientId
     */
    public static void poolCreated(String transportTarget, String clientId) {
        try {
            MDC.put(MDC_TRANSPORT_TARGET, transportTarget);
            MDC.put(MDC_CLIENT_ID, clientId);
            LOG.info("Pool created: transportTarget={} clientId={}", transportTarget, clientId);
        } finally {
            MDC.remove(MDC_TRANSPORT_TARGET);
            MDC.remove(MDC_CLIENT_ID);
        }
    }

    /**
     * Pool closed — INFO | MDC: transportTarget
     */
    public static void poolClosed(String transportTarget) {
        try {
            MDC.put(MDC_TRANSPORT_TARGET, transportTarget);
            LOG.info("Pool closed: transportTarget={}", transportTarget);
        } finally {
            MDC.remove(MDC_TRANSPORT_TARGET);
        }
    }

    // ── WARN events ───────────────────────────────────────────────────────────

    /**
     * Request timeout (responseTimeout exceeded) — WARN | MDC: clientId, url, duration
     */
    public static void requestTimeout(String clientId, URI url, Duration duration) {
        try {
            MDC.put(MDC_CLIENT_ID, clientId);
            MDC.put(MDC_URL, url.toString());
            MDC.put(MDC_DURATION, duration.toString());
            LOG.warn("Response timeout: clientId={} url={} duration={}", clientId, url, duration);
        } finally {
            MDC.remove(MDC_CLIENT_ID);
            MDC.remove(MDC_URL);
            MDC.remove(MDC_DURATION);
        }
    }

    /**
     * Read timeout (readTimeout exceeded) — WARN | MDC: clientId, url, duration
     */
    public static void readTimeout(String clientId, URI url, Duration duration) {
        try {
            MDC.put(MDC_CLIENT_ID, clientId);
            MDC.put(MDC_URL, url.toString());
            MDC.put(MDC_DURATION, duration.toString());
            LOG.warn("Read timeout: clientId={} url={} duration={}", clientId, url, duration);
        } finally {
            MDC.remove(MDC_CLIENT_ID);
            MDC.remove(MDC_URL);
            MDC.remove(MDC_DURATION);
        }
    }

    /**
     * Pool exhausted — WARN | MDC: transportTarget, pendingRequests
     *
     * <p>The live queue depth at the moment of exhaustion is not available from the Netty
     * exception. The value logged is the configured ceiling from
     * {@link cz.syntea.bedrock.wire.classic.config.HttpClientConfig#getMaxPendingRequests()},
     * not the actual live pending count. The MDC key matches the spec (§1.12) name
     * {@code pendingRequests}; operators should be aware the value represents the configured
     * maximum, not a live gauge.
     */
    public static void poolExhausted(String transportTarget, int pendingRequests) {
        try {
            MDC.put(MDC_TRANSPORT_TARGET, transportTarget);
            MDC.put(MDC_PENDING_REQUESTS, String.valueOf(pendingRequests));
            LOG.warn("Pool exhausted: transportTarget={} pendingRequests={} (configured max)",
                    transportTarget, pendingRequests);
        } finally {
            MDC.remove(MDC_TRANSPORT_TARGET);
            MDC.remove(MDC_PENDING_REQUESTS);
        }
    }

    /**
     * TLS hostname verification disabled — WARN | MDC: configName
     */
    public static void tlsHostnameVerificationDisabled(String configName) {
        try {
            MDC.put(MDC_CONFIG_NAME, configName);
            LOG.warn("TLS hostname verification DISABLED (allowInsecureInProduction=true): configName={}",
                    configName);
        } finally {
            MDC.remove(MDC_CONFIG_NAME);
        }
    }

    /**
     * maxClients threshold exceeded — WARN | MDC: clientCount, maxClients
     */
    public static void maxClientsExceeded(int clientCount, int maxClients) {
        try {
            MDC.put(MDC_CLIENT_COUNT, String.valueOf(clientCount));
            MDC.put(MDC_MAX_CLIENTS, String.valueOf(maxClients));
            LOG.warn("maxClients threshold exceeded: clientCount={} maxClients={}",
                    clientCount, maxClients);
        } finally {
            MDC.remove(MDC_CLIENT_COUNT);
            MDC.remove(MDC_MAX_CLIENTS);
        }
    }

    /**
     * Response body size exceeded limit — WARN | no MDC required by spec; clientId + url added for context
     */
    public static void responseSizeExceeded(String clientId, java.net.URI url, int actualBytes, int limitBytes) {
        try {
            MDC.put(MDC_CLIENT_ID, clientId);
            MDC.put(MDC_URL, url.toString());
            LOG.warn("Response body size exceeded: actualBytes={} limitBytes={} clientId={} url={}",
                    actualBytes, limitBytes, clientId, url);
        } finally {
            MDC.remove(MDC_CLIENT_ID);
            MDC.remove(MDC_URL);
        }
    }

    // ── ERROR events ──────────────────────────────────────────────────────────

    /**
     * close() called while requests are in-flight — ERROR | MDC: inFlightCount
     */
    public static void closeWithInFlightRequests(int inFlightCount) {
        try {
            MDC.put(MDC_IN_FLIGHT_COUNT, String.valueOf(inFlightCount));
            LOG.error("close() called while requests are in-flight: inFlightCount={}",
                    inFlightCount);
        } finally {
            MDC.remove(MDC_IN_FLIGHT_COUNT);
        }
    }

    /**
     * Registry already closed on get() — ERROR | MDC: clientId
     */
    public static void registryClosedOnGet(String clientId) {
        try {
            MDC.put(MDC_CLIENT_ID, clientId);
            LOG.error("Registry already closed on get(): clientId={}", clientId);
        } finally {
            MDC.remove(MDC_CLIENT_ID);
        }
    }
}