package cz.syntea.bedrock.wire.classic.config;

import cz.syntea.bedrock.wire.classic.observability.NoOpTraceHeaderPropagator;
import cz.syntea.bedrock.wire.classic.observability.NoOpWireMetricsCollector;
import cz.syntea.bedrock.wire.classic.observability.TraceHeaderPropagator;
import cz.syntea.bedrock.wire.classic.observability.WireMetricsCollector;
import cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry;
import cz.syntea.bedrock.wire.classic.registry.HttpClientRegistryImpl;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Spring Boot autoconfiguration for {@code bedrock-wire-client}.
 *
 * <p>Registers the following beans if not already present in the application context:
 * <ul>
 *   <li>{@link HttpClientRegistry} — the central registry; lifecycle managed via {@link PreDestroy}</li>
 *   <li>{@link WireMetricsCollector} — no-op default; replace with a Micrometer adapter if needed</li>
 *   <li>{@link TraceHeaderPropagator} — no-op default; replace with an OTel adapter if needed</li>
 * </ul>
 *
 * <h3>Customization</h3>
 * Declare your own {@code @Bean} of the above types to override the defaults.
 *
 * <h3>Shutdown</h3>
 * The registry is closed in {@link #closeRegistry()} which is annotated with
 * {@link PreDestroy}. This releases all connection pool resources gracefully.
 *
 * <h3>Configuration properties</h3>
 * See {@link BedrockWireClientProperties} ({@code bedrock.wire.client.*}).
 */
@AutoConfiguration
@EnableConfigurationProperties(BedrockWireClientProperties.class)
public class BedrockWireClientAutoConfiguration {

    private HttpClientRegistry registry;

    @Bean
    @ConditionalOnMissingBean(WireMetricsCollector.class)
    public WireMetricsCollector wireMetricsCollector() {
        return new NoOpWireMetricsCollector();
    }

    @Bean
    @ConditionalOnMissingBean(TraceHeaderPropagator.class)
    public TraceHeaderPropagator traceHeaderPropagator() {
        return new NoOpTraceHeaderPropagator();
    }

    @Bean
    @ConditionalOnMissingBean(HttpClientRegistry.class)
    public HttpClientRegistry httpClientRegistry(
            BedrockWireClientProperties properties,
            WireMetricsCollector metricsCollector) {

        HttpClientRegistryConfig registryConfig = HttpClientRegistryConfig.builder()
                .maxClients(properties.getMaxClients())
                .build();

        registry = new HttpClientRegistryImpl(registryConfig, metricsCollector);
        return registry;
    }

    /**
     * Closes all connection pools on application shutdown.
     */
    @PreDestroy
    public void closeRegistry() {
        if (registry != null) {
            registry.close();
        }
    }
}