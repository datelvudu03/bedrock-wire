package cz.syntea.bedrock.wire.monitor.listener;

import cz.syntea.bedrock.wire.monitor.model.MonitorExecutionResult;

/**
 * Callback for monitor check run results.
 *
 * <p>Implementations are notified after each check run completes, regardless
 * of the outcome. Multiple listeners may be registered; they are invoked
 * sequentially in registration order.
 *
 * <h3>Error handling</h3>
 * Exceptions thrown by a listener MUST be logged by the monitor engine
 * but MUST NOT:
 * <ul>
 *   <li>change the status of the check run</li>
 *   <li>prevent notification of subsequent listeners</li>
 *   <li>propagate to the caller</li>
 * </ul>
 */
@FunctionalInterface
public interface MonitorResultListener {

    /**
     * Called when a check run completes.
     *
     * @param result the complete check run result; never {@code null}
     */
    void onResult(MonitorExecutionResult result);
}
