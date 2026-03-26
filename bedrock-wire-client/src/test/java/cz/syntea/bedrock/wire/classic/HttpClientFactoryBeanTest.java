package cz.syntea.bedrock.wire.classic;

import cz.syntea.bedrock.wire.classic.config.HttpClientConfig;
import cz.syntea.bedrock.wire.classic.model.TlsConfig;
import cz.syntea.bedrock.wire.classic.observability.TraceHeaderPropagator;
import cz.syntea.bedrock.wire.classic.registry.HttpClient;
import cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry;
import cz.syntea.bedrock.wire.classic.spring.ClientProperties;
import cz.syntea.bedrock.wire.classic.spring.HttpClientFactoryBean;
import cz.syntea.bedrock.wire.classic.spring.TlsProfileProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link HttpClientFactoryBean}.
 */
@ExtendWith(MockitoExtension.class)
class HttpClientFactoryBeanTest {

    @Mock
    private HttpClientRegistry registry;

    @Mock
    private HttpClient mockClient;

    @Mock
    private TraceHeaderPropagator mockPropagator;

    private ClientProperties clientProperties;

    @BeforeEach
    void setUp() {
        clientProperties = new ClientProperties();
        clientProperties.setBaseUrl("https://example.com");
    }

    @Test
    @DisplayName("creates client via registry on getObject()")
    void createsClientViaRegistry() throws Exception {
        when(registry.get(any(HttpClientConfig.class))).thenReturn(mockClient);

        HttpClientFactoryBean factory = new HttpClientFactoryBean("payments", clientProperties, null);
        factory.setRegistry(registry);

        HttpClient result = factory.getObject();

        assertThat(result).isSameAs(mockClient);
        ArgumentCaptor<HttpClientConfig> captor = ArgumentCaptor.forClass(HttpClientConfig.class);
        verify(registry).get(captor.capture());
        assertThat(captor.getValue().getClientId()).isEqualTo("payments");
    }

    @Test
    @DisplayName("registers TLS profile before creating client")
    void registersTlsBeforeClient() throws Exception {
        when(registry.get(any(HttpClientConfig.class))).thenReturn(mockClient);

        TlsProfileProperties tlsProps = new TlsProfileProperties();
        tlsProps.setClientCert("/certs/client.p12");
        tlsProps.setClientCertPassword("secret");
        tlsProps.setClientCertType("PKCS12");

        clientProperties.setTlsProfile("my-tls");

        HttpClientFactoryBean factory = new HttpClientFactoryBean(
                "secure", clientProperties, tlsProps);
        factory.setRegistry(registry);

        factory.getObject();

        ArgumentCaptor<TlsConfig> tlsCaptor = ArgumentCaptor.forClass(TlsConfig.class);
        verify(registry).registerTlsConfig(tlsCaptor.capture());
        assertThat(tlsCaptor.getValue().getConfigName()).isEqualTo("my-tls");
    }

    @Test
    @DisplayName("does not register TLS when no profile configured")
    void noTlsRegistrationWithoutProfile() throws Exception {
        when(registry.get(any(HttpClientConfig.class))).thenReturn(mockClient);

        HttpClientFactoryBean factory = new HttpClientFactoryBean(
                "simple", clientProperties, null);
        factory.setRegistry(registry);

        factory.getObject();

        verify(registry, never()).registerTlsConfig(any());
    }

    @Test
    @DisplayName("throws IllegalStateException when registry not set")
    void throwsWhenRegistryNotSet() {
        HttpClientFactoryBean factory = new HttpClientFactoryBean(
                "payments", clientProperties, null);

        assertThatThrownBy(factory::getObject)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("HttpClientRegistry not set");
    }

    @Test
    @DisplayName("reports HttpClient as object type")
    void reportsCorrectObjectType() {
        HttpClientFactoryBean factory = new HttpClientFactoryBean(
                "payments", clientProperties, null);

        assertThat(factory.getObjectType()).isEqualTo(HttpClient.class);
    }

    @Test
    @DisplayName("is a singleton factory")
    void isSingleton() {
        HttpClientFactoryBean factory = new HttpClientFactoryBean(
                "payments", clientProperties, null);

        assertThat(factory.isSingleton()).isTrue();
    }

    @Test
    @DisplayName("passes TraceHeaderPropagator to HttpClientConfig when set")
    void passesTracePropagatorToConfig() throws Exception {
        when(registry.get(any(HttpClientConfig.class))).thenReturn(mockClient);

        HttpClientFactoryBean factory = new HttpClientFactoryBean(
                "traced", clientProperties, null);
        factory.setRegistry(registry);
        factory.setTracePropagator(mockPropagator);

        factory.getObject();

        ArgumentCaptor<HttpClientConfig> captor = ArgumentCaptor.forClass(HttpClientConfig.class);
        verify(registry).get(captor.capture());
        assertThat(captor.getValue().getTraceHeaderPropagator()).isSameAs(mockPropagator);
    }

    @Test
    @DisplayName("uses default no-op propagator when tracePropagator not set")
    void usesNoOpWhenTracePropagatorNotSet() throws Exception {
        when(registry.get(any(HttpClientConfig.class))).thenReturn(mockClient);

        HttpClientFactoryBean factory = new HttpClientFactoryBean(
                "untraced", clientProperties, null);
        factory.setRegistry(registry);
        // tracePropagator not set — null

        factory.getObject();

        ArgumentCaptor<HttpClientConfig> captor = ArgumentCaptor.forClass(HttpClientConfig.class);
        verify(registry).get(captor.capture());
        assertThat(captor.getValue().getTraceHeaderPropagator())
                .isInstanceOf(cz.syntea.bedrock.wire.classic.observability.NoOpTraceHeaderPropagator.class);
    }

}