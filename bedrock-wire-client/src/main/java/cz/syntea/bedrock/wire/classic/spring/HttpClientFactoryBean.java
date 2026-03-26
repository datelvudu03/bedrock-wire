package cz.syntea.bedrock.wire.classic.spring;

import cz.syntea.bedrock.wire.classic.config.HttpClientConfig;
import cz.syntea.bedrock.wire.classic.observability.TraceHeaderPropagator;
import cz.syntea.bedrock.wire.classic.registry.HttpClient;
import cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.FactoryBean;

import java.util.Objects;

/**
 * Spring {@link FactoryBean} that lazily creates an {@link HttpClient} via
 * the {@link HttpClientRegistry}.
 *
 * <p>On first call to {@link #getObject()}, registers the associated TLS profile
 * (if configured) and then obtains the client from the registry. Subsequent calls
 * return the same cached instance (via the registry's internal cache).
 *
 * <p>This bean is registered programmatically by {@link WireClientBeanDefinitionRegistrar}
 * for each client defined in properties.
 *
 * @since 1.1
 */
@Slf4j
public class HttpClientFactoryBean implements FactoryBean<HttpClient> {

    private final String clientId;
    private final ClientProperties clientProperties;
    private final TlsProfileProperties tlsProfileProperties;
    private HttpClientRegistry registry;
    private TraceHeaderPropagator tracePropagator;

    /**
     * Creates a new factory bean for the given client.
     *
     * @param clientId             the client identifier; must not be {@code null}
     * @param clientProperties     the client configuration properties; must not be {@code null}
     * @param tlsProfileProperties the TLS profile properties; may be {@code null} if no TLS
     */
    public HttpClientFactoryBean(String clientId,
                                 ClientProperties clientProperties,
                                 TlsProfileProperties tlsProfileProperties) {
        this.clientId = Objects.requireNonNull(clientId, "clientId must not be null");
        this.clientProperties = Objects.requireNonNull(clientProperties, "clientProperties must not be null");
        this.tlsProfileProperties = tlsProfileProperties;
    }

    /**
     * Sets the {@link HttpClientRegistry} used to create and cache clients.
     *
     * @param registry the registry instance; must not be {@code null}
     */
    public void setRegistry(HttpClientRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry must not be null");
    }

    /**
     * Sets the {@link TraceHeaderPropagator} to inject into each auto-registered client.
     *
     * <p>If not set, clients use the default no-op propagator from {@link HttpClientConfig}.
     *
     * @param tracePropagator the propagator instance; may be {@code null}
     */
    public void setTracePropagator(TraceHeaderPropagator tracePropagator) {
        this.tracePropagator = tracePropagator;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public HttpClient getObject() {
        if (registry == null) {
            throw new IllegalStateException(
                    "HttpClientRegistry not set for client '" + clientId + "'");
        }

        // Register TLS profile if this client references one
        if (tlsProfileProperties != null && clientProperties.getTlsProfile() != null) {
            String profileName = clientProperties.getTlsProfile();
            registry.registerTlsConfig(tlsProfileProperties.toTlsConfig(profileName));
            log.debug("Registered TLS profile '{}' for client '{}'", profileName, clientId);
        }

        HttpClientConfig config = clientProperties.toHttpClientConfig(clientId, tracePropagator);
        log.info("Auto-registering HttpClient '{}' from properties (baseUrl={}, tracing={})",
                clientId, clientProperties.getBaseUrl(),
                tracePropagator != null ? tracePropagator.getClass().getSimpleName() : "NoOp");
        return registry.get(config);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Class<?> getObjectType() {
        return HttpClient.class;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isSingleton() {
        return true;
    }

}