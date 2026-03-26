package cz.syntea.bedrock.wire.classic.spring;

import cz.syntea.bedrock.wire.classic.config.HttpClientRegistryConfig;
import cz.syntea.bedrock.wire.classic.observability.NoOpTraceHeaderPropagator;
import cz.syntea.bedrock.wire.classic.observability.NoOpWireMetricsCollector;
import cz.syntea.bedrock.wire.classic.observability.TraceHeaderPropagator;
import cz.syntea.bedrock.wire.classic.observability.WireMetricsCollector;
import cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry;
import cz.syntea.bedrock.wire.classic.registry.HttpClientRegistryImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Auto-configuration for {@code bedrock-wire-client}.
 *
 * <p>Registers the core beans ({@link HttpClientRegistry}, {@link WireMetricsCollector},
 * {@link TraceHeaderPropagator}) and conditionally enables property-driven client
 * auto-registration.
 *
 * <p><b>Auto-registration behavior:</b>
 * <ul>
 *   <li>When {@code MonitorTransport} is present — auto-registration is <b>disabled</b>.
 *       The monitor's {@code WireClientTransport.init()} creates and manages clients.</li>
 *   <li>When no {@code MonitorTransport} — auto-registration is <b>enabled</b>:
 *     <ul>
 *       <li>Primary: clients from {@code bedrock.wire.client.clients.*} (Spring Environment)
 *           are exposed as named beans via {@link WireClientBeanDefinitionRegistrar}.</li>
 *       <li>Fallback: clients from {@code wire.client.*} ({@code .param} file) are
 *           registered in the registry via {@link ParamFileClientRegistrar}.</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * @since 1.0
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(BedrockWireClientProperties.class)
public class BedrockWireClientAutoConfiguration {

    // ─── Core beans (always registered) ─────────────────────────────────────────

    /**
     * Creates the {@link HttpClientRegistry} singleton.
     *
     * @param properties       the wire client properties
     * @param metricsCollector the metrics collector
     * @return the registry instance
     */
    @Bean
    @ConditionalOnMissingBean
    public HttpClientRegistry httpClientRegistry(
            BedrockWireClientProperties properties,
            WireMetricsCollector metricsCollector) {
        HttpClientRegistryConfig config = HttpClientRegistryConfig.builder()
                .maxClients(properties.getMaxClients())
                .build();
        return new HttpClientRegistryImpl(config, metricsCollector);
    }

    /**
     * Creates a no-op metrics collector if none is provided.
     *
     * @return the no-op collector
     */
    @Bean
    @ConditionalOnMissingBean
    public WireMetricsCollector wireMetricsCollector() {
        return new NoOpWireMetricsCollector();
    }

    /**
     * Creates a no-op trace header propagator if none is provided.
     *
     * @return the no-op propagator
     */
    @Bean
    @ConditionalOnMissingBean
    public TraceHeaderPropagator traceHeaderPropagator() {
        return new NoOpTraceHeaderPropagator();
    }

    // ─── Auto-registration (disabled when MonitorTransport is present) ──────────

    /**
     * Nested configuration that activates client auto-registration from the Spring
     * Environment. Guarded by the absence of {@code MonitorTransport}.
     *
     * @since 1.1
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(type = "cz.syntea.bedrock.wire.monitor.spi.MonitorTransport")
    @Import(WireClientBeanDefinitionRegistrar.class)
    static class SpringEnvironmentAutoRegistration {
        // WireClientBeanDefinitionRegistrar handles everything.
    }

    /**
     * Nested configuration that activates fallback client auto-registration from
     * a {@code .param} file. Only active when no {@code MonitorTransport} bean exists
     * and no clients are defined in the Spring Environment.
     *
     * @since 1.1
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(type = "cz.syntea.bedrock.wire.monitor.spi.MonitorTransport")
    static class ParamFileFallbackAutoRegistration {

        private static final Set<String> SPRING_INTERNAL_BEANS = Set.of(
                "systemProperties", "systemEnvironment");

        /**
         * Finds the application's {@link Properties} bean, filtering out Spring internals.
         *
         * @param ctx the application context
         * @return the application properties; never {@code null} (condition guarantees existence)
         */
        static Properties findAppProperties(ApplicationContext ctx) {
            return ctx.getBeansOfType(Properties.class).entrySet().stream()
                    .filter(e -> !SPRING_INTERNAL_BEANS.contains(e.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "ParamFileFallbackCondition matched but no Properties bean found"));
        }

        /**
         * Creates the {@link ParamFileClientRegistrar}.
         *
         * <p>Guarded by {@link ParamFileFallbackCondition}: only created when
         * Spring Environment clients map is empty and a non-Spring
         * {@code Properties} bean exists.
         *
         * @param registry           the HTTP client registry
         * @param tracePropagator    the trace header propagator to inject into clients
         * @param applicationContext the application context for bean lookup
         * @return the registrar; never {@code null}
         */
        @Bean
        @Conditional(ParamFileFallbackCondition.class)
        public ParamFileClientRegistrar paramFileClientRegistrar(
                HttpClientRegistry registry,
                TraceHeaderPropagator tracePropagator,
                ApplicationContext applicationContext) {

            Properties appProps = findAppProperties(applicationContext);
            log.info("Using .param file fallback for wire-client auto-registration");
            return new ParamFileClientRegistrar(registry, appProps, tracePropagator);
        }
    }

    /**
     * Condition that matches when:
     * <ol>
     *   <li>No clients are defined in {@code bedrock.wire.client.clients.*}</li>
     *   <li>An application {@code Properties} bean exists (not Spring internals)</li>
     * </ol>
     */
    static class ParamFileFallbackCondition implements Condition {

        private static final Set<String> SPRING_INTERNAL_BEANS = Set.of(
                "systemProperties", "systemEnvironment");

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            BedrockWireClientProperties props = Binder.get(context.getEnvironment())
                    .bind("bedrock.wire.client", BedrockWireClientProperties.class)
                    .orElse(new BedrockWireClientProperties());

            if (!props.getClients().isEmpty()) {
                // Spring Environment clients defined — no fallback needed
                return false;
            }

            // Check if a non-Spring Properties bean exists in the bean factory
            if (context.getBeanFactory() == null) {
                return false;
            }
            String[] beanNames = context.getBeanFactory().getBeanNamesForType(Properties.class);
            for (String name : beanNames) {
                if (!SPRING_INTERNAL_BEANS.contains(name)) {
                    return true;
                }
            }
            return false;
        }
    }

}