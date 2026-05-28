package cz.syntea.bedrock.wire.monitor.spring;

import cz.syntea.bedrock.wire.monitor.config.MonitorConfigProvider;
import cz.syntea.bedrock.wire.monitor.config.PropertiesFileConfigProvider;
import cz.syntea.bedrock.wire.monitor.engine.MonitorEngine;
import cz.syntea.bedrock.wire.monitor.listener.MonitorResultListener;
import cz.syntea.bedrock.wire.monitor.spi.MonitorTransport;
import cz.syntea.bedrock.wire.monitor.validation.Validator;
import cz.syntea.bedrock.wire.monitor.validation.ValidatorRegistry;
import cz.syntea.bedrock.wire.template.TemplateRenderer;
import cz.syntea.bedrock.wire.template.autoconfigure.TemplateAutoConfiguration;
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
import org.springframework.core.env.Environment;

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
 *       the {@code .param} file specified by {@code bedrock.wire.monitor.config-file},
 *       or auto-detected from a {@link Properties} bean in the context.</li>
 *   <li>{@link MonitorTransport} — default
 *       {@link cz.syntea.bedrock.wire.monitor.transport.WireClientTransport}
 *       registered by {@link WireClientTransportAutoConfiguration} when
 *       {@code HttpClientRegistry} is available.</li>
 *   <li>{@link MonitorEngine} — the central orchestrator implementing
 *       {@link org.springframework.context.SmartLifecycle}.</li>
 * </ul>
 *
 * <h3>Template-context model (1.0.6.0)</h3>
 * The auto-configuration injects the Spring {@link Environment} into the default
 * provider so that layer 1 of the v3 template-context model (spec §2.9) is
 * populated. Per-level {@code springEnvPrefix} keys in the {@code .param} file
 * control which subset of the Spring environment is exposed; default is no filter
 * (every Spring property exposed — see README "Template context" section).
 *
 * <h3>Path resolution (1.0.6.0)</h3>
 * When the provider is constructed from a file path, the file's parent directory
 * becomes the {@code configFileRoot} for resolving relative TLS, {@code config-file},
 * and {@code templateFile} paths. When auto-detected from a {@link Properties} bean,
 * the root is derived from {@code bedrock.wire.monitor.config-file} if set; otherwise
 * paths fall back to the JVM working directory (with a WARN logged by the provider).
 */
@Slf4j
@AutoConfiguration(after = TemplateAutoConfiguration.class)
@EnableConfigurationProperties(BedrockWireMonitorProperties.class)
@ConditionalOnProperty(name = "bedrock.wire.monitor.enabled", havingValue = "true",
        matchIfMissing = true)
public class BedrockWireMonitorAutoConfiguration {

    private static final Set<String> SPRING_INFRASTRUCTURE_PROPERTIES = Set.of(
            "systemProperties", "systemEnvironment"
    );

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
     * Registers the default {@link MonitorConfigProvider}.
     *
     * <h3>Configuration source resolution</h3>
     * <ol>
     *   <li>Auto-detection: scan for application {@link Properties} beans, skipping
     *       Spring infrastructure. If exactly one is found, use it. The
     *       {@code configFileRoot} is derived from
     *       {@code bedrock.wire.monitor.config-file} when set; otherwise null
     *       (JVM working dir fallback with WARN).</li>
     *   <li>Fallback: {@code bedrock.wire.monitor.config-file} → file-based load.
     *       The file's parent directory becomes the {@code configFileRoot}.</li>
     * </ol>
     *
     * <p>The Spring {@link Environment} is always passed to the provider to enable
     * layer 1 of the template-context model.
     *
     * @param properties         the monitor starter properties
     * @param validatorRegistry  the validator registry (for alias validation)
     * @param applicationContext the Spring application context
     * @param environment        the Spring environment (layer 1 of template model)
     * @return the config provider
     */
    @Bean
    @ConditionalOnMissingBean
    public MonitorConfigProvider monitorConfigProvider(
            BedrockWireMonitorProperties properties,
            ValidatorRegistry validatorRegistry,
            ApplicationContext applicationContext,
            Environment environment) {

        Properties detected = detectApplicationProperties(applicationContext);
        if (detected != null) {
            Path inferredRoot = inferConfigFileRoot(properties);
            log.info("Auto-detected monitor configuration from Properties bean: {} "
                            + "(class: {}, configFileRoot: {})",
                    getBeanName(applicationContext, detected),
                    detected.getClass().getSimpleName(),
                    inferredRoot);
            return PropertiesFileConfigProvider.builder()
                    .props(detected)
                    .validatorAliases(validatorRegistry.getAliases())
                    .configFileRoot(inferredRoot)
                    .environment(environment)
                    .build();
        }

        String configFile = properties.getConfigFile();
        if (configFile == null || configFile.isBlank()) {
            throw new IllegalStateException(
                    "No application Properties bean detected in the context and "
                            + "'bedrock.wire.monitor.config-file' is not set. "
                            + "Either register a Properties bean (e.g. PropertiesCfg) "
                            + "containing the monitor.* namespace, or set "
                            + "bedrock.wire.monitor.config-file to the path of your "
                            + ".param/.properties file.");
        }

        Path path = Path.of(configFile);
        log.info("Loading monitor configuration from file: {}", path.toAbsolutePath());
        Properties fileProps = loadPropertiesOrThrow(path);
        return PropertiesFileConfigProvider.builder()
                .props(fileProps)
                .validatorAliases(validatorRegistry.getAliases())
                .configFileRoot(path.toAbsolutePath().getParent())
                .environment(environment)
                .build();
    }

