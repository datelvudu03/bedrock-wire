package cz.syntea.bedrock.wire.classic.registry;

import cz.syntea.bedrock.wire.classic.config.HttpClientConfig;
import cz.syntea.bedrock.wire.classic.exception.BedrockWireException;
import cz.syntea.bedrock.wire.classic.exception.PoolAcquisitionTimeoutException;
import cz.syntea.bedrock.wire.classic.exception.ReadTimeoutException;
import cz.syntea.bedrock.wire.classic.exception.RedirectNotSupportedException;
import cz.syntea.bedrock.wire.classic.exception.RegistryClosedException;
import cz.syntea.bedrock.wire.classic.exception.RequestTimeoutException;
import cz.syntea.bedrock.wire.classic.exception.ResponseSizeExceededException;
import cz.syntea.bedrock.wire.classic.exception.TransportException;
import cz.syntea.bedrock.wire.classic.model.HttpMethod;
import cz.syntea.bedrock.wire.classic.model.HttpRequest;
import cz.syntea.bedrock.wire.classic.model.HttpResponse;
import cz.syntea.bedrock.wire.classic.model.TransportTarget;
import cz.syntea.bedrock.wire.classic.model.enums.RequestOutcome;
import cz.syntea.bedrock.wire.classic.observability.WireMetricsCollector;
import io.netty.channel.ConnectTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

