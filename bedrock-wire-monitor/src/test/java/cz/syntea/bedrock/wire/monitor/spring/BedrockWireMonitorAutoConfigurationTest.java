package cz.syntea.bedrock.wire.monitor.spring;

import cz.syntea.bedrock.wire.monitor.config.MonitorConfigProvider;
import cz.syntea.bedrock.wire.monitor.config.ServiceConfig;
import cz.syntea.bedrock.wire.monitor.engine.MonitorEngine;
import cz.syntea.bedrock.wire.monitor.spi.MonitorRequest;
import cz.syntea.bedrock.wire.monitor.spi.MonitorResult;
import cz.syntea.bedrock.wire.monitor.spi.MonitorTransport;
import cz.syntea.bedrock.wire.monitor.spi.TransportStatus;
import cz.syntea.bedrock.wire.monitor.validation.ValidatorRegistry;
import cz.syntea.bedrock.wire.template.TemplateRenderer;
import cz.syntea.bedrock.wire.template.autoconfigure.TemplateAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link BedrockWireMonitorAutoConfiguration}.
 *
 * <p>Uses {@link ApplicationContextRunner} for lightweight context testing
 * without starting a full Spring Boot application.
 *
 * <p>Note: {@link WireClientTransportAutoConfiguration} is loaded but will NOT
 * activate because there is no {@code HttpClientRegistry} bean in the test context
 * (class-level {@code @ConditionalOnBean(HttpClientRegistry.class)} fails).
 * For tests that need {@link MonitorEngine} construction, {@link StubTransportConfig}
 * provides a no-op {@link MonitorTransport} bean.
 */
class BedrockWireMonitorAutoConfigurationTest {

    private static final String CONFIG_FILE_PROP =
            "bedrock.wire.monitor.config-file=src/test/resources/monitor-test.properties";

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WireClientTransportAutoConfiguration.class,
                    BedrockWireMonitorAutoConfiguration.class));

    @Test
    void shouldRegisterValidatorRegistryBean() {
        contextRunner
                .withPropertyValues(CONFIG_FILE_PROP)
                .run(context -> {
                    assertThat(context).hasSingleBean(ValidatorRegistry.class);
                    ValidatorRegistry registry = context.getBean(ValidatorRegistry.class);
                    assertThat(registry.getAliases()).contains(
                            "httpStatus", "contains", "regex", "maxDuration", "xpath");
                });
    }

    @Test
    void shouldRegisterConfigProviderBean() {
        contextRunner
                .withPropertyValues(CONFIG_FILE_PROP)
                .run(context -> {
                    assertThat(context).hasSingleBean(MonitorConfigProvider.class);
                    MonitorConfigProvider provider = context.getBean(MonitorConfigProvider.class);
                    assertThat(provider.getServices()).isNotEmpty();
                    assertThat(provider.getChecks()).isNotEmpty();
                });
    }

    @Test
    void shouldNotLoadWhenDisabled() {
        contextRunner
                .withPropertyValues("bedrock.wire.monitor.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ValidatorRegistry.class);
                    assertThat(context).doesNotHaveBean(MonitorConfigProvider.class);
                    assertThat(context).doesNotHaveBean(MonitorEngine.class);
                });
    }

    @Test
    void shouldFailWhenConfigFileMissing() {
        contextRunner
                .withPropertyValues("bedrock.wire.monitor.config-file=")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldFailWhenConfigFileDoesNotExist() {
        contextRunner
                .withPropertyValues(
                        "bedrock.wire.monitor.config-file=/nonexistent/file.properties")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void shouldNotRegisterTransportOrEngineWithoutRegistry() {
        // No HttpClientRegistry bean → WireClientTransportAutoConfiguration skipped
        // (class-level @ConditionalOnBean) → no MonitorTransport →
        // MonitorEngine skipped (@ConditionalOnBean(MonitorTransport.class))
        contextRunner
                .withPropertyValues(CONFIG_FILE_PROP)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MonitorTransport.class);
                    assertThat(context).doesNotHaveBean(MonitorEngine.class);
                    // Standalone beans still load fine
                    assertThat(context).hasSingleBean(ValidatorRegistry.class);
                    assertThat(context).hasSingleBean(MonitorConfigProvider.class);
                });
    }

    @Test
    void shouldUseTemplateRendererBeanWhenAvailable() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(TemplateAutoConfiguration.class))
                .withUserConfiguration(StubTransportConfig.class)
                .withPropertyValues(CONFIG_FILE_PROP)
                .run(context -> {
                    assertThat(context).hasSingleBean(TemplateRenderer.class);
                    assertThat(context).hasSingleBean(MonitorEngine.class);
                });
    }

    @Test
    void shouldConstructEngineWithFallbackTemplateRendererWhenNoTemplateAutoConfig() {
        // No TemplateAutoConfiguration loaded → no TemplateRenderer bean in context.
        // BedrockWireMonitorAutoConfiguration must still construct MonitorEngine
        // by falling back to TemplateRenderer.create() via ObjectProvider.
        contextRunner
                .withUserConfiguration(StubTransportConfig.class)
                .withPropertyValues(CONFIG_FILE_PROP)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(TemplateRenderer.class);
                    assertThat(context).hasSingleBean(MonitorEngine.class);
                });
    }

    /**
     * Provides a no-op {@link MonitorTransport} bean so the {@link MonitorEngine}
     * conditional ({@code @ConditionalOnBean(MonitorTransport.class)}) is satisfied
     * without requiring the full {@code HttpClientRegistry} chain.
     */
    @Configuration
    static class StubTransportConfig {

        /**
         * No-op transport for context tests.
         *
         * @return a stub {@link MonitorTransport}
         */
        @Bean
        MonitorTransport monitorTransport() {
            return new MonitorTransport() {
                @Override
                public void init(List<ServiceConfig> services) {
                    // no-op
                }

                @Override
                public MonitorResult execute(MonitorRequest request) {
                    return MonitorResult.builder()
                            .transportStatus(TransportStatus.IO_ERROR)
                            .httpStatus(0)
                            .errorMessage("stub")
                            .build();
                }

                @Override
                public void close(Duration timeout) {
                    // no-op
                }
            };
        }
    }
}