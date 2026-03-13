package cz.syntea.bedrock.wire.classic;

import cz.syntea.bedrock.wire.classic.config.HttpClientConfig;
import cz.syntea.bedrock.wire.classic.model.HttpMethod;
import cz.syntea.bedrock.wire.classic.model.HttpRequest;
import cz.syntea.bedrock.wire.classic.model.HttpResponse;
import cz.syntea.bedrock.wire.classic.model.TlsConfig;
import cz.syntea.bedrock.wire.classic.registry.HttpClient;
import cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Live integration test against a real endpoint.
 *
 * <p>Runs ONLY with the {@code integration} profile active:
 * <pre>
 *   mvn test -Dspring.profiles.active=integration
 *   # or in IDE: set active profile to "integration"
 * </pre>
 *
 * <p>Configuration is loaded from {@code src/test/resources/application-integration.properties}.
 * No mocks — this hits the real service over the network.
 */
@SpringBootTest(classes = BedrockWireIntegrationTest.TestConfig.class)
@ActiveProfiles("integration")
@Slf4j
class BedrockWireIntegrationTest {

    private static final String PING_BODY =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n" +
                    "<ns2:Envelope xmlns:ns2=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                    "xmlns:ws=\"http://www.supin.cz/soap/2023/04\">\n" +
                    "    <ns2:Header>\n" +
                    "        <ws:Request IdentZpravy=\"PX1PS_oKgNHDOC\" Mode=\"TST\"/>\n" +
                    "    </ns2:Header>\n" +
                    "    <ns2:Body>\n" +
                    "        <ws:Ping/>\n" +
                    "    </ns2:Body>\n" +
                    "</ns2:Envelope>\n";
    @Autowired
    private HttpClientRegistry registry;

    @Value("${integration.certifications.path}")
    private String certPath;

    @Value("${integration.aaa.url}")
    private String aaaUrl;

    @Value("${integration.aaa.certName}")
    private String certName;

    @Value("${integration.aaa.certPassword}")
    private String certPassword;

    @Value("${integration.aaa.connectTimeout}")
    private long connectTimeout;

    @Value("${integration.aaa.readTimeout}")
    private long readTimeout;

    @Value("${integration.aaa.responseTimeout}")
    private long responseTimeout;

    private static URI toBaseUrl(URI uri) {
        int port = uri.getPort();
        String portPart = port == -1 ? "" : ":" + port;
        return URI.create(uri.getScheme() + "://" + uri.getHost() + portPart);
    }

    private static URI toPath(URI uri) {
        String path = uri.getRawPath();
        String query = uri.getRawQuery();
        return URI.create(query != null ? path + "?" + query : path);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    @Test
    void callAaaEndpoint() {
        URI fullUrl = URI.create(aaaUrl);
        URI baseUrl = toBaseUrl(fullUrl);
        URI path = toPath(fullUrl);

        registry.registerTlsConfig(TlsConfig.builder()
                .configName("aaa-tls-integration")
                .clientCert(Path.of(certPath, certName))
                .clientCertPassword(certPassword)
                .clientCertType("PKCS12")
                .build());

        HttpClient client = registry.get(HttpClientConfig.builder()
                .clientId("aaa-integration")
                .baseUrl(baseUrl)
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .responseTimeout(Duration.ofMillis(responseTimeout))
                .readTimeout(Duration.ofMillis(readTimeout))
                .defaultHeader("Content-Type", List.of("text/xml"))
                .defaultHeader("Accept", List.of("text/xml"))
                .tlsConfigName("aaa-tls-integration")
                .build());

        HttpResponse response = client.execute(HttpRequest.builder()
                .method(HttpMethod.POST)
                .url(path)
                .body(PING_BODY)
                .build()
        ).block();

        log.info("[IntegrationTest] result: {}", response);

        assertThat(response.getStatusCode())
                .as("Expected 2xx from %s", aaaUrl)
                .isBetween(200, 299);
    }

    /**
     * Minimal Spring Boot context — just enough to trigger auto-configuration.
     */
    @Configuration
    @EnableAutoConfiguration
    static class TestConfig {
    }
}
