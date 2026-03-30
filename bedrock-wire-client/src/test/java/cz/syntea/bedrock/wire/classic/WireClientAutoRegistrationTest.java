package cz.syntea.bedrock.wire.classic;

import cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry;
import cz.syntea.bedrock.wire.classic.spring.BedrockWireClientAutoConfiguration;
import cz.syntea.bedrock.wire.classic.spring.ParamFileClientRegistrar;
import cz.syntea.bedrock.wire.monitor.spi.MonitorTransport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for wire-client auto-registration behavior using {@link ApplicationContextRunner}.
 *
 * <p>Validates the two deployment modes:
 * <ol>
 *   <li>Wire-client standalone — clients registered from {@code .param} file
 *       via {@link ParamFileClientRegistrar}</li>
 *   <li>Wire-client + monitor — auto-registration disabled (monitor manages clients)</li>
 * </ol>
 */
class WireClientAutoRegistrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BedrockWireClientAutoConfiguration.class));

    /**
     * Registers a {@link MonitorTransport} bean at the exact FQN checked by
     * {@code @ConditionalOnMissingBean(type = "cz.syntea.bedrock.wire.monitor.spi.MonitorTransport")}.
     */
    @Configuration(proxyBeanMethods = false)
    static class MockMonitorTransportConfig {

        @Bean("monitorTransport")
        MonitorTransport monitorTransport() {
            return new MonitorTransport() {
            };
        }
    }

    /**
     * Simulates a PropertiesCfg bean from a .param file.
     */
    @Configuration(proxyBeanMethods = false)
    static class MockPropertiesBeanConfig {

        @Bean("cfg")
        java.util.Properties appProperties() {
            java.util.Properties props = new java.util.Properties();
            props.setProperty("wire.client.testservice.url", "https://test.example.com");
            props.setProperty("wire.client.testservice.responseTimeout", "5s");
            return props;
        }
    }

    @Nested
    @DisplayName("Core beans")
    class CoreBeans {

        @Test
        @DisplayName("registers HttpClientRegistry even without .param file")
        void registersRegistry() {
            contextRunner
                    .run(context -> {
                        assertThat(context).hasSingleBean(HttpClientRegistry.class);
                        HttpClientRegistry registry = context.getBean(HttpClientRegistry.class);
                        assertThat(registry.getRegisteredClientIds()).isEmpty();
                    });
        }
    }

    @Nested
    @DisplayName(".param file auto-registration")
    class ParamFileRegistration {

        @Test
        @DisplayName("creates ParamFileClientRegistrar when Properties bean exists")
        void createsRegistrar() {
            contextRunner
                    .withUserConfiguration(MockPropertiesBeanConfig.class)
                    .run(context -> {
                        assertThat(context).hasSingleBean(ParamFileClientRegistrar.class);

                        HttpClientRegistry registry = context.getBean(HttpClientRegistry.class);
                        assertThat(registry.containsClient("testservice")).isTrue();
                    });
        }

        @Test
        @DisplayName("no registrar when no Properties bean")
        void noRegistrarWithoutPropertiesBean() {
            contextRunner
                    .run(context -> {
                        assertThat(context).doesNotHaveBean(ParamFileClientRegistrar.class);
                    });
        }
    }

    @Nested
    @DisplayName("Monitor back-off behavior")
    class MonitorBackOff {

        @Test
        @DisplayName("auto-registration disabled when MonitorTransport present")
        void disabledWhenMonitorTransportPresent() {
            contextRunner
                    .withUserConfiguration(MockMonitorTransportConfig.class,
                            MockPropertiesBeanConfig.class)
                    .run(context -> {
                        // ParamFileClientRegistrar should NOT be created
                        assertThat(context).doesNotHaveBean(ParamFileClientRegistrar.class);

                        // Registry should have no auto-registered clients
                        HttpClientRegistry registry = context.getBean(HttpClientRegistry.class);
                        assertThat(registry.getRegisteredClientIds()).isEmpty();

                        // MonitorTransport itself should be present
                        assertThat(context).hasBean("monitorTransport");
                    });
        }
    }

}