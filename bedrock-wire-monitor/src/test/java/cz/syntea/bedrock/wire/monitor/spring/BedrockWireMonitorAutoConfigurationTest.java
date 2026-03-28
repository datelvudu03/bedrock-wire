package cz.syntea.bedrock.wire.monitor.spring;

import cz.syntea.bedrock.wire.monitor.config.MonitorConfigProvider;
import cz.syntea.bedrock.wire.monitor.engine.MonitorEngine;
import cz.syntea.bedrock.wire.monitor.engine.TemplateProcessor;
import cz.syntea.bedrock.wire.monitor.spi.MonitorTransport;
import cz.syntea.bedrock.wire.monitor.validation.ValidatorRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

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
 */
class BedrockWireMonitorAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    WireClientTransportAutoConfiguration.class,
                    BedrockWireMonitorAutoConfiguration.class));

    @Test
    void shouldRegisterValidatorRegistryBean() {
        contextRunner
                .withPropertyValues(
                        "bedrock.wire.monitor.config-file=src/test/resources/monitor-test.properties")
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
                .withPropertyValues(
                        "bedrock.wire.monitor.config-file=src/test/resources/monitor-test.properties")
                .run(context -> {
                    assertThat(context).hasSingleBean(MonitorConfigProvider.class);
                    MonitorConfigProvider provider = context.getBean(MonitorConfigProvider.class);
                    assertThat(provider.getServices()).isNotEmpty();
                    assertThat(provider.getChecks()).isNotEmpty();
                });
    }

    @Test
    void shouldRegisterTemplateProcessorBean() {
        contextRunner
                .withPropertyValues(
                        "bedrock.wire.monitor.config-file=src/test/resources/monitor-test.properties")
                .run(context -> assertThat(context).hasSingleBean(TemplateProcessor.class));
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
                .withPropertyValues(
                        "bedrock.wire.monitor.config-file=src/test/resources/monitor-test.properties")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(MonitorTransport.class);
                    assertThat(context).doesNotHaveBean(MonitorEngine.class);
                    // Standalone beans still load fine
                    assertThat(context).hasSingleBean(ValidatorRegistry.class);
                    assertThat(context).hasSingleBean(MonitorConfigProvider.class);
                    assertThat(context).hasSingleBean(TemplateProcessor.class);
                });
    }
}