import java.net.URI;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * {@link HttpClient} implementation backed by Spring {@link WebClient} and Reactor Netty.
 *
 * <h2>Timeout implementation</h2>
 *
 * <h3>responseTimeout</h3>
 * Configured on the Reactor Netty {@code HttpClient} via {@code .responseTimeout(duration)}
 * (set in {@link HttpClientRegistryImpl#buildNettyClient}).
 * Covers the period from sending the request to receiving the <em>first byte</em> of the
 * HTTP response. Does NOT include DNS, TCP connect, or TLS handshake (those are covered by
 * {@code connectTimeout}). When it fires, Reactor Netty emits {@link TimeoutException};
 * this class maps that to {@link RequestTimeoutException}.
 *
 * <h3>readTimeout</h3>
 * Implemented via Netty's {@link io.netty.handler.timeout.ReadTimeoutHandler} injected into
 * the channel pipeline per-request via {@code doOnRequest} / {@code doAfterResponseSuccess}
 * (in {@link HttpClientRegistryImpl#buildNettyClient}).
 * This is a true per-byte timeout: fires when no data arrives for {@code readTimeout}
 * consecutive milliseconds during body transfer.
 * When it fires, Netty emits {@link io.netty.handler.timeout.ReadTimeoutException};
 * this class maps that to {@link ReadTimeoutException}.
 *
 * <h2>Logging</h2>
 * All logging goes through {@link WireLogger}, which enforces the logger name
 * {@code bedrock.wire.client} and populates MDC fields per spec §1.12.
 * {@code @Slf4j} is intentionally NOT used in this class.
 *
 * <h2>Header merge order (highest priority wins)</h2>
 * <ol>
 *   <li>{@code HttpClientConfig.defaultHeaders} — static defaults</li>
 *   <li>{@link cz.syntea.bedrock.wire.classic.observability.TraceHeaderPropagator} — per-request trace context</li>
 *   <li>{@code HttpRequest.headers} — caller-supplied; <em>replaces</em> (not appends)</li>
 * </ol>
 * Header names are compared case-insensitively (RFC 7230). Spring's {@link HttpHeaders} enforces this.
 *
 * <h2>Body handling</h2>
 * <ul>
 *   <li>{@code HEAD}: body silently discarded per spec.</li>
 *   <li>Null or empty body: {@link BodyInserters#empty()} — avoids spurious {@code Content-Length: 0}
 *       on GET/DELETE.</li>
 *   <li>Non-empty body: sent via {@code bodyValue(String)} as UTF-8 text.</li>
 * </ul>
 *
 * <h2>Duration semantics</h2>
 * <ul>
 *   <li>{@link HttpResponse#getDuration()} — from sending the request to last byte of body.</li>
 *   <li>Metrics {@code duration} — from {@code execute()} subscription (including pool wait) to completion.</li>
 * </ul>
 */
class HttpClientImpl implements HttpClient {

    private static final String URL = " url=";
    private final HttpClientConfig config;
    private final TransportTarget transportTarget;  // needed for pool-exhausted log (spec §1.12)
    private final WebClient webClient;
    private final WireMetricsCollector metricsCollector;
    private final BooleanSupplier registryClosedChecker;
    private final AtomicInteger inFlightCounter;

    HttpClientImpl(
            HttpClientConfig config,
            TransportTarget transportTarget,
            WebClient webClient,
            WireMetricsCollector metricsCollector,
            BooleanSupplier registryClosedChecker,
            AtomicInteger inFlightCounter) {
        this.config = config;
        this.transportTarget = transportTarget;
        this.webClient = webClient;
        this.metricsCollector = metricsCollector;
        this.registryClosedChecker = registryClosedChecker;
        this.inFlightCounter = inFlightCounter;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Computes the UTF-8 encoded byte length of a string without allocating a byte array.
     * Each char is counted based on its Unicode code point: 1 byte for ASCII,
     * 2 for U+0080–U+07FF, 3 for BMP (including surrogates handled via pairs), 4 for supplementary.
     */
    private static int utf8ByteLength(String s) {
        int count = 0;
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            if (c <= 0x7F) {
                count++;
            } else if (c <= 0x7FF) {
                count += 2;
            } else if (Character.isHighSurrogate(c) && i + 1 < len
                    && Character.isLowSurrogate(s.charAt(i + 1))) {
                count += 4;
                i++; // skip low surrogate
            } else {
                count += 3;
            }
        }
        return count;
    }

    // ── Request validation ────────────────────────────────────────────────────

    private static RequestOutcome outcomeFromError(Throwable e) {
        if (e instanceof RequestTimeoutException) return RequestOutcome.TIMEOUT;
        if (e instanceof ReadTimeoutException) return RequestOutcome.READ_TIMEOUT;
        if (e instanceof PoolAcquisitionTimeoutException) return RequestOutcome.POOL_EXHAUSTED;
        if (e instanceof ResponseSizeExceededException) return RequestOutcome.SIZE_EXCEEDED;
        if (e instanceof RedirectNotSupportedException) return RequestOutcome.REDIRECT_REJECTED;
        if (e instanceof RegistryClosedException) return RequestOutcome.REGISTRY_CLOSED;
        return RequestOutcome.TRANSPORT_ERROR;
    }

    // ── URL construction ──────────────────────────────────────────────────────

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

    // ── WebClient call ────────────────────────────────────────────────────────

    private static Map<String, List<String>> copyHeaders(HttpHeaders springHeaders) {
        Map<String, List<String>> result = LinkedHashMap.newLinkedHashMap(springHeaders.size());
        springHeaders.forEach((name, values) ->
                result.put(name, List.copyOf(values)));
        return Collections.unmodifiableMap(result);
    }

    @Override
    public Mono<HttpResponse> execute(HttpRequest request) {
        // Fail-fast validations — synchronous, before any reactive chain is assembled.
        // These throw directly rather than emitting Mono.error, which is intentional:
        // a null request or absolute URL is a programming error, not a runtime condition.
        validateRequest(request);

        if (registryClosedChecker.getAsBoolean()) {
            return Mono.error(new RegistryClosedException(config.getClientId()));
        }

        final URI fullUri = buildFullUri(request);

        // metricsStart and inFlightCounter are managed inside the reactive chain so that
        // they track actual subscription time, not call time.
        //
        // Bug fix: previously both were set synchronously in execute().  Since Mono is cold,
        // a caller may build a chain (call execute()) and defer subscription — or never
        // subscribe at all.  Setting them synchronously would:
        //   • metricsStart: silently include assembly-time lag in every duration measurement,
        //     making metrics inaccurate proportional to the delay between compose and subscribe.
        //   • inFlightCounter: increment without a matching doFinally decrement when the Mono
        //     is never subscribed, causing close() to log a false in-flight ERROR indefinitely.
        //
        // Fix: both are moved into doOnSubscribe, which fires exactly once per subscription,
        // immediately before the operator chain starts executing — i.e. at the correct moment.
        final long[] metricsStart = {0L};

        // Mono.defer ensures the closed check runs at subscription time, not at
        // assembly time. This closes the gap where the registry could be closed
        // between the synchronous check above and actual subscription.
        return Mono.defer(() -> {
                    if (registryClosedChecker.getAsBoolean()) {
                        return Mono.error(
                                new RegistryClosedException(config.getClientId()));
                    }
                    return buildWebClientCall(request, fullUri);
                })
                // ── enforce max response body size ────────────────────────────
                .flatMap(response -> enforceBodySizeLimit(response, fullUri))
                // ── map all Netty / JDK exceptions → BedrockWireException ─────
                .onErrorMap(this::shouldWrap, e -> wrapException(e, fullUri, elapsed(metricsStart[0])))
                // ── metrics ───────────────────────────────────────────────────
                .doOnSuccess(r -> recordMetrics(request.getMethod(), r.getStatusCode(),
                        elapsed(metricsStart[0]), RequestOutcome.SUCCESS))
                .doOnError(e -> recordMetrics(request.getMethod(), 0,
                        elapsed(metricsStart[0]), outcomeFromError(e)))
                // ── subscription-time setup ───────────────────────────────────
                // doOnSubscribe fires once per subscription, before any network activity.
                // This is the correct place for both the metrics clock and the in-flight
                // counter: too early means the measurement is wrong; too late is impossible
                // because doOnSubscribe is the earliest reactive hook available.
                .doOnSubscribe(s -> {
                    metricsStart[0] = System.nanoTime();
                    inFlightCounter.incrementAndGet();
                })
                // ── in-flight counter ─────────────────────────────────────────
                // doFinally fires for every terminal signal (onComplete, onError, cancel).
                // Placed after doOnSubscribe so the decrement always has a matching increment.
                .doFinally(sig -> inFlightCounter.decrementAndGet());
    }

    private void validateRequest(HttpRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("HttpRequest must not be null");
        }
        if (request.getUrl() == null) {
            throw new IllegalArgumentException("HttpRequest.url must not be null");
        }
        URI url = request.getUrl();
        if (url.isAbsolute()) {
            throw new IllegalArgumentException(
                    "HttpRequest.url must be relative (absolute URL not allowed): " + url);
        }
        if (!url.toString().startsWith("/")) {
            throw new IllegalArgumentException(
                    "HttpRequest.url must start with '/': " + url);
        }
        if (request.getMethod() == null) {
            throw new IllegalArgumentException("HttpRequest.method must not be null");
        }
    }

    private URI buildFullUri(HttpRequest request) {
        return URI.create(config.getBaseUrl().toString() + request.getUrl().toString());
    }

    private Mono<HttpResponse> buildWebClientCall(HttpRequest request, URI fullUri) {
        org.springframework.http.HttpMethod springMethod =
                org.springframework.http.HttpMethod.valueOf(request.getMethod().name());

        // Mono.defer re-executes this lambda on each subscription, so System.nanoTime()
        // is captured once per request immediately before the WebClient call is assembled —
        // after pool acquisition, right as the request is sent.
        // This becomes HttpResponse.duration per spec §1.8.2.
        //
        // Why NOT doOnSubscribe on RequestHeadersSpec:
        //   RequestHeadersSpec is not a Publisher — it has no doOnSubscribe method.
        // Why NOT doOnSubscribe on the Mono from exchangeToMono:
        //   It fires before pool acquisition, so it would include pool-wait time,
        //   violating the spec definition of duration.
        return Mono.defer(() -> {
            final long requestStart = System.nanoTime();

            return buildRequestSpec(request, fullUri, springMethod)
                    .exchangeToMono(clientResponse -> {
                        int statusCode = clientResponse.statusCode().value();

                        // Reject 3xx except 304 Not Modified (spec §1.8.2)
                        if (statusCode >= 300 && statusCode < 400 && statusCode != 304) {
                            return clientResponse.releaseBody()
                                    .then(Mono.error(new RedirectNotSupportedException(
                                            config.getClientId(), fullUri, statusCode)));
                        }

                        return clientResponse.bodyToMono(String.class)
                                .defaultIfEmpty("")
                                .map(body -> HttpResponse.builder()
                                        .statusCode(statusCode)
                                        .headers(copyHeaders(clientResponse.headers().asHttpHeaders()))
                                        .responseBody(body)
                                        .duration(elapsed(requestStart))
                                        .build());
                    });
        });
    }

    /**
     * Builds the {@link WebClient.RequestHeadersSpec} with the correct body strategy.
     */
    private WebClient.RequestHeadersSpec<?> buildRequestSpec(
            HttpRequest request, URI fullUri,
            org.springframework.http.HttpMethod springMethod) {

        WebClient.RequestBodySpec bodySpec = webClient
                .method(springMethod)
                .uri(fullUri)
                .headers(httpHeaders -> applyHeaders(httpHeaders, request));

        // HEAD: spec mandates body MUST be silently discarded
        if (request.getMethod() == HttpMethod.HEAD) {
            return bodySpec.body(BodyInserters.empty());
        }

        // Non-empty body: send as UTF-8 text
        String body = request.getBody();
        if (body != null && !body.isEmpty()) {
            return bodySpec.bodyValue(body);
        }

        // Null or empty body: no entity (avoids Content-Length: 0 on GET/DELETE)
        return bodySpec.body(BodyInserters.empty());
    }

    /**
     * Applies headers in priority order: defaultHeaders → trace → request (highest).
     * RFC 7230 case-insensitive semantics enforced by Spring's {@link HttpHeaders}.
     * Higher-priority values replace (not append) lower-priority ones.
     */
    private void applyHeaders(HttpHeaders httpHeaders, HttpRequest request) {
        // 1. defaultHeaders — lowest priority
        if (config.getDefaultHeaders() != null) {
            config.getDefaultHeaders().forEach(httpHeaders::addAll);
        }

        // 2. Trace headers — mid priority; replace any matching default
        Map<String, String> traceHeaders =
                config.getTraceHeaderPropagator().headersForRequest(request);
        if (traceHeaders != null) {
            traceHeaders.forEach((name, value) -> {
                httpHeaders.remove(name);
                httpHeaders.add(name, value);
            });
        }

        // 3. Per-request headers — highest priority; replace any matching header from above
        if (request.getHeaders() != null) {
            request.getHeaders().forEach((name, values) -> {
                httpHeaders.remove(name);
                httpHeaders.addAll(name, values);
            });
        }
    }

    private Mono<HttpResponse> enforceBodySizeLimit(HttpResponse response, URI fullUri) {
        int byteLength = utf8ByteLength(response.getResponseBody());
        int limit = config.getMaxResponseBodySize();
        if (byteLength > limit) {
            WireLogger.responseSizeExceeded(config.getClientId(), fullUri, byteLength, limit);
            return Mono.error(new ResponseSizeExceededException(limit));
        }
        return Mono.just(response);
    }

    /**
     * Pass already-wrapped exceptions through; wrap everything else.
     */
    private boolean shouldWrap(Throwable e) {
        return !(e instanceof BedrockWireException);
    }


    /**
     * Maps JDK / Netty / Reactor Netty exceptions to {@link BedrockWireException} subtypes
     * and emits the required spec §1.12 log events via {@link WireLogger}.
     *
     * @param actualElapsed real wall-clock time since subscription, used for timeout log MDC
     */
    private BedrockWireException wrapException(Throwable e, URI fullUri, Duration actualElapsed) {
        String clientId = config.getClientId();

        // responseTimeout → java.util.concurrent.TimeoutException (from Reactor Netty .responseTimeout())
        if (e instanceof TimeoutException) {
            WireLogger.requestTimeout(clientId, fullUri, actualElapsed);
            return new RequestTimeoutException(clientId, fullUri, config.getResponseTimeout());
        }

        // readTimeout → io.netty.handler.timeout.ReadTimeoutException (from ReadTimeoutHandler)
        if (e instanceof io.netty.handler.timeout.ReadTimeoutException) {
            WireLogger.readTimeout(clientId, fullUri, actualElapsed);
            return new ReadTimeoutException(clientId, fullUri, config.getReadTimeout());
        }

        // Pool exhaustion: Reactor Netty uses non-public exception classes; match by name.
        // Spec §1.12: pool exhausted — WARN with MDC fields transportTarget, pendingRequests.
        // The live queue depth is unavailable from the exception; the configured ceiling is
        // logged instead.
        String exClass = e.getClass().getName();
        if (exClass.contains("PoolAcquireTimeoutException")
                || exClass.contains("PendingAcquireTimeoutException")) {
            WireLogger.poolExhausted(transportTarget.toPoolName(), config.getMaxPendingRequests());
            return new PoolAcquisitionTimeoutException(
                    "Pool slot not available within " + config.getPoolAcquisitionTimeout()
                            + ": clientId=" + clientId, e);
        }

        if (e instanceof ConnectTimeoutException) {
            return new TransportException(
                    "Connect timeout: clientId=" + clientId + URL + fullUri, e);
        }

        if (e instanceof PrematureCloseException) {
            return new TransportException(
                    "Connection closed prematurely: clientId=" + clientId + URL + fullUri, e);
        }

        if (e instanceof java.net.ConnectException) {
            return new TransportException(
                    "Connection refused: clientId=" + clientId + URL + fullUri, e);
        }

        if (e instanceof java.io.IOException) {
            return new TransportException(
                    "IO error: clientId=" + clientId + URL + fullUri, e);
        }

        // Fallback — all execute() errors MUST be BedrockWireException subtypes
        return new TransportException(
                "Unexpected error: clientId=" + clientId + URL + fullUri, e);
    }

    private void recordMetrics(HttpMethod method, int statusCode,
                               Duration duration, RequestOutcome outcome) {
        try {
            metricsCollector.recordRequest(config.getClientId(), method, statusCode,
                    duration, outcome);
        } catch (Exception ignored) {
            // Metrics failures MUST NOT affect request flow
        }
    }
}
