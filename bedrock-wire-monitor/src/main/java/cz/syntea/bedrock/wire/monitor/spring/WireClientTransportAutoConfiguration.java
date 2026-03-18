package cz.syntea.bedrock.wire.monitor.spring;

import cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry;
import cz.syntea.bedrock.wire.monitor.config.MonitorConfigProvider;
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
 * <p>Runs before {@link BedrockWireMonitorAutoConfiguration} so that
 * the {@link MonitorTransport} bean is visible to {@code @ConditionalOnBean}
 * checks in the main auto-configuration.
 *
 * <p>Only activates when both conditions are met:
 * <ul>
 *   <li>The monitor is enabled ({@code bedrock.wire.monitor.enabled=true})</li>
 *   <li>An {@link HttpClientRegistry} bean exists in the context
 *       (provided by {@code bedrock-wire-client} auto-configuration)</li>
 * </ul>
 */
@Slf4j
@AutoConfiguration(before = BedrockWireMonitorAutoConfiguration.class)
@ConditionalOnProperty(name = "bedrock.wire.monitor.enabled", havingValue = "true", matchIfMissing = true)
@ConditionalOnBean(HttpClientRegistry.class)
public class WireClientTransportAutoConfiguration {

    /**
     * Registers the default {@link WireClientTransport} using the shared
     * {@link HttpClientRegistry}.
     *
     * @param registry       the shared HTTP client registry
     * @param configProvider the config provider (for TLS profiles)
     * @return the transport
     */
    @Bean
    @ConditionalOnMissingBean(MonitorTransport.class)
    public MonitorTransport monitorTransport(
            HttpClientRegistry registry,
            MonitorConfigProvider configProvider) {

        WireClientTransport transport = new WireClientTransport(registry);
        transport.registerTlsProfiles(configProvider.getTlsProfiles());
        log.info("WireClientTransport registered with shared HttpClientRegistry");
        return transport;
    }
}
