package cz.syntea.bedrock.wire.monitor.config;

import cz.syntea.bedrock.wire.monitor.model.HttpMethod;
import cz.syntea.bedrock.wire.monitor.validation.ValidatorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PropertiesFileConfigProvider}: lookup rules, header merging,
 * Duration parsing, validation, and the 7-layer template-context model
 * (spec §2.9 — Spring env + per-level config-file + per-level param.*).
 */
class PropertiesFileConfigProviderTest {

    @TempDir
    Path tempDir;
    private Set<String> validAliases;

    @BeforeEach
    void setUp() {
        validAliases = new ValidatorRegistry().getAliases();
    }

    // ── Existing core behaviour (file-based, 3-level lookup) ────────────────

    /**
     * Traverses a nested {@link Map} structure by dotted-style path arguments.
     * Returns {@code null} if any segment is missing or not a map.
     */
    @SuppressWarnings("unchecked")
    private static Object nested(Map<String, Object> root, String... path) {
        Object cursor = root;
        for (String segment : path) {
            if (!(cursor instanceof Map<?, ?> m)) {
                return null;
            }
            cursor = ((Map<String, Object>) m).get(segment);
            if (cursor == null) {
                return null;
            }
        }
        return cursor;
    }

    @Test
    void shouldLoadFullConfiguration() throws IOException {
        Path configFile = Path.of("src/test/resources/monitor-test.properties");
        PropertiesFileConfigProvider provider =
                new PropertiesFileConfigProvider(configFile, validAliases);

        assertEquals(2, provider.getServices().size());
        assertEquals(3, provider.getChecks().size());
        assertEquals(1, provider.getTlsProfiles().size());
        assertEquals(Duration.ofSeconds(10), provider.getShutdownTimeout());
    }

    @Test
    void shouldLoadFromPropertiesInstance() {
        Properties props = new Properties();
        props.setProperty("monitor.default.interval", "30s");
        props.setProperty("monitor.service.svc.url", "https://a.example.com");
        props.setProperty("monitor.check.c.service", "svc");
        props.setProperty("monitor.check.c.interval", "5s");
        props.setProperty("monitor.check.c.validation.validators", "httpStatus");
        props.setProperty("monitor.check.c.validation.httpStatus", "200");

        PropertiesFileConfigProvider provider =
                new PropertiesFileConfigProvider(props, validAliases);

        assertEquals(1, provider.getServices().size());
        assertEquals("svc", provider.getServices().get(0).getServiceName());
        assertEquals(1, provider.getChecks().size());
        assertEquals("c", provider.getChecks().get(0).getCheckName());
        assertEquals(Duration.ofSeconds(5), provider.getChecks().get(0).getInterval());
    }

    @Test
    void shouldResolveCheckWithThreeLevelLookup() throws IOException {
        Path configFile = Path.of("src/test/resources/monitor-test.properties");
        PropertiesFileConfigProvider provider =
                new PropertiesFileConfigProvider(configFile, validAliases);

        CheckConfig healthCheck = provider.getChecks().stream()
                .filter(c -> "healthCheck".equals(c.getCheckName()))
                .findFirst().orElseThrow();
        assertEquals(Duration.ofSeconds(30), healthCheck.getInterval());
        assertEquals(HttpMethod.POST, healthCheck.getMethod());
        assertEquals(1, healthCheck.getRetryCount());

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
        PropertiesFileConfigProvider provider =
                new PropertiesFileConfigProvider(configFile, validAliases);

        CheckConfig healthCheck = provider.getChecks().stream()
                .filter(c -> "healthCheck".equals(c.getCheckName()))
                .findFirst().orElseThrow();
        assertTrue(healthCheck.getHeaders().containsKey("Accept"));
        assertTrue(healthCheck.getHeaders().containsKey("Content-Type"));
        assertTrue(healthCheck.getHeaders().containsKey("X-Client-Id"));
    }

