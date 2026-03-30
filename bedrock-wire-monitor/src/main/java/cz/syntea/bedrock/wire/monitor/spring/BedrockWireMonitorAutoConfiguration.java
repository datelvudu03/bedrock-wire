package cz.syntea.bedrock.wire.monitor.spring;

import cz.syntea.bedrock.wire.monitor.config.MonitorConfigProvider;
import cz.syntea.bedrock.wire.monitor.config.PropertiesFileConfigProvider;
import cz.syntea.bedrock.wire.monitor.engine.MonitorEngine;
import cz.syntea.bedrock.wire.monitor.engine.TemplateProcessor;
import cz.syntea.bedrock.wire.monitor.listener.MonitorResultListener;
import cz.syntea.bedrock.wire.monitor.spi.MonitorTransport;
import cz.syntea.bedrock.wire.monitor.validation.Validator;
import cz.syntea.bedrock.wire.monitor.validation.ValidatorRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.context.event.EventListener;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;


/**
 * Spring Boot auto-configuration for {@code bedrock-wire-monitor}.
 *
 * <p>Registers the following beans when the monitor is enabled
 * ({@code bedrock.wire.monitor.enabled=true}, which is the default):
 * <ul>
 *   <li>{@link ValidatorRegistry} — pre-loaded with built-in validators plus
 *       any custom {@link Validator} beans from the context.</li>
 *   <li>{@link MonitorConfigProvider} — default implementation reading from
 *       the {@code .param} file specified by {@code bedrock.wire.monitor.config-file}.</li>
 *   <li>{@link MonitorTransport} — default {@link cz.syntea.bedrock.wire.monitor.transport.WireClientTransport}
 *       registered by {@link WireClientTransportAutoConfiguration} when
 *       {@code HttpClientRegistry} is available.</li>
 *   <li>{@link TemplateProcessor} — template loading and variable substitution.</li>
 *   <li>{@link MonitorEngine} — the central orchestrator implementing
 *       {@link org.springframework.context.SmartLifecycle}.</li>
 * </ul>
 *
 * <h3>Customization</h3>
 * Declare your own {@code @Bean} of any of the above types to override defaults.
 * For example, provide a custom {@link MonitorTransport} to replace the
 * wire-client-based default.
 *
 * <h3>Disabling</h3>
 * Set {@code bedrock.wire.monitor.enabled=false} to disable the entire monitor.
 */
@Slf4j
@AutoConfiguration
@EnableConfigurationProperties(BedrockWireMonitorProperties.class)
@ConditionalOnProperty(name = "bedrock.wire.monitor.enabled", havingValue = "true", matchIfMissing = true)
public class BedrockWireMonitorAutoConfiguration {

    /**
     * Registers the {@link ValidatorRegistry} with built-in validators and any
     * custom {@link Validator} beans from the application context.
     *
     * @param customValidators custom validators provided by the application; may be empty
     * @return the validator registry
     */
    @Bean
    @ConditionalOnMissingBean
    public ValidatorRegistry validatorRegistry(ObjectProvider<List<Validator>> customValidators) {
        List<Validator> custom = customValidators.getIfAvailable();
        ValidatorRegistry registry = new ValidatorRegistry(custom);
        log.info("ValidatorRegistry created with aliases: {}", registry.getAliases());
        return registry;
    }

    /**
     * Bean names of Spring-internal {@link Properties} instances that must be
     * excluded when auto-detecting the application's configuration bean.
     */
    private static final Set<String> SPRING_INFRASTRUCTURE_PROPERTIES = Set.of(
            "systemProperties", "systemEnvironment"
    );

