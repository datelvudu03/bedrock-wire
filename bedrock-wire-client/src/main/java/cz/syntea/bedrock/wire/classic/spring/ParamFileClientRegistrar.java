package cz.syntea.bedrock.wire.classic.spring;

import cz.syntea.bedrock.wire.classic.config.HttpClientConfig;
import cz.syntea.bedrock.wire.classic.observability.TraceHeaderPropagator;
import cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.Map;
import java.util.Properties;

/**
 * Fallback registrar that auto-registers HTTP clients from a {@code .param} file
 * when no clients are defined in the Spring Environment.
 *
 * <p>This bean is activated only when:
 * <ol>
 *   <li>No {@code MonitorTransport} bean exists (monitor manages its own clients)</li>
 *   <li>No clients are defined in {@code bedrock.wire.client.clients.*} (Spring Environment)</li>
 *   <li>A {@code Properties} bean exists (typically {@code PropertiesCfg} from {@code .param} file)</li>
 * </ol>
 *
 * <p>TLS profile validation is strict: a client's {@code tlsProfile} must reference a
 * profile defined in the same {@code .param} file's {@code wire.tls.*} namespace.
 * Profiles registered programmatically via {@code registry.registerTlsConfig()} are
 * not considered (the {@code .param} file is treated as a self-contained config source).
 *
 * @since 1.1
 */
@Slf4j
public class ParamFileClientRegistrar implements SmartInitializingSingleton {

    private final HttpClientRegistry registry;
    private final Properties properties;
    private final TraceHeaderPropagator tracePropagator;

    /**
     * Creates a new registrar.
     *
     * @param registry        the HTTP client registry; must not be {@code null}
     * @param properties      the properties source (typically {@code PropertiesCfg}); must not be {@code null}
     * @param tracePropagator the trace header propagator to inject into clients; may be {@code null}
     */
    public ParamFileClientRegistrar(HttpClientRegistry registry,
                                    Properties properties,
                                    TraceHeaderPropagator tracePropagator) {
        this.registry = registry;
        this.properties = properties;
        this.tracePropagator = tracePropagator;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void afterSingletonsInstantiated() {
        ParamFileClientConfigAdapter adapter = new ParamFileClientConfigAdapter(properties);

        Map<String, TlsProfileProperties> tlsProfiles = adapter.parseTlsProfiles();
        Map<String, ClientProperties> clients = adapter.parseClients();

        if (clients.isEmpty()) {
            log.debug("No clients found in .param file under wire.client.* namespace");
            return;
        }

        // Register TLS profiles first
        for (Map.Entry<String, TlsProfileProperties> entry : tlsProfiles.entrySet()) {
            registry.registerTlsConfig(entry.getValue().toTlsConfig(entry.getKey()));
            log.debug("Registered TLS profile '{}' from .param file", entry.getKey());
        }

        // Register clients
        log.info("Auto-registering {} HttpClient(s) from .param file: {}", clients.size(), clients.keySet());
        for (Map.Entry<String, ClientProperties> entry : clients.entrySet()) {
            String clientId = entry.getKey();
            ClientProperties clientProps = entry.getValue();

            if (registry.containsClient(clientId)) {
                log.warn("Client '{}' already registered — skipping .param file registration", clientId);
                continue;
            }

            // Validate TLS profile reference within .param file scope
            if (clientProps.getTlsProfile() != null && !tlsProfiles.containsKey(clientProps.getTlsProfile())) {
                throw new IllegalArgumentException(
                        "Client '" + clientId + "' references TLS profile '"
                                + clientProps.getTlsProfile()
                                + "' which is not defined in wire.tls.* namespace");
            }

            HttpClientConfig config = clientProps.toHttpClientConfig(clientId, tracePropagator);
            registry.get(config); // warm-cache the client
            log.info("Auto-registered HttpClient '{}' from .param file (baseUrl={})",
                    clientId, clientProps.getBaseUrl());
        }
    }

}