    @Test
    void shouldExtractValidationParams() throws IOException {
        Path configFile = Path.of("src/test/resources/monitor-test.properties");
        PropertiesFileConfigProvider provider =
                new PropertiesFileConfigProvider(configFile, validAliases);

        CheckConfig healthCheck = provider.getChecks().stream()
                .filter(c -> "healthCheck".equals(c.getCheckName()))
                .findFirst().orElseThrow();
        assertEquals("200", healthCheck.getValidationParams().get("httpStatus"));
        assertEquals("<status>OK</status>", healthCheck.getValidationParams().get("contains"));
    }

    @Test
    void shouldFailOnMissingFile() {
        assertThrows(IllegalArgumentException.class, () ->
                new PropertiesFileConfigProvider(Path.of("/nonexistent.properties"),
                        validAliases));
    }

    @Test
    void shouldFailOnUnknownValidatorAlias() throws IOException {
        Path file = writeConfig(tempDir,
                "monitor.service.svc.url = https://a.com\n"
                        + "monitor.check.c.service = svc\n"
                        + "monitor.check.c.interval = 5s\n"
                        + "monitor.check.c.validation.validators = nonExistent\n");
        assertThrows(IllegalArgumentException.class, () ->
                new PropertiesFileConfigProvider(file, validAliases));
    }

    @Test
    void shouldFailOnReservedDefaultName() throws IOException {
        Path file = writeConfig(tempDir,
                "monitor.service.default.url = https://a.com\n");
        assertThrows(IllegalArgumentException.class, () ->
                new PropertiesFileConfigProvider(file, validAliases));
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

    // ── Template-context model (spec §2.9) ──────────────────────────────────

    @Test
    void shouldParseDurationFormats() {
        assertEquals(Duration.ofMillis(500),
                PropertiesFileConfigProvider.parseDuration("500ms", "test"));
        assertEquals(Duration.ofSeconds(5),
                PropertiesFileConfigProvider.parseDuration("5s", "test"));
        assertEquals(Duration.ofMinutes(2),
                PropertiesFileConfigProvider.parseDuration("2m", "test"));
        assertEquals(Duration.ofHours(1),
                PropertiesFileConfigProvider.parseDuration("1h", "test"));
    }

    @Test
    void layer7ParamOverridesLayer3Param() {
        PropertiesFileConfigProvider p = PropertiesFileConfigProvider.builder()
                .props(buildMinimalProps(Map.of(
                        "monitor.default.param.region", "eu-west-1",
                        "monitor.check.c.param.region", "eu-east-2"
                )))
                .validatorAliases(validAliases)
                .build();

        Map<String, Object> model = p.getChecks().get(0).getTemplateParams();
        assertEquals("eu-east-2", model.get("region"));
    }

    @Test
    void layer5ServiceParamFitsBetweenDefaultAndCheck() {
        PropertiesFileConfigProvider p = PropertiesFileConfigProvider.builder()
                .props(buildMinimalProps(Map.of(
                        "monitor.default.param.tier", "default",
                        "monitor.service.svc.param.tier", "service",
                        "monitor.check.c.param.region", "checkOnly"
                )))
                .validatorAliases(validAliases)
                .build();

        Map<String, Object> model = p.getChecks().get(0).getTemplateParams();
        assertEquals("service", model.get("tier"));         // L5 wins over L3
        assertEquals("checkOnly", model.get("region"));     // L7 sets a new key
    }

    @Test
    void layer1SpringEnvIsLowestPrecedence() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("RUN.MODE", "fromSpring");

        PropertiesFileConfigProvider p = PropertiesFileConfigProvider.builder()
                .props(buildMinimalProps(Map.of(
                        // Use a non-dotted key in the .param file: layer 3 wins.
                        "monitor.default.param.MODE", "fromParam"
                )))
                .validatorAliases(validAliases)
                .environment(env)
                .build();

        Map<String, Object> model = p.getChecks().get(0).getTemplateParams();
        // Spring property reached layer 1, .param reached layer 3 — L3 wins for MODE.
        assertEquals("fromParam", model.get("MODE"));
        // Spring-only key survives untouched.
        assertEquals("fromSpring", nested(model, "RUN", "MODE"));
    }

    @Test
    void springEnvPrefixRestrictsLayer1() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("template.region", "us")
                .withProperty("spring.datasource.password", "shouldNotLeak");

