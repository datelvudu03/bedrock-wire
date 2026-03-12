package cz.syntea.bedrock.wire.classic.registry;

import cz.syntea.bedrock.wire.classic.config.HttpClientConfig;
import cz.syntea.bedrock.wire.classic.model.TlsConfig;

/**
 * Manages {@link HttpClient} instances and their shared connection pools.
 *
 * <h3>Lifecycle</h3>
 * SHOULD be a singleton within the application. Creating multiple instances
 * may result in duplicate connection pools for the same
 * {@link cz.syntea.bedrock.wire.classic.model.TransportTarget}.
 *
 * <h3>Spring integration</h3>
 * Declare as a {@code @Bean} and annotate the {@code close()} method with
 * {@code @PreDestroy}. Failure to do so will result in connection pool resources
 * not being released on application shutdown.
 *
 * <pre>{@code
 * @Bean
 * public HttpClientRegistry httpClientRegistry() {
 *     return new HttpClientRegistryImpl();
 * }
 *
 * @PreDestroy
 * public void shutdownRegistry() {
 *     httpClientRegistry().close();
 * }
 * }</pre>
 *
 * <h3>Thread safety</h3>
 * Implementations MUST be thread-safe. {@code get()} MUST be safe for concurrent calls.
 *
 * <h3>Caching</h3>
 * {@code get()} caches {@link HttpClient} instances by {@code clientId}.
 * The same instance is returned for repeated calls with the same {@code clientId}.
 * Calling {@code get()} with the same {@code clientId} but different parameters
 * MUST throw an exception.
 */
public interface HttpClientRegistry extends AutoCloseable {

    /**
     * Registers a TLS configuration profile.
     *
     * <p>MUST be called before any {@code get()} that references the given
     * {@code tlsConfigName}. Registering the same {@code configName} twice
     * MUST throw an exception.
     *
     * @param config the TLS profile to register; must not be {@code null}
     * @throws cz.syntea.bedrock.wire.classic.exception.InsecureConfigurationException if {@code hostnameVerification=false} without {@code allowInsecureInProduction=true}
     * @throws IllegalStateException                                                   if the registry is already closed
     */
    void registerTlsConfig(TlsConfig config);

    /**
     * Returns a cached or newly created {@link HttpClient} for the given configuration.
     *
     * <p>Connection pools are created lazily and shared per
     * {@link cz.syntea.bedrock.wire.classic.model.TransportTarget}.
     *
     * @param config client configuration; must not be {@code null}
     * @return the {@link HttpClient} instance; never {@code null}
     * @throws IllegalArgumentException                                           if {@code tlsConfigName} references an unregistered config,
     *                                                                            or the same {@code clientId} was registered with different parameters
     * @throws cz.syntea.bedrock.wire.classic.exception.RegistryCapacityException if {@code maxClients} is exceeded
     * @throws IllegalStateException                                              if the registry is already closed
     */
    HttpClient get(HttpClientConfig config);

    /**
     * Hard-closes all connection pools and releases all resources immediately.
     *
     * <p>Any {@code Mono} subscription in-flight at the time of {@code close()} will
     * immediately emit {@code Mono.error(RegistryClosedException)}.
     *
     * <p>After {@code close()}, every later call to {@code get()} MUST throw
     * {@link IllegalStateException}. Idempotent — repeated calls are safe.
     *
     * <p>SHOULD only be called during application shutdown.
     */
    @Override
    void close();
}