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
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Auto-configuration for {@code bedrock-wire-client}.
 *
 * <p>Registers the core beans ({@link HttpClientRegistry}, {@link WireMetricsCollector},
 * {@link TraceHeaderPropagator}) and conditionally enables client auto-registration
 * from a {@code .param} file.
 *
 * <h3>Auto-registration behavior</h3>
 * <ul>
 *   <li>When {@code MonitorTransport} is present — auto-registration is <b>disabled</b>.
 *       The monitor's {@code WireClientTransport.init()} creates and manages clients.</li>
 *   <li>When no {@code MonitorTransport} — auto-registration is <b>enabled</b> if a
 *       {@code Properties} bean (e.g. {@code PropertiesCfg} from {@code --app.configFile})
 *       is found. Clients defined under {@code wire.client.*} and TLS profiles under
 *       {@code wire.tls.*} are registered in the {@link HttpClientRegistry} via
 *       {@link ParamFileClientRegistrar}.</li>
 * </ul>
 *
 * <h3>Access pattern</h3>
 * Auto-registered clients are accessed via {@code registry.get("clientId")}.
 * Named {@code @Qualifier} beans are not created — all clients live in the registry.
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

    // ─── .param file auto-registration (disabled when MonitorTransport present) ─

    /**
     * Nested configuration that activates client auto-registration from a
     * {@code .param} file ({@code wire.client.*} namespace).
     *
     * <p>The {@link ParamFileRegistrationCondition} on the bean method enforces
     * both requirements: a {@code Properties} bean must exist AND no
     * {@code MonitorTransport} bean may be present. The condition uses
     * {@code BeanFactory} directly, which reliably sees all bean definitions
     * including those from user configurations.
     *
     * @since 1.1
     */
    @Configuration(proxyBeanMethods = false)
    static class ParamFileAutoRegistration {

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
                            "ParamFileRegistrationCondition matched but no Properties bean found"));
        }

        /**
         * Creates the {@link ParamFileClientRegistrar}.
         *
         * <p>Guarded by {@link ParamFileRegistrationCondition}: only created when
         * a non-Spring {@code Properties} bean exists in the context.
         *
         * @param registry           the HTTP client registry
         * @param tracePropagator    the trace header propagator to inject into clients
         * @param applicationContext the application context for bean lookup
         * @return the registrar; never {@code null}
         */
        @Bean
        @Conditional(ParamFileRegistrationCondition.class)
        public ParamFileClientRegistrar paramFileClientRegistrar(
                HttpClientRegistry registry,
                TraceHeaderPropagator tracePropagator,
                ApplicationContext applicationContext) {

            Properties appProps = findAppProperties(applicationContext);
            log.info("Auto-registering wire-client HTTP clients from .param file");
            return new ParamFileClientRegistrar(registry, appProps, tracePropagator);
        }
    }

    /**
     * Condition that matches when:
     * <ol>
     *   <li>No {@code MonitorTransport} bean exists — when the monitor is present,
     *       {@code WireClientTransport.init()} manages clients and auto-registration
     *       would cause duplicates.</li>
     *   <li>An application {@code Properties} bean exists (excluding Spring internals
     *       like {@code systemProperties} and {@code systemEnvironment}) — this
     *       indicates a {@code PropertiesCfg} has been loaded from a {@code .param}
     *       file and may contain {@code wire.client.*} keys.</li>
     * </ol>
     *
     * <p>The {@code MonitorTransport} type is referenced by FQN string to avoid
     * a compile-time dependency on {@code bedrock-wire-monitor}.
     */
    static class ParamFileRegistrationCondition implements Condition {

        private static final String MONITOR_TRANSPORT_TYPE =
                "cz.syntea.bedrock.wire.monitor.spi.MonitorTransport";

        private static final Set<String> SPRING_INTERNAL_BEANS = Set.of(
                "systemProperties", "systemEnvironment");

        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            if (context.getBeanFactory() == null) {
                return false;
            }

            // 1. Back off if MonitorTransport is present
            try {
                Class<?> transportType = Class.forName(MONITOR_TRANSPORT_TYPE,
                        false, context.getClassLoader());
                if (context.getBeanFactory().getBeanNamesForType(transportType).length > 0) {
                    return false;
                }
            } catch (ClassNotFoundException e) {
                // monitor module not on classpath — no back-off needed
            }

            // 2. Require an application Properties bean
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