package cz.syntea.bedrock.wire.classic.registry;

import cz.syntea.bedrock.wire.classic.model.HttpRequest;
import cz.syntea.bedrock.wire.classic.model.HttpResponse;
import reactor.core.publisher.Mono;

/**
 * Reactive HTTP client bound to a single {@code baseUrl}.
 *
 * <p>Instances are obtained from and cached by
 * {@link HttpClientRegistry#get(cz.syntea.bedrock.wire.classic.config.HttpClientConfig)}.
 *
 * <h3>Concurrency</h3>
 * Implementations MUST be safe for concurrent use from multiple threads and reactive schedulers.
 *
 * <h3>Retries</h3>
 * {@code execute()} MUST NOT perform automatic retries. Retry logic is the caller's responsibility
 * to prevent silent duplication of non-idempotent requests (POST, PATCH).
 *
 * <h3>Blocking</h3>
 * {@code execute()} MUST NOT block the calling thread.
 */
public interface HttpClient {

    /**
     * Executes a single HTTP request against the configured {@code baseUrl}.
     *
     * <p>The returned {@link Mono} is cold — no network activity occurs until subscription.
     *
     * <h3>Fail-fast validations (thrown synchronously, before subscription)</h3>
     * <ul>
     *   <li>{@code request.url} is {@code null} → {@link IllegalArgumentException}</li>
     *   <li>{@code request.url} is absolute → {@link IllegalArgumentException}</li>
     *   <li>{@code request.url} does not start with {@code /} → {@link IllegalArgumentException}</li>
     * </ul>
     *
     * <h3>Reactive errors (emitted via Mono.error)</h3>
     * <ul>
     *   <li>{@link cz.syntea.bedrock.wire.classic.exception.RequestTimeoutException} — responseTimeout exceeded</li>
     *   <li>{@link cz.syntea.bedrock.wire.classic.exception.ReadTimeoutException} — readTimeout exceeded</li>
     *   <li>{@link cz.syntea.bedrock.wire.classic.exception.PoolAcquisitionTimeoutException} — pool exhausted</li>
     *   <li>{@link cz.syntea.bedrock.wire.classic.exception.ResponseSizeExceededException} — body too large</li>
     *   <li>{@link cz.syntea.bedrock.wire.classic.exception.RedirectNotSupportedException} — 3xx response (not 304)</li>
     *   <li>{@link cz.syntea.bedrock.wire.classic.exception.TransportException} — connection / IO error</li>
     *   <li>{@link cz.syntea.bedrock.wire.classic.exception.RegistryClosedException} — registry was closed</li>
     * </ul>
     *
     * @param request the request to execute; must not be {@code null}
     * @return cold {@link Mono} emitting exactly one {@link HttpResponse} on success
     */
    Mono<HttpResponse> execute(HttpRequest request);
}