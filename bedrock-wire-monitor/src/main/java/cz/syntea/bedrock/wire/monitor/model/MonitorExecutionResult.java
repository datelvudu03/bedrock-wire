package cz.syntea.bedrock.wire.monitor.model;

import cz.syntea.bedrock.wire.monitor.spi.MonitorResult;
import lombok.Builder;
import lombok.Value;

import java.time.Duration;
import java.time.Instant;

/**
 * Complete output of a single monitor check run.
 *
 * <p>Passed to all registered
 * {@link cz.syntea.bedrock.wire.monitor.listener.MonitorResultListener} instances
 * after the check run completes (including retries and validation).
 *
 * <h3>Timing</h3>
 * {@link #getExecutionDuration()} spans the entire check run from start to finish,
 * including all retry attempts and delays. Individual HTTP attempt duration is
 * available via {@link MonitorResult#getTransportDuration()}.
 */
@Value
@Builder(toBuilder = true)
public class MonitorExecutionResult {

    /**
     * Name of the check that produced this result.
     */
    String checkName;

    /**
     * Name of the target service.
     */
    String serviceName;

    /**
     * Timestamp when the check run started (before the first HTTP attempt).
     */
    Instant startedAt;

    /**
     * Timestamp when the check run finished (after the last attempt and validation).
     */
    Instant finishedAt;

    /**
     * Total wall-clock duration of the check run ({@code finishedAt - startedAt}).
     */
    Duration executionDuration;

    /**
     * Total number of HTTP attempts made (initial attempt + retries).
     * Always {@code >= 1}.
     */
    int attempts;

    /**
     * Unique identifier for this check run (UUID v4).
     * Used for correlation in logs and skip-if-running diagnostics.
     */
    String requestId;

    /**
     * Final status of the check run, derived from transport outcome and validation.
     */
    MonitorStatus status;

    /**
     * Diagnostic message: first {@code FAIL} message from validators; if none,
     * first {@code WARN} message; otherwise {@code null}.
     * For non-{@code RESPONSE_RECEIVED} transport statuses, contains the
     * transport error message.
     */
    String message;

    /**
     * Raw transport result from the last HTTP attempt.
     * Provides access to HTTP status, response body, headers, and transport duration.
     */
    MonitorResult transport;
}
