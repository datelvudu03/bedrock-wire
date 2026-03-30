package cz.syntea.bedrock.wire.classic;

import cz.syntea.bedrock.wire.classic.spring.ClientProperties;
import cz.syntea.bedrock.wire.classic.spring.ParamFileClientConfigAdapter;
import cz.syntea.bedrock.wire.classic.spring.TlsProfileProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link ParamFileClientConfigAdapter}.
 */
class ParamFileClientConfigAdapterTest {

    private Properties properties;
    private ParamFileClientConfigAdapter adapter;

    @BeforeEach
    void setUp() {
        properties = new Properties();
        adapter = new ParamFileClientConfigAdapter(properties);
    }

    @Nested
    @DisplayName("parseTlsProfiles()")
    class ParseTlsProfiles {

        @Test
        @DisplayName("parses complete TLS profile")
        void parsesCompleteTlsProfile() {
            properties.setProperty("wire.tls.payments-tls.clientCert", "/certs/client.p12");
            properties.setProperty("wire.tls.payments-tls.clientCertPassword", "secret");
            properties.setProperty("wire.tls.payments-tls.clientCertType", "PKCS12");
            properties.setProperty("wire.tls.payments-tls.trustStore", "/certs/trust.p12");
            properties.setProperty("wire.tls.payments-tls.trustStorePassword", "trustsecret");
            properties.setProperty("wire.tls.payments-tls.trustStoreType", "PKCS12");

            Map<String, TlsProfileProperties> profiles = adapter.parseTlsProfiles();

            assertThat(profiles).hasSize(1).containsKey("payments-tls");
            TlsProfileProperties tls = profiles.get("payments-tls");
            assertThat(tls.getClientCert()).isEqualTo("/certs/client.p12");
            assertThat(tls.getClientCertPassword()).isEqualTo("secret");
            assertThat(tls.getClientCertType()).isEqualTo("PKCS12");
            assertThat(tls.getTrustStore()).isEqualTo("/certs/trust.p12");
            assertThat(tls.getTrustStorePassword()).isEqualTo("trustsecret");
            assertThat(tls.getTrustStoreType()).isEqualTo("PKCS12");
            assertThat(tls.isHostnameVerification()).isTrue();
        }

        @Test
        @DisplayName("parses hostname verification override")
        void parsesHostnameVerificationOverride() {
            properties.setProperty("wire.tls.insecure.hostnameVerification", "false");
            properties.setProperty("wire.tls.insecure.allowInsecureInProduction", "true");

            Map<String, TlsProfileProperties> profiles = adapter.parseTlsProfiles();

            TlsProfileProperties tls = profiles.get("insecure");
            assertThat(tls.isHostnameVerification()).isFalse();
            assertThat(tls.isAllowInsecureInProduction()).isTrue();
        }

        @Test
        @DisplayName("returns empty map when no TLS profiles defined")
        void returnsEmptyWhenNoProfiles() {
            properties.setProperty("wire.client.payments.url", "https://example.com");

            assertThat(adapter.parseTlsProfiles()).isEmpty();
        }

        @Test
        @DisplayName("parses multiple TLS profiles")
        void parsesMultipleProfiles() {
            properties.setProperty("wire.tls.profile-a.clientCert", "/a.p12");
            properties.setProperty("wire.tls.profile-b.clientCert", "/b.p12");

            Map<String, TlsProfileProperties> profiles = adapter.parseTlsProfiles();

            assertThat(profiles).hasSize(2)
                    .containsKeys("profile-a", "profile-b");
        }
    }

    @Nested
    @DisplayName("parseClients()")
    class ParseClients {

