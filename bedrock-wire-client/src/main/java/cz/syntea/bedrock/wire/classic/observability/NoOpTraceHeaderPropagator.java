package cz.syntea.bedrock.wire.classic.observability;

import cz.syntea.bedrock.wire.classic.model.HttpRequest;

import java.util.Map;

/**
 * No-op implementation of {@link TraceHeaderPropagator}.
 * Used as the default when no tracing integration is configured.
 */
public class NoOpTraceHeaderPropagator implements TraceHeaderPropagator {

    @Override
    public Map<String, String> headersForRequest(HttpRequest request) {
        return Map.of();
    }
}