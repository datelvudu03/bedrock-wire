package cz.syntea.bedrock.wire.classic;

import cz.syntea.bedrock.wire.classic.registry.HttpClient;
import cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry;
import cz.syntea.bedrock.wire.classic.spi.MonitorTransport;
import cz.syntea.bedrock.wire.classic.spring.BedrockWireClientAutoConfiguration;
import cz.syntea.bedrock.wire.classic.spring.ParamFileClientRegistrar;
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
 * <p>Validates the three deployment modes:
 * <ol>
 *   <li>Wire-client only (Spring env) → named beans created</li>
 *   <li>Wire-client only (.param fallback) → clients in registry</li>
 *   <li>Wire-client + monitor → auto-registration disabled</li>
 * </ol>
 */
class WireClientAutoRegistrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BedrockWireClientAutoConfiguration.class));

    /**
     * Registers a real {@link MonitorTransport} bean (test stub from
     * {@code cz.syntea.bedrock.wire.monitor.spi.MonitorTransport}).
     *
     * <p>The {@code @ConditionalOnMissingBean(type = "...MonitorTransport")} check
     * resolves by type assignability, so the bean must implement the exact interface.
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
    @DisplayName("Spring Environment auto-registration")
    class SpringEnvironmentRegistration {

        @Test
        @DisplayName("registers named HttpClient beans from properties")
        void registersNamedBeans() {
            contextRunner
                    .withPropertyValues(
                            "bedrock.wire.client.clients.httpbin.base-url=https://httpbin.org",
                            "bedrock.wire.client.clients.httpbin.connect-timeout=3s",
                            "bedrock.wire.client.clients.httpbin.response-timeout=10s")
                    .run(context -> {
                        assertThat(context).hasBean("httpbin");
                        assertThat(context.getBean("httpbin")).isInstanceOf(HttpClient.class);

                        HttpClientRegistry registry = context.getBean(HttpClientRegistry.class);
                        assertThat(registry.containsClient("httpbin")).isTrue();
                    });
        }

        @Test
        @DisplayName("registers multiple clients")
        void registersMultipleClients() {
            contextRunner
                    .withPropertyValues(
                            "bedrock.wire.client.clients.service-a.base-url=https://a.example.com",
                            "bedrock.wire.client.clients.service-b.base-url=https://b.example.com")
                    .run(context -> {
                        assertThat(context).hasBean("service-a");
                        assertThat(context).hasBean("service-b");
                    });
        }

        @Test
        @DisplayName("resolves TLS profile reference — bean definition registered")
        void resolvesTlsProfile() {
            contextRunner
                    .withPropertyValues(
                            "bedrock.wire.client.tls.my-tls.client-cert=/certs/c.p12",
                            "bedrock.wire.client.tls.my-tls.client-cert-password=secret",
                            "bedrock.wire.client.tls.my-tls.client-cert-type=PKCS12",
                            "bedrock.wire.client.clients.secure.base-url=https://secure.example.com",
                            "bedrock.wire.client.clients.secure.tls-profile=my-tls")
                    .run(context -> {
                        // Check bean definition exists without triggering FactoryBean.getObject()
                        // (which would try to load /certs/c.p12 from disk)
                        assertThat(context.getBeanFactory().containsBeanDefinition("secure"))
                                .isTrue();
                    });
        }

        @Test
        @DisplayName("fails on invalid TLS profile reference")
        void failsOnInvalidTlsProfileReference() {
            contextRunner
                    .withPropertyValues(
                            "bedrock.wire.client.clients.broken.base-url=https://example.com",
                            "bedrock.wire.client.clients.broken.tls-profile=nonexistent-tls")
                    .run(context -> {
                        assertThat(context).hasFailed();
                        assertThat(context.getStartupFailure())
                                .rootCause()
                                .isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("nonexistent-tls")
                                .hasMessageContaining("not defined");
                    });
        }

        @Test
        @DisplayName("no beans registered when no clients defined")
        void noBeansWhenEmpty() {
            contextRunner
                    .run(context -> {
                        assertThat(context).hasSingleBean(HttpClientRegistry.class);
                        HttpClientRegistry registry = context.getBean(HttpClientRegistry.class);
                        assertThat(registry.getRegisteredClientIds()).isEmpty();
                    });
        }
    }

    // ── Test configurations ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("Monitor back-off behavior")
    class MonitorBackOff {

        @Test
        @DisplayName("auto-registration disabled when MonitorTransport present")
        void disabledWhenMonitorTransportPresent() {
            contextRunner
                    .withUserConfiguration(MockMonitorTransportConfig.class)
                    .withPropertyValues(
                            "bedrock.wire.client.clients.httpbin.base-url=https://httpbin.org")
                    .run(context -> {
                        // Named bean should NOT be registered
                        assertThat(context).doesNotHaveBean("httpbin");

                        // Registry should have no auto-registered clients
                        HttpClientRegistry registry = context.getBean(HttpClientRegistry.class);
                        assertThat(registry.getRegisteredClientIds()).isEmpty();

                        // MonitorTransport itself should be present
                        assertThat(context).hasBean("monitorTransport");
                    });
        }

        @Test
        @DisplayName("paramFile fallback disabled when MonitorTransport present")
        void paramFileFallbackDisabledWithMonitor() {
            contextRunner
                    .withUserConfiguration(MockMonitorTransportConfig.class)
                    .run(context -> {
                        assertThat(context).doesNotHaveBean(ParamFileClientRegistrar.class);
                    });
        }
    }

    @Nested
    @DisplayName(".param file fallback")
    class ParamFileFallback {

        @Test
        @DisplayName("creates ParamFileClientRegistrar when Properties bean exists and no Spring clients")
        void createsRegistrarWithPropertiesBean() {
            contextRunner
                    .withUserConfiguration(MockPropertiesBeanConfig.class)
                    .run(context -> {
                        assertThat(context).hasSingleBean(ParamFileClientRegistrar.class);
                    });
        }

        @Test
        @DisplayName("skips fallback when Spring Environment clients are defined")
        void skipsFallbackWhenSpringClientsExist() {
            contextRunner
                    .withUserConfiguration(MockPropertiesBeanConfig.class)
                    .withPropertyValues(
                            "bedrock.wire.client.clients.httpbin.base-url=https://httpbin.org")
                    .run(context -> {
                        assertThat(context).doesNotHaveBean(ParamFileClientRegistrar.class);
                        assertThat(context).hasBean("httpbin");
                    });
        }
    }

}