    private Path inferConfigFileRoot(BedrockWireMonitorProperties properties) {
        String configFile = properties.getConfigFile();
        if (configFile == null || configFile.isBlank()) {
            return null;
        }
        return Path.of(configFile).toAbsolutePath().getParent();
    }

    private Properties loadPropertiesOrThrow(Path path) {
        if (!java.nio.file.Files.exists(path)) {
            throw new IllegalArgumentException("Config file does not exist: " + path);
        }
        Properties props = new Properties();
        try (var is = java.nio.file.Files.newInputStream(path)) {
            props.load(is);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException("Failed to load config file: " + path, e);
        }
        return props;
    }

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

    private String getBeanName(ApplicationContext context, Properties bean) {
        return context.getBeansOfType(Properties.class).entrySet().stream()
                .filter(e -> e.getValue() == bean)
                .map(Map.Entry::getKey)
                .findFirst()
                .orElse("unknown");
    }

    /**
     * Registers the {@link MonitorEngine} orchestrator.
     *
     * @param configProvider           the config provider
     * @param transport                the transport
     * @param validatorRegistry        the validator registry
     * @param templateRendererProvider provider for the optional {@link TemplateRenderer} bean
     * @param listeners                all registered result listeners
     * @param properties               the monitor starter properties (shutdown timeout fallback)
     * @return the monitor engine
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(MonitorTransport.class)
    public MonitorEngine monitorEngine(
            MonitorConfigProvider configProvider,
            MonitorTransport transport,
            ValidatorRegistry validatorRegistry,
            ObjectProvider<TemplateRenderer> templateRendererProvider,
            ObjectProvider<List<MonitorResultListener>> listeners,
            BedrockWireMonitorProperties properties) {

        java.time.Duration shutdownTimeout = properties.getShutdownTimeout();
        if (configProvider instanceof PropertiesFileConfigProvider concreteProvider) {
            shutdownTimeout = concreteProvider.getShutdownTimeout();
        }

        TemplateRenderer templateRenderer = templateRendererProvider
                .getIfAvailable(TemplateRenderer::create);
        log.info("MonitorEngine using TemplateRenderer: {}",
                templateRendererProvider.getIfAvailable() != null
                        ? "context bean" : "internal default");

        List<MonitorResultListener> listenerList = listeners.getIfAvailable();
        return new MonitorEngine(
                configProvider,
                transport,
                validatorRegistry,
                templateRenderer,
                listenerList != null ? listenerList : List.of(),
                shutdownTimeout
        );
    }

    /**
     * Fires on {@link ContextRefreshedEvent} to catch the silent failure where config
     * loads successfully but the transport/engine chain is broken.
     *
     * @param event Spring context-refreshed event; never {@code null}
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