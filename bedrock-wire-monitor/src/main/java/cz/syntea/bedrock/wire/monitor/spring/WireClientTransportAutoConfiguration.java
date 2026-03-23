package cz.syntea.bedrock.wire.monitor.spring;

import cz.syntea.bedrock.wire.classic.config.BedrockWireClientAutoConfiguration;
import cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry;
import cz.syntea.bedrock.wire.monitor.config.MonitorConfigProvider;
import cz.syntea.bedrock.wire.monitor.config.PropertiesFileConfigProvider;
import cz.syntea.bedrock.wire.monitor.spi.MonitorTransport;
import cz.syntea.bedrock.wire.monitor.transport.WireClientTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for the default {@link WireClientTransport}.
 *
 * <p>Ordering: runs <strong>after</strong> {@link BedrockWireClientAutoConfiguration}
 * (so that {@link HttpClientRegistry} bean exists) and <strong>before</strong>
 * {@link BedrockWireMonitorAutoConfiguration} (so that the {@link MonitorTransport}
 * bean is visible to {@code @ConditionalOnBean} checks in the main auto-configuration).
 *
 * <p>Only activates when both conditions are met:
 * <ul>
 *   <li>The monitor is enabled ({@code bedrock.wire.monitor.enabled=true})</li>
 *   <li>An {@link HttpClientRegistry} bean exists in the context
 *       (provided by {@code bedrock-wire-client} auto-configuration)</li>
 * </ul>
 *
 * <p>TLS profiles are registered from the concrete {@link PropertiesFileConfigProvider}
 * if available. This keeps TLS configuration out of the transport-agnostic
 * {@code MonitorConfigProvider} interface.
 */
@Slf4j
@AutoConfiguration(
        after = BedrockWireClientAutoConfiguration.class,
        before = BedrockWireMonitorAutoConfiguration.class
)
@ConditionalOnProperty(name = "bedrock.wire.monitor.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(HttpClientRegistry.class)
public class WireClientTransportAutoConfiguration {

    /**
     * Registers the default {@link WireClientTransport} using the shared
     * {@link HttpClientRegistry}.
     *
     * <p>TLS profiles are loaded from the {@link PropertiesFileConfigProvider}
     * if it is the active config provider. If a custom {@code MonitorConfigProvider}
     * is in use, TLS profile registration is skipped (the custom transport is
     * expected to handle TLS independently).
     *
     * @param registry       the shared HTTP client registry
     * @param configProvider the monitor config provider
     * @return the transport
     */
    @Bean
    @ConditionalOnMissingBean(MonitorTransport.class)
    public MonitorTransport monitorTransport(
            HttpClientRegistry registry,
            MonitorConfigProvider configProvider) {

        WireClientTransport transport = new WireClientTransport(registry);

        if (configProvider instanceof PropertiesFileConfigProvider concreteProvider) {
            transport.registerTlsProfiles(concreteProvider.getTlsProfiles());
        } else {
            log.warn("Config provider is not PropertiesFileConfigProvider ({}); "
                            + "TLS profiles not registered. If your checks require mTLS, "
                            + "ensure TLS is configured by the transport.",
                    configProvider.getClass().getSimpleName());
        }

        log.info("WireClientTransport registered with shared HttpClientRegistry");
        return transport;
    }
}