package cz.syntea.bedrock.wire.template.source;

import cz.syntea.bedrock.wire.template.exception.TemplateParamException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpringParamsTest {

    @Test
    void shouldStripPrefixFromKeys() {
        var env = new MockEnvironment()
                .withProperty("monitor.check.x.clientId", "hejny")
                .withProperty("monitor.check.x.mode", "PROD");

        var map = new SpringParams(env, "monitor.check.x.").asMap();

        assertEquals("hejny", map.get("clientId"));
        assertEquals("PROD", map.get("mode"));
    }

    @Test
    void shouldRebuildNestedMapFromDottedTail() {
        var env = new MockEnvironment()
                .withProperty("monitor.check.x.deployment.env", "prod")
                .withProperty("monitor.check.x.deployment.region", "eu-central");

        var map = new SpringParams(env, "monitor.check.x.").asMap();

        var deployment = assertInstanceOf(Map.class, map.get("deployment"));
        assertEquals("prod", deployment.get("env"));
        assertEquals("eu-central", deployment.get("region"));
    }

    @Test
    void shouldIgnorePropertiesOutsidePrefix() {
        var env = new MockEnvironment()
                .withProperty("monitor.check.x.a", "1")
                .withProperty("other.key", "leak")
                .withProperty("spring.datasource.password", "secret");

        var map = new SpringParams(env, "monitor.check.x.").asMap();

        assertEquals("1", map.get("a"));
        assertEquals(1, map.size());
        assertNull(map.get("other"));
    }

    @Test
    void shouldThrowOnNullPrefix() {
        assertThrows(TemplateParamException.class,
                () -> new SpringParams(new MockEnvironment(), null));
    }

    @Test
    void shouldThrowOnBlankPrefix() {
        assertThrows(TemplateParamException.class,
                () -> new SpringParams(new MockEnvironment(), "   "));
    }

    @Test
    void shouldThrowOnNullEnvironment() {
        assertThrows(TemplateParamException.class,
                () -> new SpringParams(null, "monitor.check.x."));
    }
}