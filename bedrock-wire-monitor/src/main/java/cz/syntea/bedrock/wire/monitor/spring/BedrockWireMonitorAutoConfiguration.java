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
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.util.List;


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
     * Registers the default {@link MonitorConfigProvider} that reads the
     * {@code monitor.*} namespace from the configured {@code .param} file.
     *
     * @param properties        the monitor properties
     * @param validatorRegistry the validator registry (for alias validation)
     * @return the config provider
     */
    @Bean
    @ConditionalOnMissingBean
    public MonitorConfigProvider monitorConfigProvider(
            BedrockWireMonitorProperties properties,
            ValidatorRegistry validatorRegistry) {

        String configFile = properties.getConfigFile();
        if (configFile == null || configFile.isBlank()) {
            throw new IllegalStateException(
                    "Property 'bedrock.wire.monitor.config-file' is required but not set. "
                            + "Set it to the path of your .param/.properties file "
                            + "(e.g. --app.configFile=src/cfg/myapp.param).");
        }

        Path path = Path.of(configFile);
        log.info("Loading monitor configuration from: {}", path.toAbsolutePath());
        return new PropertiesFileConfigProvider(path, validatorRegistry.getAliases());
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
     * @param configProvider    the config provider
     * @param transport         the transport
     * @param validatorRegistry the validator registry
     * @param templateProcessor the template processor
     * @param listeners         all registered result listeners
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
            ObjectProvider<List<MonitorResultListener>> listeners) {

        List<MonitorResultListener> listenerList = listeners.getIfAvailable();
        return new MonitorEngine(
                configProvider,
                transport,
                validatorRegistry,
                templateProcessor,
                listenerList != null ? listenerList : List.of()
        );
    }
}