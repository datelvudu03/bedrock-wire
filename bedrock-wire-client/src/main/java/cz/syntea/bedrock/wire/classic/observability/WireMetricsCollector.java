package cz.syntea.bedrock.wire.classic.observability;

import cz.syntea.bedrock.wire.classic.model.HttpMethod;
import cz.syntea.bedrock.wire.classic.model.TransportTarget;
import cz.syntea.bedrock.wire.classic.model.enums.RequestOutcome;
import cz.syntea.bedrock.wire.classic.registry.PoolMetricsReporter;

import java.time.Duration;

/**
 * SPI for recording request and connection-pool metrics.
 *
 * <p>Inject an implementation into
 * {@link cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry} at construction time.
 * If not provided, no metrics are emitted (see {@link NoOpWireMetricsCollector}).
 *
 * <p>A Micrometer adapter may be provided as a separate module.
 *
 * <h3>Recommended Micrometer metric names</h3>
 * <table>
 *   <tr><th>Metric</th><th>Type</th><th>Tags</th></tr>
 *   <tr><td>{@code bedrock.wire.request.duration}</td><td>histogram</td>
 *       <td>clientId, method, statusCode, outcome</td></tr>
 *   <tr><td>{@code bedrock.wire.request.count}</td><td>counter</td>
 *       <td>clientId, method, statusCode, outcome</td></tr>
 *   <tr><td>{@code bedrock.wire.pool.active_connections}</td><td>gauge</td>
 *       <td>transportTarget</td></tr>
 *   <tr><td>{@code bedrock.wire.pool.pending_requests}</td><td>gauge</td>
 *       <td>transportTarget</td></tr>
 * </table>
 *
 * <h3>Thread safety</h3>
 * Implementations MUST be thread-safe; both methods may be called from multiple
 * threads and reactive schedulers concurrently.
 *
 * <h3>Error handling</h3>
 * Implementations MUST NOT throw. The library will silently swallow any exception thrown *  to prevent metrics failures from affecting the request flow.
 */
public interface WireMetricsCollector {

    /**
     * Called once after each completed (or failed) HTTP request.
     *
     * @param clientId   the {@code HttpClientConfig.clientId} of the client that made the request
     * @param method     HTTP method used
     * @param statusCode HTTP status code returned by the server; {@code 0} when no response
     *                   was received (transport error, timeout, pool exhaustion, etc.)
     * @param duration   elapsed time from {@code execute()} subscription (including pool-acquisition
     *                   wait) to completion or error
     * @param outcome    categorised result — see {@link RequestOutcome}
     */
    void recordRequest(
            String clientId,
            HttpMethod method,
            int statusCode,
            Duration duration,
            RequestOutcome outcome
    );

    /**
     * Called periodically by the library's background
     * {@link PoolMetricsReporter} to report
     * current pool utilization.
     *
     * <p>Implementations should treat this as a gauge update — the values represent the
     * instantaneous state of the pool at the time of sampling, not a cumulative count.
     *
     * @param target            identifies the connection pool (scheme + host + port + tlsConfigName)
     * @param activeConnections number of currently acquired (in-use) TCP connections
     * @param pendingRequests   number of requests currently waiting for a free pool slot
     */
    void recordPoolState(
            TransportTarget target,
            int activeConnections,
            int pendingRequests
    );
}