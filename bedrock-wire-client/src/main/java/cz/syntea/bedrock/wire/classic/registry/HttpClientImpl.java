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
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.PrematureCloseException;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * {@link HttpClient} backed by Spring {@link WebClient} / Reactor Netty.
 *
 * <p><b>Timeouts:</b> {@code responseTimeout} (first byte) via Reactor Netty;
 * {@code readTimeout} (inter-byte idle) via Netty {@code ReadTimeoutHandler}.
 *
 * <p><b>Headers:</b> merged lowest→highest: defaultHeaders → trace propagator → request.
 * Same-key entries are replaced, not appended (RFC 7230 case-insensitive).
 *
 * <p><b>Response body:</b> streamed via {@code bodyToFlux(DataBuffer.class)} with inline
 * size limit — download is cancelled if {@code maxResponseBodySize} is exceeded. Raw bytes
 * accumulated in {@link ByteArrayOutputStream}, decoded to UTF-8 once after last chunk.
 * All {@link DataBuffer}s released on every path to prevent native memory leaks.
 *
 * <p><b>Duration:</b> {@code HttpResponse.duration} = send → last byte;
 * metrics duration = subscribe (incl. pool wait) → complete.
 *
 * <p>All logging via {@link WireLogger} ({@code bedrock.wire.client}).
 */
class HttpClientImpl implements HttpClient {

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

    private static RequestOutcome outcomeFromError(Throwable e) {
        if (e instanceof RequestTimeoutException) return RequestOutcome.TIMEOUT;
        if (e instanceof ReadTimeoutException) return RequestOutcome.READ_TIMEOUT;
        if (e instanceof PoolAcquisitionTimeoutException) return RequestOutcome.POOL_EXHAUSTED;
        if (e instanceof ResponseSizeExceededException) return RequestOutcome.SIZE_EXCEEDED;
        if (e instanceof RedirectNotSupportedException) return RequestOutcome.REDIRECT_REJECTED;
        if (e instanceof RegistryClosedException) return RequestOutcome.REGISTRY_CLOSED;
        return RequestOutcome.TRANSPORT_ERROR;
    }

    private static Duration elapsed(long startNanos) {
        return Duration.ofNanos(System.nanoTime() - startNanos);
    }

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

        final int limit = config.getMaxResponseBodySize();

        // Mono.defer re-executes this lambda on each subscription, so System.nanoTime()
        // is captured once per request immediately before the WebClient call is assembled —
        // after pool acquisition, right as the request is sent.
        // This becomes HttpResponse.duration per spec §1.8.2.
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

                        Map<String, List<String>> headers =
                                copyHeaders(clientResponse.headers().asHttpHeaders());

                        // Stream the body chunk-by-chunk, enforcing the size limit as
                        // data arrives. If the limit is exceeded, the Flux is cancelled
                        // and no further data is downloaded from the server.
                        //
                        // Raw bytes are accumulated in a ByteArrayOutputStream and
                        // decoded to UTF-8 only once after the last chunk. This avoids
                        // multi-byte character boundary issues (e.g. ř split across
                        // two DataBuffers).
                        final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                        final int[] totalBytes = {0};

                        return clientResponse.bodyToFlux(DataBuffer.class)
                                .doOnNext(dataBuffer -> {
                                    int chunkSize = dataBuffer.readableByteCount();
                                    totalBytes[0] += chunkSize;

                                    if (totalBytes[0] > limit) {
                                        // Release this chunk before signaling error
                                        DataBufferUtils.release(dataBuffer);
                                        WireLogger.responseSizeExceeded(
                                                config.getClientId(), fullUri,
                                                totalBytes[0], limit);
                                        throw new ResponseSizeExceededException(limit);
                                    }

                                    // Copy bytes and release the DataBuffer immediately
                                    // to return Netty's off-heap memory to the pool.
                                    byte[] bytes = new byte[chunkSize];
                                    dataBuffer.read(bytes);
                                    DataBufferUtils.release(dataBuffer);
                                    buffer.write(bytes, 0, chunkSize);
                                })
                                // Safety net: release any DataBuffer that wasn't consumed
                                // by doOnNext (e.g., due to cancel or upstream error).
                                .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
                                .then(Mono.fromSupplier(() ->
                                        HttpResponse.builder()
                                                .statusCode(statusCode)
                                                .headers(headers)
                                                .responseBody(buffer.size() > 0
                                                        ? buffer.toString(StandardCharsets.UTF_8)
                                                        : "")
                                                .duration(elapsed(requestStart))
                                                .build()));
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
                    "Connect timeout: clientId=" + clientId + " url=" + fullUri, e);
        }

        if (e instanceof PrematureCloseException) {
            return new TransportException(
                    "Connection closed prematurely: clientId=" + clientId + " url=" + fullUri, e);
        }

        if (e instanceof java.net.ConnectException) {
            return new TransportException(
                    "Connection refused: clientId=" + clientId + " url=" + fullUri, e);
        }

        if (e instanceof java.io.IOException) {
            return new TransportException(
                    "IO error: clientId=" + clientId + " url=" + fullUri, e);
        }

        // Fallback — all execute() errors MUST be BedrockWireException subtypes
        return new TransportException(
                "Unexpected error: clientId=" + clientId + " url=" + fullUri, e);
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


/*
•.,¸,.•*`•.,¸¸,.•*¯ ╭━━━━╮
•.,¸,.•*¯`•.,¸,.•*¯.|:::::::::: /\___/\
•.,¸,.•*¯`•.,¸,.•* <|:::::::::(｡ ●ω●｡) ᵐᵉᵒʷ ᵐᵉᵒʷ ᵐᵉᵒʷ
•.,¸,.•¯•.,¸,.•╰ * >し------し---Ｊ
*/
