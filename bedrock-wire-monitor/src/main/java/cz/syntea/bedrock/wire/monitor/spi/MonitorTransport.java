package cz.syntea.bedrock.wire.monitor.spi;

import cz.syntea.bedrock.wire.monitor.config.ServiceConfig;

import java.time.Duration;
import java.util.List;

/**
 * Service Provider Interface for HTTP transport used by the monitor engine.
 *
 * <p>The monitor layer is transport-agnostic — it delegates all HTTP communication
 * to an implementation of this interface. The default implementation
 * ({@code WireClientTransport}) uses {@code bedrock-wire-client}, but any HTTP
 * client library can be plugged in by implementing this SPI.
 *
 * <h3>Lifecycle contract</h3>
 * <pre>{@code
 *   init(services)  ──→  execute(request) × N  ──→  close(timeout)
 *        │                      │                         │
 *    fail-fast on           thread-safe,              idempotent,
 *    bad config             never throws              releases all
 *                           exceptions                resources
 * }</pre>
 *
 * <h3>Thread safety</h3>
 * {@link #execute(MonitorRequest)} is called concurrently from multiple virtual threads
 * without any synchronization by the monitor layer. Implementations MUST be fully
 * thread-safe.
 *
 * @see TransportStatus
 * @see MonitorRequest
 * @see MonitorResult
 */
public interface MonitorTransport {

    /**
     * Initializes the transport with service configurations.
     *
     * <p>Called once during monitor startup, before the first {@link #execute(MonitorRequest)}
     * call. The transport SHOULD create HTTP clients, connection pools, and register
     * TLS profiles during this call.
     *
     * <p>Invalid configuration MUST cause a fail-fast failure with a descriptive exception.
     *
     * @param services list of service configurations; never {@code null}, may be empty
     * @throws IllegalStateException    if the transport is already initialized
     * @throws IllegalArgumentException if any service configuration is invalid
     */
    void init(List<ServiceConfig> services);

    /**
     * Executes a single HTTP request synchronously.
     *
     * <p>Called on a virtual thread of a check run. MUST never throw an exception —
     * all error conditions are expressed via {@link TransportStatus} in the returned
     * {@link MonitorResult}.
     *
     * @param request description of the HTTP request; never {@code null}
     * @return transport result; never {@code null}
     */
    MonitorResult execute(MonitorRequest request);

    /**
     * Releases all transport resources (connection pools, HTTP clients).
     *
     * <p>Called once during monitor shutdown. MUST be idempotent — repeated calls
     * are safe. After {@code close()}, subsequent {@link #execute(MonitorRequest)}
     * calls MAY return {@link MonitorResult} with {@link TransportStatus#IO_ERROR}
     * or {@link TransportStatus#POOL_EXHAUSTED}.
     *
     * @param timeout maximum time allowed for graceful shutdown
     */
    void close(Duration timeout);
}
