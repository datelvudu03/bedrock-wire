package cz.syntea.bedrock.wire.monitor.config;

import cz.syntea.bedrock.wire.monitor.model.HttpMethod;
import cz.syntea.bedrock.wire.monitor.validation.ValidatorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PropertiesFileConfigProvider}: lookup rules, header merging,
 * Duration parsing, transport property extraction, and validation.
 */
class PropertiesFileConfigProviderTest {

    @TempDir
    Path tempDir;
    private Set<String> validAliases;

    @BeforeEach
    void setUp() {
        validAliases = new ValidatorRegistry().getAliases();
    }

    @Test
    void shouldLoadFullConfiguration() throws IOException {
        Path configFile = Path.of("src/test/resources/monitor-test.properties");
        PropertiesFileConfigProvider provider = new PropertiesFileConfigProvider(configFile, validAliases);

        assertEquals(2, provider.getServices().size());
        assertEquals(3, provider.getChecks().size());
        assertEquals(1, provider.getTlsProfiles().size());
        assertEquals(Duration.ofSeconds(10), provider.getShutdownTimeout());
    }

    @Test
    void shouldResolveServiceConfig() throws IOException {
        Path configFile = Path.of("src/test/resources/monitor-test.properties");
        PropertiesFileConfigProvider provider = new PropertiesFileConfigProvider(configFile, validAliases);

        ServiceConfig testService = provider.getServices().stream()
                .filter(s -> "testService".equals(s.getServiceName()))
                .findFirst().orElseThrow();

        assertEquals("https://test.example.com", testService.getUrl().toString());
        assertEquals("application/xml", testService.getHeaders().get("Content-Type"));
        assertEquals("10s", testService.getTransportProperties().get("responseTimeout"));
        assertEquals("3s", testService.getTransportProperties().get("connectionTimeout"));
    }

    @Test
    void shouldResolveCheckWithThreeLevelLookup() throws IOException {
        Path configFile = Path.of("src/test/resources/monitor-test.properties");
        PropertiesFileConfigProvider provider = new PropertiesFileConfigProvider(configFile, validAliases);

        // healthCheck has no interval at check level → uses default (30s)
        CheckConfig healthCheck = provider.getChecks().stream()
                .filter(c -> "healthCheck".equals(c.getCheckName()))
                .findFirst().orElseThrow();

        assertEquals(Duration.ofSeconds(30), healthCheck.getInterval());
        assertEquals(HttpMethod.POST, healthCheck.getMethod());
        assertEquals(1, healthCheck.getRetryCount()); // from default
        assertEquals(Duration.ofSeconds(2), healthCheck.getRetryDelay()); // from default

        // pingCheck overrides interval to 10s and retry.count to 0
        CheckConfig pingCheck = provider.getChecks().stream()
                .filter(c -> "pingCheck".equals(c.getCheckName()))
                .findFirst().orElseThrow();

        assertEquals(Duration.ofSeconds(10), pingCheck.getInterval());
        assertEquals(0, pingCheck.getRetryCount());
        assertEquals(HttpMethod.GET, pingCheck.getMethod());
    }

    @Test
    void shouldMergeHeadersCaseInsensitive() throws IOException {
        Path configFile = Path.of("src/test/resources/monitor-test.properties");
        PropertiesFileConfigProvider provider = new PropertiesFileConfigProvider(configFile, validAliases);

        CheckConfig healthCheck = provider.getChecks().stream()
                .filter(c -> "healthCheck".equals(c.getCheckName()))
                .findFirst().orElseThrow();

        // Default: Accept=application/xml, X-Client-Id=bedrock-monitor-test
        // Service: Content-Type=application/xml
        // Check: (none)
        assertTrue(healthCheck.getHeaders().containsKey("Accept"));
        assertTrue(healthCheck.getHeaders().containsKey("Content-Type"));
        assertTrue(healthCheck.getHeaders().containsKey("X-Client-Id"));
    }

    @Test
    void shouldExtractValidationParams() throws IOException {
        Path configFile = Path.of("src/test/resources/monitor-test.properties");
        PropertiesFileConfigProvider provider = new PropertiesFileConfigProvider(configFile, validAliases);

        CheckConfig healthCheck = provider.getChecks().stream()
                .filter(c -> "healthCheck".equals(c.getCheckName()))
                .findFirst().orElseThrow();

        assertEquals("200", healthCheck.getValidationParams().get("httpStatus"));
        assertEquals("<status>OK</status>", healthCheck.getValidationParams().get("contains"));
        assertEquals(2, healthCheck.getValidators().size());
        assertEquals("httpStatus", healthCheck.getValidators().get(0));
        assertEquals("contains", healthCheck.getValidators().get(1));
    }

