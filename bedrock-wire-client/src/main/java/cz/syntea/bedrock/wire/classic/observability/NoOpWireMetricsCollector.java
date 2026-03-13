package cz.syntea.bedrock.wire.classic.observability;

import cz.syntea.bedrock.wire.classic.model.HttpMethod;
import cz.syntea.bedrock.wire.classic.model.TransportTarget;
import cz.syntea.bedrock.wire.classic.model.enums.RequestOutcome;

import java.time.Duration;

/**
 * No-op implementation of {@link WireMetricsCollector}.
 *
 * <p>Used as the default when no metrics backend is configured. All methods are
 * empty and return immediately. Replacing this with a Micrometer adapter enables
 * full request and pool metrics without any other code changes.
 */
public final class NoOpWireMetricsCollector implements WireMetricsCollector {

    @Override
    public void recordRequest(String clientId, HttpMethod method, int statusCode,
                              Duration duration, RequestOutcome outcome) {
        // no-op
    }

    @Override
    public void recordPoolState(TransportTarget target, int activeConnections, int pendingRequests) {
        // no-op
    }
}
