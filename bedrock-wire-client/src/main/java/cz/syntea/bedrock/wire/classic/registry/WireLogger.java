package cz.syntea.bedrock.wire.classic.registry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Single structured logger for all {@code bedrock-wire-client} output.
 * Logger name: {@code bedrock.wire.client} (fixed per spec §1.12).
 * {@code @Slf4j} must not be used elsewhere in this module.
 *
 * <p>MDC fields are set in {@code try/finally} per method — caller's context is not disturbed.
 *
 * <pre>
 * Event                                  Level   MDC fields
 * Request sent                           DEBUG   clientId, url
 *   + headers, body                      TRACE   clientId, url
 * Response received                      DEBUG   clientId, url, duration
 *   + headers, body                      TRACE   clientId, url, duration
 * Transport error                        DEBUG   clientId, url, duration
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

    /**
     * Maximum number of characters logged for request/response bodies at TRACE level.
     * Bodies exceeding this limit are truncated with a {@code ...[truncated]} suffix.
     */
    private static final int BODY_TRUNCATE_LIMIT = 2048;

    private WireLogger() {
    }

    // ── DEBUG/TRACE events (request/response wire log) ──────────────────────

    /**
     * Outbound request sent — DEBUG: method + URL; TRACE: + headers + body.
     * MDC: clientId, url.
     *
     * @param clientId the client identifier
     * @param method   HTTP method
     * @param fullUri  fully resolved URI (base URL + relative path)
     * @param headers  merged headers (default → trace → request)
     * @param body     request body; may be {@code null}
     */
    public static void requestSent(String clientId, String method, URI fullUri,
                                   Map<String, List<String>> headers, String body) {
        if (!LOG.isDebugEnabled()) {
            return;
        }
        try {
            MDC.put(MDC_CLIENT_ID, clientId);
            MDC.put(MDC_URL, fullUri.toString());
            LOG.debug("→ {} {}", method, fullUri);
            if (LOG.isTraceEnabled()) {
                LOG.trace("  request headers={}", headers);
                if (body != null && !body.isEmpty()) {
                    LOG.trace("  request body={}", truncate(body));
                }
            }
        } finally {
            MDC.remove(MDC_CLIENT_ID);
            MDC.remove(MDC_URL);
        }
    }

    /**
     * Response received — DEBUG: status + URL + duration; TRACE: + headers + body.
     * MDC: clientId, url, duration.
     *
     * @param clientId the client identifier
     * @param fullUri  fully resolved URI
     * @param status   HTTP status code
     * @param duration time from request send to last byte received
     * @param headers  response headers
     * @param body     response body; may be {@code null} or empty
     */
    public static void responseReceived(String clientId, URI fullUri, int status,
                                        Duration duration,
                                        Map<String, List<String>> headers, String body) {
        if (!LOG.isDebugEnabled()) {
            return;
        }
        try {
            MDC.put(MDC_CLIENT_ID, clientId);
            MDC.put(MDC_URL, fullUri.toString());
            MDC.put(MDC_DURATION, duration.toString());
            LOG.debug("← {} {} ({}ms)", status, fullUri, duration.toMillis());
            if (LOG.isTraceEnabled()) {
                LOG.trace("  response headers={}", headers);
                if (body != null && !body.isEmpty()) {
                    LOG.trace("  response body={}", truncate(body));
                }
            }
        } finally {
            MDC.remove(MDC_CLIENT_ID);
            MDC.remove(MDC_URL);
            MDC.remove(MDC_DURATION);
        }
    }

    /**
     * Transport error (no response received) — DEBUG: error class + message.
     * MDC: clientId, url, duration.
     *
     * @param clientId the client identifier
     * @param fullUri  fully resolved URI
     * @param duration time from request send to error
     * @param error    the exception that occurred
     */
    public static void transportError(String clientId, URI fullUri,
                                      Duration duration, Throwable error) {
        if (!LOG.isDebugEnabled()) {
            return;
        }
        try {
            MDC.put(MDC_CLIENT_ID, clientId);
            MDC.put(MDC_URL, fullUri.toString());
            MDC.put(MDC_DURATION, duration.toString());
            LOG.debug("✗ {} {} ({}ms) {}: {}",
                    error.getClass().getSimpleName(), fullUri, duration.toMillis(),
                    error.getClass().getSimpleName(), error.getMessage());
        } finally {
            MDC.remove(MDC_CLIENT_ID);
            MDC.remove(MDC_URL);
            MDC.remove(MDC_DURATION);
        }
    }

    /**
     * Truncates a string to {@link #BODY_TRUNCATE_LIMIT} characters.
     */
    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= BODY_TRUNCATE_LIMIT) {
            return value;
        }
        return value.substring(0, BODY_TRUNCATE_LIMIT) + "...[truncated, total " + value.length() + " chars]";
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