        @Test
        @DisplayName("parses complete client configuration")
        void parsesCompleteClient() {
            properties.setProperty("wire.client.payments.url", "https://payments.example.com:8443");
            properties.setProperty("wire.client.payments.connectionTimeout", "3s");
            properties.setProperty("wire.client.payments.responseTimeout", "10s");
            properties.setProperty("wire.client.payments.readTimeout", "10s");
            properties.setProperty("wire.client.payments.maxConnections", "100");
            properties.setProperty("wire.client.payments.maxResponseBodySize", "2097152");
            properties.setProperty("wire.client.payments.tlsProfile", "payments-tls");
            properties.setProperty("wire.client.payments.header.Content-Type", "text/xml");
            properties.setProperty("wire.client.payments.header.X-Client-Id", "showcase");

            Map<String, ClientProperties> clients = adapter.parseClients();

            assertThat(clients).hasSize(1).containsKey("payments");
            ClientProperties client = clients.get("payments");
            assertThat(client.getBaseUrl()).isEqualTo("https://payments.example.com:8443");
            assertThat(client.getConnectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(client.getResponseTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(client.getReadTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(client.getMaxConnections()).isEqualTo(100);
            assertThat(client.getMaxResponseBodySize()).isEqualTo(2097152);
            assertThat(client.getTlsProfile()).isEqualTo("payments-tls");
            assertThat(client.getHeaders())
                    .containsEntry("Content-Type", "text/xml")
                    .containsEntry("X-Client-Id", "showcase");
        }

        @Test
        @DisplayName("uses defaults for omitted timeout values")
        void usesDefaultsForOmittedValues() {
            properties.setProperty("wire.client.simple.url", "https://example.com");

            Map<String, ClientProperties> clients = adapter.parseClients();

            ClientProperties client = clients.get("simple");
            assertThat(client.getConnectTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(client.getResponseTimeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(client.getReadTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(client.getMaxConnections()).isEqualTo(50);
            assertThat(client.getMaxResponseBodySize()).isEqualTo(1024 * 1024);
        }

        @Test
        @DisplayName("parses millisecond durations")
        void parsesMillisecondDurations() {
            properties.setProperty("wire.client.fast.url", "https://example.com");
            properties.setProperty("wire.client.fast.responseTimeout", "500ms");

            Map<String, ClientProperties> clients = adapter.parseClients();

            assertThat(clients.get("fast").getResponseTimeout())
                    .isEqualTo(Duration.ofMillis(500));
        }

        @Test
        @DisplayName("parses minute durations")
        void parsesMinuteDurations() {
            properties.setProperty("wire.client.slow.url", "https://example.com");
            properties.setProperty("wire.client.slow.readTimeout", "2m");

            Map<String, ClientProperties> clients = adapter.parseClients();

            assertThat(clients.get("slow").getReadTimeout())
                    .isEqualTo(Duration.ofMinutes(2));
        }

        @Test
        @DisplayName("returns empty map when no clients defined")
        void returnsEmptyWhenNoClients() {
            properties.setProperty("wire.tls.profile.clientCert", "/cert.p12");

            assertThat(adapter.parseClients()).isEmpty();
        }

        @Test
        @DisplayName("parses multiple clients")
        void parsesMultipleClients() {
            properties.setProperty("wire.client.a.url", "https://a.example.com");
            properties.setProperty("wire.client.b.url", "https://b.example.com");

            Map<String, ClientProperties> clients = adapter.parseClients();

            assertThat(clients).hasSize(2)
                    .containsKeys("a", "b");
        }

        @Test
        @DisplayName("throws on malformed maxConnections value")
        void throwsOnMalformedMaxConnections() {
            properties.setProperty("wire.client.bad.url", "https://example.com");
            properties.setProperty("wire.client.bad.maxConnections", "abc");

            assertThatThrownBy(() -> adapter.parseClients())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid integer")
                    .hasMessageContaining("maxConnections")
                    .hasMessageContaining("bad");
        }

        @Test
        @DisplayName("throws on malformed maxResponseBodySize value")
        void throwsOnMalformedMaxResponseBodySize() {
            properties.setProperty("wire.client.bad.url", "https://example.com");
            properties.setProperty("wire.client.bad.maxResponseBodySize", "not-a-number");

            assertThatThrownBy(() -> adapter.parseClients())
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid integer")
                    .hasMessageContaining("maxResponseBodySize");
        }
    }

    @Nested
    @DisplayName("parseDuration()")
    class ParseDuration {

        @Test
        @DisplayName("rejects bare number without unit")
        void rejectsBareNumber() {
            assertThatThrownBy(() ->
                    ParamFileClientConfigAdapter.parseDuration("10", "timeout", "test"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid duration")
                    .hasMessageContaining("Expected format: 5s, 500ms, 2m");
        }

        @Test
        @DisplayName("rejects empty string")
        void rejectsEmptyString() {
            assertThatThrownBy(() ->
                    ParamFileClientConfigAdapter.parseDuration("", "timeout", "test"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects non-numeric value with unit suffix")
        void rejectsNonNumericWithUnit() {
            assertThatThrownBy(() ->
                    ParamFileClientConfigAdapter.parseDuration("abcs", "timeout", "test"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid duration");
        }
    }

    @Nested
    @DisplayName("ClientProperties.toHttpClientConfig()")
    class ToHttpClientConfig {

        @Test
        @DisplayName("throws on null baseUrl")
        void throwsOnNullBaseUrl() {
            ClientProperties client = new ClientProperties();
            // baseUrl is null by default

            assertThatThrownBy(() -> client.toHttpClientConfig("test"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("baseUrl is required")
                    .hasMessageContaining("test");
        }

        @Test
        @DisplayName("throws on blank baseUrl")
        void throwsOnBlankBaseUrl() {
            ClientProperties client = new ClientProperties();
            client.setBaseUrl("   ");

            assertThatThrownBy(() -> client.toHttpClientConfig("test"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("baseUrl is required");
        }
    }

}