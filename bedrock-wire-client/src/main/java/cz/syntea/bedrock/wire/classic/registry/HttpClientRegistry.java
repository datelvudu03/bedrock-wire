package cz.syntea.bedrock.wire.classic.registry;

import cz.syntea.bedrock.wire.classic.config.HttpClientConfig;
import cz.syntea.bedrock.wire.classic.model.TlsConfig;

import java.util.Set;


/**
 * Registry for managing {@link HttpClient} instances and their connection pools.
 *
 * <p>Clients are created lazily via {@link #get(HttpClientConfig)} and cached by
 * {@code clientId}. Once registered, a client can be retrieved by its ID alone
 * via {@link #get(String)}.
 *
 * <p>Clients sharing the same {@code TransportTarget} (scheme + host + port + tlsConfigName)
 * share a connection pool.
 *
 * @since 1.0
 */
public interface HttpClientRegistry {

    /**
     * Returns an {@link HttpClient} for the given configuration, creating one if necessary.
     *
     * <p>If a client with the same {@code clientId} already exists, the cached instance
     * is returned. Otherwise, a new client and its connection pool are created atomically.
     *
     * @param config the client configuration; must not be {@code null}
     * @return the cached or newly created client; never {@code null}
     * @throws cz.syntea.bedrock.wire.classic.exception.RegistryCapacityException
     *         if the registry has reached its maximum capacity
     * @throws cz.syntea.bedrock.wire.classic.exception.RegistryClosedException
     *         if the registry has been closed
     */
    HttpClient get(HttpClientConfig config);

    /**
     * Returns a previously registered {@link HttpClient} by its {@code clientId}.
     *
     * @param clientId the client identifier used during registration; must not be {@code null}
     * @return the cached client; never {@code null}
     * @throws IllegalArgumentException if no client with the given {@code clientId} is registered
     * @throws cz.syntea.bedrock.wire.classic.exception.RegistryClosedException
     *         if the registry has been closed
     * @since 1.1
     */
    HttpClient get(String clientId);

    /**
     * Checks whether a client with the given {@code clientId} is registered.
     *
     * @param clientId the client identifier to check; must not be {@code null}
     * @return {@code true} if a client with the given ID exists in the registry
     * @since 1.1
     */
    boolean containsClient(String clientId);

    /**
     * Returns a snapshot of all registered client IDs.
     *
     * @return an unmodifiable set of client IDs; never {@code null}, may be empty
     * @since 1.1
     */
    Set<String> getRegisteredClientIds();

    /**
     * Registers a TLS configuration profile for use by clients.
     *
     * @param tlsConfig the TLS configuration; must not be {@code null}
     */
    void registerTlsConfig(TlsConfig tlsConfig);

    /**
     * Closes the registry and releases all connection pools.
     *
     * <p>After this method returns, all subsequent calls to {@link #get(HttpClientConfig)}
     * and {@link #get(String)} will throw {@code RegistryClosedException}.
     */
    void close();

}