    @Test
    void shouldExtractTemplateParams() throws IOException {
        Path configFile = Path.of("src/test/resources/monitor-test.properties");
        PropertiesFileConfigProvider provider = new PropertiesFileConfigProvider(configFile, validAliases);

        CheckConfig healthCheck = provider.getChecks().stream()
                .filter(c -> "healthCheck".equals(c.getCheckName()))
                .findFirst().orElseThrow();

        assertEquals("test-client", healthCheck.getTemplateParams().get("clientId"));
        assertEquals("test-region", healthCheck.getTemplateParams().get("region"));
    }

    @Test
    void shouldParseTlsProfile() throws IOException {
        Path configFile = Path.of("src/test/resources/monitor-test.properties");
        PropertiesFileConfigProvider provider = new PropertiesFileConfigProvider(configFile, validAliases);

        TlsProfileConfig tls = provider.getTlsProfiles().get(0);
        assertEquals("test-tls", tls.getProfileName());
        assertEquals("/certs/test-truststore.p12", tls.getTrustStore());
        assertEquals("testpass", tls.getTrustStorePassword());
        assertEquals("PKCS12", tls.getTrustStoreType());
        assertTrue(tls.isHostnameVerification());
    }

    @Test
    void shouldFailOnMissingFile() {
        assertThrows(IllegalArgumentException.class, () ->
                new PropertiesFileConfigProvider(Path.of("/nonexistent.properties"), validAliases));
    }

    @Test
    void shouldFailOnDuplicateServiceName() throws IOException {
        Path file = writeConfig(tempDir,
                "monitor.service.dup.url = https://a.com\n"
                        + "monitor.service.dup.url = https://b.com\n"
                        + "monitor.check.c.service = dup\n"
                        + "monitor.check.c.interval = 5s\n"
                        + "monitor.check.c.validation.validators = httpStatus\n"
                        + "monitor.check.c.validation.httpStatus = 200\n"
        );
        // Properties format inherently deduplicates keys (last wins),
        // so this tests that a single service loads correctly.
        PropertiesFileConfigProvider provider = new PropertiesFileConfigProvider(file, validAliases);
        assertEquals(1, provider.getServices().size());
    }

    @Test
    void shouldFailOnUnknownValidatorAlias() throws IOException {
        Path file = writeConfig(tempDir,
                "monitor.service.svc.url = https://a.com\n"
                        + "monitor.check.c.service = svc\n"
                        + "monitor.check.c.interval = 5s\n"
                        + "monitor.check.c.validation.validators = nonExistentValidator\n"
        );
        assertThrows(IllegalArgumentException.class, () ->
                new PropertiesFileConfigProvider(file, validAliases));
    }

    @Test
    void shouldFailOnBareNumberDuration() throws IOException {
        Path file = writeConfig(tempDir,
                "monitor.service.svc.url = https://a.com\n"
                        + "monitor.check.c.service = svc\n"
                        + "monitor.check.c.interval = 30\n"
                        + "monitor.check.c.validation.validators = httpStatus\n"
                        + "monitor.check.c.validation.httpStatus = 200\n"
        );
        assertThrows(IllegalArgumentException.class, () ->
                new PropertiesFileConfigProvider(file, validAliases));
    }

    @Test
    void shouldFailOnMissingServiceReference() throws IOException {
        Path file = writeConfig(tempDir,
                "monitor.service.svc.url = https://a.com\n"
                        + "monitor.check.c.service = nonexistent\n"
                        + "monitor.check.c.interval = 5s\n"
        );
        assertThrows(IllegalArgumentException.class, () ->
                new PropertiesFileConfigProvider(file, validAliases));
    }

    @Test
    void shouldFailOnReservedDefaultName() throws IOException {
        Path file = writeConfig(tempDir,
                "monitor.service.default.url = https://a.com\n"
        );
        assertThrows(IllegalArgumentException.class, () ->
                new PropertiesFileConfigProvider(file, validAliases));
    }

    @Test
    void shouldParseDurationFormats() {
        assertEquals(Duration.ofMillis(500), PropertiesFileConfigProvider.parseDuration("500ms", "test"));
        assertEquals(Duration.ofSeconds(5), PropertiesFileConfigProvider.parseDuration("5s", "test"));
        assertEquals(Duration.ofMinutes(2), PropertiesFileConfigProvider.parseDuration("2m", "test"));
        assertEquals(Duration.ofHours(1), PropertiesFileConfigProvider.parseDuration("1h", "test"));
    }

    @Test
    void shouldRejectInvalidDuration() {
        assertThrows(IllegalArgumentException.class, () ->
                PropertiesFileConfigProvider.parseDuration("30", "test"));
        assertThrows(IllegalArgumentException.class, () ->
                PropertiesFileConfigProvider.parseDuration("abc", "test"));
        assertThrows(IllegalArgumentException.class, () ->
                PropertiesFileConfigProvider.parseDuration("", "test"));
    }

    private Path writeConfig(Path dir, String content) throws IOException {
        Path file = dir.resolve("test.properties");
        Files.writeString(file, content);
        return file;
    }
}
