package cz.syntea.bedrock.wire.template.source;

import cz.syntea.bedrock.wire.template.exception.TemplateParamException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@link SpringParams} blank-prefix policy change in 1.0.6.0.
 *
 * <p>Prior to 1.0.6.0 the prefix was REQUIRED and a blank prefix caused
 * {@link TemplateParamException}. From 1.0.6.0 blank means "no filter, expose
 * every key" — see the ADR in {@code Architecture-template.md}.
 */
class SpringParamsBlankPrefixTest {

    @SuppressWarnings("unchecked")
    private static Object deep(Map<String, Object> root, String... path) {
        Object cursor = root;
        for (String s : path) {
            if (!(cursor instanceof Map<?, ?> m)) {
                return null;
            }
            cursor = ((Map<String, Object>) m).get(s);
        }
        return cursor;
    }

    @Test
    void blankPrefixExposesEveryKey() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("a", "1")
                .withProperty("b.c", "2")
                .withProperty("spring.datasource.password", "secret");

        Map<String, Object> map = new SpringParams(env, "").asMap();

        assertEquals("1", map.get("a"));
        assertEquals("2", deep(map, "b", "c"));
        // The class-level security warning notes this is expected.
        assertEquals("secret", deep(map, "spring", "datasource", "password"));
    }

    @Test
    void nullPrefixSameAsBlank() {
        MockEnvironment env = new MockEnvironment().withProperty("a", "1");
        Map<String, Object> map = new SpringParams(env, null).asMap();
        assertEquals("1", map.get("a"));
    }

    @Test
    void prefixedRestrictsResults() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("template.region", "us")
                .withProperty("spring.datasource.password", "secret");

        Map<String, Object> map = new SpringParams(env, "template.").asMap();

        assertEquals("us", map.get("region"));
        assertNull(deep(map, "spring", "datasource", "password"));
    }

    @Test
    void factoryFromSpringEnvOnlyMatchesNoFilter() {
        MockEnvironment env = new MockEnvironment().withProperty("a", "1");
        Map<String, Object> via = Params.fromSpring(env).asMap();
        Map<String, Object> via2 = Params.fromSpring(env, null).asMap();
        Map<String, Object> via3 = Params.fromSpring(env, "").asMap();

        assertEquals("1", via.get("a"));
        assertEquals(via, via2);
        assertEquals(via, via3);
    }

    @Test
    void nullEnvironmentStillThrows() {
        assertThrows(TemplateParamException.class, () -> new SpringParams(null, "p."));
        assertThrows(TemplateParamException.class, () -> new SpringParams(null, ""));
        assertThrows(TemplateParamException.class, () -> Params.fromSpring(null));
    }

    @Test
    void emptyEnvironmentReturnsEmpty() {
        Map<String, Object> map = new SpringParams(new MockEnvironment(), null).asMap();
        assertTrue(map.isEmpty());
    }
}