    /**
     * Registers the default {@link MonitorConfigProvider} that reads the
     * {@code monitor.*} namespace.
     *
     * <h3>Configuration source resolution (priority order)</h3>
     * <ol>
     *   <li>Auto-detection: the context is scanned for {@link Properties} beans.
     *       Spring infrastructure beans ({@code systemProperties},
     *       {@code systemEnvironment}) are excluded. If exactly one application
     *       {@code Properties} bean remains (e.g. a {@code PropertiesCfg} loaded
     *       from {@code --app.configFile}), it is used directly — no extra
     *       annotation or property is needed on the application side.</li>
     *   <li>Fallback: if no suitable bean is found (or multiple candidates exist),
     *       the {@code bedrock.wire.monitor.config-file} property is used to locate
     *       and parse a standalone {@code .properties} file.</li>
     * </ol>
     *
     * @param properties        the monitor starter properties
     * @param validatorRegistry the validator registry (for alias validation)
     * @param applicationContext the Spring application context
     * @return the config provider
     */
    @Bean
    @ConditionalOnMissingBean
    public MonitorConfigProvider monitorConfigProvider(
            BedrockWireMonitorProperties properties,
            ValidatorRegistry validatorRegistry,
            ApplicationContext applicationContext) {

        // 1. Auto-detect: find application Properties beans, skip Spring infrastructure
        Properties detected = detectApplicationProperties(applicationContext);
        if (detected != null) {
            log.info("Auto-detected monitor configuration from Properties bean: {} (class: {})",
                    getBeanName(applicationContext, detected), detected.getClass().getSimpleName());
            return new PropertiesFileConfigProvider(detected, validatorRegistry.getAliases());
        }

        // 2. Fall back to bedrock.wire.monitor.config-file
        String configFile = properties.getConfigFile();
        if (configFile == null || configFile.isBlank()) {
            throw new IllegalStateException(
                    "No application Properties bean detected in the context and "
                            + "'bedrock.wire.monitor.config-file' is not set. "
                            + "Either register a Properties bean (e.g. PropertiesCfg) containing "
                            + "the monitor.* namespace, or set bedrock.wire.monitor.config-file "
                            + "to the path of your .param/.properties file.");
        }

        Path path = Path.of(configFile);
        log.info("Loading monitor configuration from file: {}", path.toAbsolutePath());
        return new PropertiesFileConfigProvider(path, validatorRegistry.getAliases());
    }

    /**
     * Scans the application context for {@link Properties} beans, filtering out
     * Spring infrastructure beans. Returns the single application bean if exactly
     * one is found, or {@code null} otherwise.
     */
    private Properties detectApplicationProperties(ApplicationContext context) {
        Map<String, Properties> allBeans = context.getBeansOfType(Properties.class);

        List<Map.Entry<String, Properties>> candidates = allBeans.entrySet().stream()
                .filter(e -> !SPRING_INFRASTRUCTURE_PROPERTIES.contains(e.getKey()))
                .toList();

        if (candidates.size() == 1) {
            return candidates.get(0).getValue();
        }

        if (candidates.size() > 1) {
            List<String> names = candidates.stream().map(Map.Entry::getKey).toList();
            log.warn("Multiple application Properties beans found: {}. "
                    + "Cannot auto-detect; falling back to bedrock.wire.monitor.config-file. "
                    + "To resolve, either keep only one Properties bean or set "
                    + "bedrock.wire.monitor.config-file explicitly.", names);
        }

        return null;
    }

    /**
     * Resolves the bean name for a given instance (for logging purposes).
     */
    private String getBeanName(ApplicationContext context, Properties bean) {
        return context.getBeansOfType(Properties.class).entrySet().stream()
                .filter(e -> e.getValue() == bean)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("unknown");
    }

    /**
     * Registers the {@link TemplateProcessor}.
     *
     * @return the template processor
     */
    @Bean
    @ConditionalOnMissingBean
    public TemplateProcessor templateProcessor() {
        return new TemplateProcessor();
    }

