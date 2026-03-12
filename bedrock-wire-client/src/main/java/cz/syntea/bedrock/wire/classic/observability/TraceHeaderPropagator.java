package cz.syntea.bedrock.wire.classic.observability;

import cz.syntea.bedrock.wire.classic.model.HttpRequest;

import java.util.Map;

/**
 * SPI for injecting distributed-trace context headers into outbound requests.
 *
 * <p>Configured per {@link cz.syntea.bedrock.wire.classic.config.HttpClientConfig}.
 * Called once per {@code execute()} invocation; returned headers are merged into
 * the request — per-request headers take priority over propagated trace headers.
 *
 * <p>Supported formats: W3C {@code traceparent} / {@code tracestate}, B3 (single/multi-header).
 * An OpenTelemetry adapter may be provided as a separate module.
 *
 * <p><strong>Important:</strong> Do NOT inject trace headers via
 * {@code HttpClientConfig.defaultHeaders} — those are static and would attach the
 * same trace context to every request, breaking distributed tracing.
 */
public interface TraceHeaderPropagator {

    /**
     * Returns headers to be added to the outbound request.
     * The returned map must not be {@code null}; an empty map is valid.
     *
     * @param request the outbound request (read-only; must not be modified)
     * @return headers to inject; single-value entries are typical for trace headers
     */
    Map<String, String> headersForRequest(HttpRequest request);
}