        PropertiesFileConfigProvider p = PropertiesFileConfigProvider.builder()
                .props(buildMinimalProps(Map.of(
                        "monitor.default.springEnvPrefix", "template."
                )))
                .validatorAliases(validAliases)
                .environment(env)
                .build();

        Map<String, Object> model = p.getChecks().get(0).getTemplateParams();
        assertEquals("us", model.get("region"));
        // spring.datasource.password is excluded by the prefix filter.
        assertNull(nested(model, "spring", "datasource", "password"));
    }

    @Test
    void springEnvPrefixCheckLevelOverridesDefault() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("a.x", "defaultPrefix")
                .withProperty("b.x", "checkPrefix");

        PropertiesFileConfigProvider p = PropertiesFileConfigProvider.builder()
                .props(buildMinimalProps(Map.of(
                        "monitor.default.springEnvPrefix", "a.",
                        "monitor.check.c.springEnvPrefix", "b."
                )))
                .validatorAliases(validAliases)
                .environment(env)
                .build();

        Map<String, Object> model = p.getChecks().get(0).getTemplateParams();
        // Check-level prefix wins; only b.* is exposed.
        assertEquals("checkPrefix", model.get("x"));
    }

    @Test
    void defaultConfigFileJsonNamespacedUnderDefault() throws IOException {
        Path cfg = tempDir.resolve("global.json");
        Files.writeString(cfg, "{\"region\":\"eu-west-1\",\"limits\":{\"max\":100}}");

        PropertiesFileConfigProvider p = PropertiesFileConfigProvider.builder()
                .props(buildMinimalProps(Map.of(
                        "monitor.default.config-file", cfg.toString()
                )))
                .validatorAliases(validAliases)
                .configFileRoot(tempDir)
                .build();

        Map<String, Object> model = p.getChecks().get(0).getTemplateParams();
        assertEquals("eu-west-1", nested(model, "default", "region"));
        assertEquals(100, nested(model, "default", "limits", "max"));
    }

    @Test
    void serviceConfigFilePropertiesNamespacedUnderService() throws IOException {
        Path cfg = tempDir.resolve("svc.properties");
        Files.writeString(cfg, "timeout=10s\nendpoints.health=/h\n");

        PropertiesFileConfigProvider p = PropertiesFileConfigProvider.builder()
                .props(buildMinimalProps(Map.of(
                        "monitor.service.svc.config-file", cfg.toString()
                )))
                .validatorAliases(validAliases)
                .configFileRoot(tempDir)
                .build();

        Map<String, Object> model = p.getChecks().get(0).getTemplateParams();
        assertEquals("10s", nested(model, "service", "svc", "timeout"));
        assertEquals("/h", nested(model, "service", "svc", "endpoints", "health"));
    }

    @Test
    void checkConfigFileParamNamespacedUnderCheck() throws IOException {
        Path cfg = tempDir.resolve("check.param");
        Files.writeString(cfg, "threshold=42\nlabels.team=ops\n");

        PropertiesFileConfigProvider p = PropertiesFileConfigProvider.builder()
                .props(buildMinimalProps(Map.of(
                        "monitor.check.c.config-file", cfg.toString()
                )))
                .validatorAliases(validAliases)
                .configFileRoot(tempDir)
                .build();

        Map<String, Object> model = p.getChecks().get(0).getTemplateParams();
        assertEquals("42", nested(model, "check", "c", "threshold"));
        assertEquals("ops", nested(model, "check", "c", "labels", "team"));
    }

    @Test
    void configFileMissingThrows() {
        assertThrows(IllegalArgumentException.class, () ->
                PropertiesFileConfigProvider.builder()
                        .props(buildMinimalProps(Map.of(
                                "monitor.default.config-file", "/nonexistent.json"
                        )))
                        .validatorAliases(validAliases)
                        .build());
    }

    @Test
    void reservedParamNameDefaultRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                PropertiesFileConfigProvider.builder()
                        .props(buildMinimalProps(Map.of(
                                "monitor.default.param.default", "x"
                        )))
                        .validatorAliases(validAliases)
                        .build());
    }

    @Test
    void reservedParamNameServiceRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                PropertiesFileConfigProvider.builder()
                        .props(buildMinimalProps(Map.of(
                                "monitor.check.c.param.service.x", "y"
                        )))
                        .validatorAliases(validAliases)
                        .build());
    }

    // ── Path resolution ─────────────────────────────────────────────────────

    @Test
    void reservedParamNameCheckRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                PropertiesFileConfigProvider.builder()
                        .props(buildMinimalProps(Map.of(
                                "monitor.service.svc.param.check", "y"
                        )))
                        .validatorAliases(validAliases)
                        .build());
    }

    @Test
    void templateFileResolvesRelativeToConfigFileRoot() throws IOException {
        Path templatesDir = Files.createDirectory(tempDir.resolve("templates"));
        Path tpl = templatesDir.resolve("body.xml");
        Files.writeString(tpl, "<x/>");

        PropertiesFileConfigProvider p = PropertiesFileConfigProvider.builder()
                .props(buildMinimalProps(Map.of(
                        "monitor.check.c.templateFile", "templates/body.xml"
                )))
                .validatorAliases(validAliases)
                .configFileRoot(tempDir)
                .build();

        String resolved = p.getChecks().get(0).getTemplateFile();
        assertEquals(tpl.toAbsolutePath().normalize().toString(), resolved);
    }

    // ── Layer 0 — bare-key scan (spec §2.9.0) ───────────────────────────────

    @Test
    void tlsTrustStoreResolvesRelativeToConfigFileRoot() throws IOException {
        Path certsDir = Files.createDirectory(tempDir.resolve("certs"));
        Path ts = certsDir.resolve("ts.p12");
        Files.writeString(ts, "");

        Map<String, String> extra = Map.of(
                "monitor.tls.test-tls.trustStore", "certs/ts.p12",
                "monitor.tls.test-tls.trustStorePassword", "x",
                "monitor.tls.test-tls.trustStoreType", "PKCS12"
        );
        PropertiesFileConfigProvider p = PropertiesFileConfigProvider.builder()
                .props(buildMinimalProps(extra))
                .validatorAliases(validAliases)
                .configFileRoot(tempDir)
                .build();

        TlsProfileConfig tls = p.getTlsProfiles().get(0);
        assertEquals(ts.toAbsolutePath().normalize().toString(), tls.getTrustStore());
    }

    @Test
    void layer0ExposesBareKey() {
        PropertiesFileConfigProvider p = PropertiesFileConfigProvider.builder()
                .props(buildMinimalProps(Map.of("_MODE", "TST")))
                .validatorAliases(validAliases)
                .build();

        Map<String, Object> model = p.getChecks().get(0).getTemplateParams();
        assertEquals("TST", model.get("_MODE"));
    }

    @Test
    void layer0ExposesDottedKeyFlat() {
        PropertiesFileConfigProvider p = PropertiesFileConfigProvider.builder()
                .props(buildMinimalProps(Map.of("RUN.MODE", "staging")))
                .validatorAliases(validAliases)
                .build();

        Map<String, Object> model = p.getChecks().get(0).getTemplateParams();
        // Flat: the single key "RUN.MODE", NOT nested under {RUN:{MODE}}.
        assertEquals("staging", model.get("RUN.MODE"));
        assertNull(nested(model, "RUN", "MODE"));
    }

    @Test
    void layer0ExcludesFrameworkNamespaces() {
        Properties props = buildMinimalProps(Map.of(
                "_MODE", "TST",
                "bedrock.wire.monitor.enabled", "true"
        ));
        // monitor.* and bedrock.wire.monitor.* must NOT be scanned into the model.
        PropertiesFileConfigProvider p = PropertiesFileConfigProvider.builder()
                .props(props)
                .validatorAliases(validAliases)
                .build();

        Map<String, Object> model = p.getChecks().get(0).getTemplateParams();
        assertEquals("TST", model.get("_MODE"));
        assertNull(model.get("monitor.service.svc.url"));
        assertNull(model.get("bedrock.wire.monitor.enabled"));
    }

    @Test
    void layer0SkipsBlankValues() {
        PropertiesFileConfigProvider p = PropertiesFileConfigProvider.builder()
                .props(buildMinimalProps(Map.of("_MODE", "   ")))
                .validatorAliases(validAliases)
                .build();

        Map<String, Object> model = p.getChecks().get(0).getTemplateParams();
        assertNull(model.get("_MODE"));
    }

    @Test
    void layer0IsLowestPrecedence() {
        // Bare _MODE at layer 0 vs check param._MODE at layer 7 → layer 7 wins.
        PropertiesFileConfigProvider p = PropertiesFileConfigProvider.builder()
                .props(buildMinimalProps(Map.of(
                        "_MODE", "scanned",
                        "monitor.check.c.param._MODE", "checkParam"
                )))
                .validatorAliases(validAliases)
                .build();

        Map<String, Object> model = p.getChecks().get(0).getTemplateParams();
        assertEquals("checkParam", model.get("_MODE"));
    }

    @Test
    void layer0LosesToServiceParam() {
        PropertiesFileConfigProvider p = PropertiesFileConfigProvider.builder()
                .props(buildMinimalProps(Map.of(
                        "_MODE", "scanned",
                        "monitor.service.svc.param._MODE", "serviceParam"
                )))
                .validatorAliases(validAliases)
                .build();

        Map<String, Object> model = p.getChecks().get(0).getTemplateParams();
        assertEquals("serviceParam", model.get("_MODE"));
    }

    @Test
    void layer0ResolvesChainedValue() {
        Properties props = new ResolvingProperties();
        props.setProperty("_RAW", "DEV");
        props.setProperty("_MODE", "${_RAW}");
        props.setProperty("monitor.service.svc.url", "https://a.com");
        props.setProperty("monitor.check.c.service", "svc");
        props.setProperty("monitor.check.c.interval", "5s");
        props.setProperty("monitor.check.c.validation.validators", "httpStatus");
        props.setProperty("monitor.check.c.validation.httpStatus", "200");

        PropertiesFileConfigProvider p = PropertiesFileConfigProvider.builder()
                .props(props)
                .validatorAliases(validAliases)
                .build();

        Map<String, Object> model = p.getChecks().get(0).getTemplateParams();
        assertEquals("DEV", model.get("_MODE"));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    @Test
    void layer0BareReservedNameAllowedButShadowed() {
        // A bare key literally "default" is NOT rejected (only param.* names are).
        // It is shadowed by any config-file layer, but with none present it survives.
        PropertiesFileConfigProvider p = PropertiesFileConfigProvider.builder()
                .props(buildMinimalProps(Map.of("default", "bareValue")))
                .validatorAliases(validAliases)
                .build();

        Map<String, Object> model = p.getChecks().get(0).getTemplateParams();
        assertEquals("bareValue", model.get("default"));
    }

    private Path writeConfig(Path dir, String content) throws IOException {
        Path file = dir.resolve("test.properties");
        Files.writeString(file, content);
        return file;
    }

    /**
     * Builds a minimal valid monitor configuration (one service, one check) plus
     * the supplied extra keys. Used to keep model-precedence tests focused.
     */
    private Properties buildMinimalProps(Map<String, String> extra) {
        Properties p = new Properties();
        p.setProperty("monitor.service.svc.url", "https://a.com");
        p.setProperty("monitor.check.c.service", "svc");
        p.setProperty("monitor.check.c.interval", "5s");
        p.setProperty("monitor.check.c.validation.validators", "httpStatus");
        p.setProperty("monitor.check.c.validation.httpStatus", "200");
        extra.forEach(p::setProperty);
        return p;
    }

    /**
     * Minimal {@link Properties} subclass that resolves {@code ${name}} references
     * on read, simulating {@code PropertiesCfg}.
     */
    private static final class ResolvingProperties extends Properties {
        @Override
        public String getProperty(String key) {
            String value = super.getProperty(key);
            if (value == null) {
                return null;
            }
            Matcher m = Pattern.compile("\\$\\{([^}]+)}").matcher(value);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String ref = getProperty(m.group(1));
                m.appendReplacement(sb, Matcher.quoteReplacement(ref != null ? ref : ""));
            }
            m.appendTail(sb);
            return sb.toString();
        }
    }
}