    /**
     * Registers the {@link MonitorEngine} orchestrator.
     *
     * <p>Implements {@link org.springframework.context.SmartLifecycle} for
     * auto-start on context refresh and graceful stop on context close.
     * Also exposes programmatic {@code start()} / {@code stop()}.
     *
     * <p>Shutdown timeout resolution: if the config provider is a
     * {@link PropertiesFileConfigProvider}, its parsed {@code monitor.executor.shutdownTimeout}
     * value is used. Otherwise, {@code bedrock.wire.monitor.shutdown-timeout} from
     * Spring properties is used (default: 30s).
     *
     * @param configProvider    the config provider
     * @param transport         the transport
     * @param validatorRegistry the validator registry
     * @param templateProcessor the template processor
     * @param listeners         all registered result listeners
     * @param properties        the monitor starter properties (for shutdown timeout fallback)
     * @return the monitor engine
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(MonitorTransport.class)
    public MonitorEngine monitorEngine(
            MonitorConfigProvider configProvider,
            MonitorTransport transport,
            ValidatorRegistry validatorRegistry,
            TemplateProcessor templateProcessor,
            ObjectProvider<List<MonitorResultListener>> listeners,
            BedrockWireMonitorProperties properties) {

        // Resolve shutdown timeout: .param file value > Spring property > default
        java.time.Duration shutdownTimeout = properties.getShutdownTimeout();
        if (configProvider instanceof PropertiesFileConfigProvider concreteProvider) {
            shutdownTimeout = concreteProvider.getShutdownTimeout();
        }

        List<MonitorResultListener> listenerList = listeners.getIfAvailable();
        return new MonitorEngine(
                configProvider,
                transport,
                validatorRegistry,
                templateProcessor,
                listenerList != null ? listenerList : List.of(),
                shutdownTimeout
        );
    }

    // ── Startup diagnostics ─────────────────────────────────────────────────

    /**
     * Checks at startup whether critical beans are present and logs
     * actionable diagnostics if they are missing.
     *
     * <p>Fires on {@link ContextRefreshedEvent}, after all beans are created
     * and conditional evaluations are complete. This catches the silent failure
     * where config loads successfully but the transport/engine chain is broken.
     */
    @EventListener(ContextRefreshedEvent.class)
    public void onStartupDiagnostics(ContextRefreshedEvent event) {
        ApplicationContext context = event.getApplicationContext();

        boolean hasTransport = !context.getBeansOfType(MonitorTransport.class).isEmpty();
        boolean hasEngine = !context.getBeansOfType(MonitorEngine.class).isEmpty();
        boolean hasConfig = !context.getBeansOfType(MonitorConfigProvider.class).isEmpty();

        if (hasConfig && !hasTransport) {
            log.error("╔══════════════════════════════════════════════════════════════╗");
            log.error("║  MONITOR CONFIGURATION LOADED BUT NO TRANSPORT AVAILABLE    ║");
            log.error("╠══════════════════════════════════════════════════════════════╣");
            log.error("║  Monitor checks will NOT run.                               ║");
            log.error("║                                                              ║");
            log.error("║  Cause: No MonitorTransport bean found in the context.       ║");
            log.error("║  The default WireClientTransport requires:                   ║");
            log.error("║    1. syntea-bedrock-wire-client on the classpath             ║");
            log.error("║    2. HttpClientRegistry bean (auto-configured by            ║");
            log.error("║       BedrockWireClientAutoConfiguration)                    ║");
            log.error("║                                                              ║");
            log.error("║  Fix: Add to your pom.xml:                                   ║");
            log.error("║    <dependency>                                               ║");
            log.error("║      <groupId>cz.syntea.bedrock</groupId>                    ║");
            log.error("║      <artifactId>syntea-bedrock-wire-client</artifactId>      ║");
            log.error("║    </dependency>                                              ║");
            log.error("║                                                              ║");
            log.error("║  Or provide a custom MonitorTransport bean.                  ║");
            log.error("╚══════════════════════════════════════════════════════════════╝");
        } else if (hasConfig && hasTransport && !hasEngine) {
            log.error("MonitorTransport is available but MonitorEngine was not created. "
                    + "Check for bean creation errors in the log above.");
        }
    }
}