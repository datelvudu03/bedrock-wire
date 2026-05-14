package cz.syntea.bedrock.wire.template.source;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link TemplateVarScanner}: namespace exclusion, bare-identifier
 * filtering, dotted-key skipping, and blank-value handling.
 */
class TemplateVarScannerTest {

    @Test
    void exposesLegalBareIdentifiers() {
        Properties cfg = new Properties();
        cfg.setProperty("_MODE", "DEV");
        cfg.setProperty("mode", "PROD");
        cfg.setProperty("userId", "hejny");

        Map<String, Object> vars = TemplateVarScanner.scan(cfg);

        assertEquals("DEV", vars.get("_MODE"));
        assertEquals("PROD", vars.get("mode"));
        assertEquals("hejny", vars.get("userId"));
        assertEquals(3, vars.size());
    }

    @Test
    void excludesMonitorNamespace() {
        Properties cfg = new Properties();
        cfg.setProperty("_MODE", "DEV");
        cfg.setProperty("monitor.service.px1ps.url", "https://example.com");
        cfg.setProperty("monitor.check.c.path", "/ping");

        Map<String, Object> vars = TemplateVarScanner.scan(cfg);

        assertTrue(vars.containsKey("_MODE"));
        assertFalse(vars.containsKey("monitor.service.px1ps.url"));
        assertFalse(vars.containsKey("monitor.check.c.path"));
        assertEquals(1, vars.size());
    }

    @Test
    void excludesBedrockWireMonitorNamespace() {
        Properties cfg = new Properties();
        cfg.setProperty("_MODE", "DEV");
        cfg.setProperty("bedrock.wire.monitor.enabled", "true");
        cfg.setProperty("bedrock.wire.monitor.config-file", "app.param");

        Map<String, Object> vars = TemplateVarScanner.scan(cfg);

        assertTrue(vars.containsKey("_MODE"));
        assertFalse(vars.containsKey("bedrock.wire.monitor.enabled"));
        assertFalse(vars.containsKey("bedrock.wire.monitor.config-file"));
        assertEquals(1, vars.size());
    }

    @Test
    void skipsDottedKeysSilently() {
        Properties cfg = new Properties();
        cfg.setProperty("_MODE", "DEV");
        cfg.setProperty("mode.mode", "X");
        cfg.setProperty("some.app.setting", "Y");

        Map<String, Object> vars = TemplateVarScanner.scan(cfg);

        assertTrue(vars.containsKey("_MODE"));
        assertFalse(vars.containsKey("mode.mode"));
        assertFalse(vars.containsKey("some.app.setting"));
        assertEquals(1, vars.size());
    }

    @Test
    void skipsKeysStartingWithDigit() {
        Properties cfg = new Properties();
        cfg.setProperty("_MODE", "DEV");
        cfg.setProperty("1mode", "X");

        Map<String, Object> vars = TemplateVarScanner.scan(cfg);

        assertTrue(vars.containsKey("_MODE"));
        assertFalse(vars.containsKey("1mode"));
    }

    @Test
    void treatsBlankValueAsAbsent() {
        Properties cfg = new Properties();
        cfg.setProperty("_MODE", "");
        cfg.setProperty("_REGION", "   ");
        cfg.setProperty("_OK", "value");

        Map<String, Object> vars = TemplateVarScanner.scan(cfg);

        assertFalse(vars.containsKey("_MODE"));
        assertFalse(vars.containsKey("_REGION"));
        assertTrue(vars.containsKey("_OK"));
    }

    @Test
    void trimsValues() {
        Properties cfg = new Properties();
        cfg.setProperty("_MODE", "  DEV  ");

        Map<String, Object> vars = TemplateVarScanner.scan(cfg);

        assertEquals("DEV", vars.get("_MODE"));
    }

    @Test
    void returnsEmptyMapForEmptyProperties() {
        Map<String, Object> vars = TemplateVarScanner.scan(new Properties());
        assertTrue(vars.isEmpty());
    }

    @Test
    void rejectsNullProperties() {
        assertThrows(IllegalArgumentException.class, () -> TemplateVarScanner.scan(null));
    }

    @Test
    void resultIsImmutable() {
        Properties cfg = new Properties();
        cfg.setProperty("_MODE", "DEV");
        Map<String, Object> vars = TemplateVarScanner.scan(cfg);
        assertThrows(UnsupportedOperationException.class, () -> vars.put("x", "y"));
    }

    @Test
    void readsThroughResolvingPropertiesSubclass() {
        // Simulates PropertiesCfg: getProperty() resolves ${...} chains.
        Properties cfg = new ResolvingProperties();
        cfg.setProperty("_RAW", "DEV");
        cfg.setProperty("_MODE", "${_RAW}");

        Map<String, Object> vars = TemplateVarScanner.scan(cfg);

        assertEquals("DEV", vars.get("_MODE"));
    }

    /**
     * Minimal {@link Properties} subclass that resolves {@code ${name}} references
     * on read, simulating {@code PropertiesCfg} for tests without depending on
     * {@code syntea-bedrock-cfg}.
     */
    private static final class ResolvingProperties extends Properties {
        @Override
        public String getProperty(String key) {
            String value = super.getProperty(key);
            if (value == null) {
                return null;
            }
            java.util.regex.Matcher m =
                    java.util.regex.Pattern.compile("\\$\\{([^}]+)}").matcher(value);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                String ref = getProperty(m.group(1));
                m.appendReplacement(sb,
                        java.util.regex.Matcher.quoteReplacement(ref != null ? ref : ""));
            }
            m.appendTail(sb);
            return sb.toString();
        }
    }
}