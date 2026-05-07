package cz.syntea.bedrock.wire.template.source;

import cz.syntea.bedrock.wire.template.exception.TemplateParamException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CombinedParamsTest {

    @Test
    void shouldMergeNonOverlappingKeys() {
        var combined = new CombinedParams(
                Params.of(Map.of("a", "1")),
                Params.of(Map.of("b", "2")));
        var map = combined.asMap();
        assertEquals("1", map.get("a"));
        assertEquals("2", map.get("b"));
    }

    @Test
    void shouldOverrideOnCollisionLastWins() {
        var combined = new CombinedParams(
                Params.of(Map.of("a", "first")),
                Params.of(Map.of("a", "second")),
                Params.of(Map.of("a", "third")));
        assertEquals("third", combined.asMap().get("a"));
    }

    @Test
    void shouldDeepMergeNestedMaps() {
        var combined = new CombinedParams(
                Params.of(Map.of("a", Map.of("b", "1"))),
                Params.of(Map.of("a", Map.of("c", "2"))));
        var a = assertInstanceOf(Map.class, combined.asMap().get("a"));
        assertEquals("1", a.get("b"));
        assertEquals("2", a.get("c"));
    }

    @Test
    void shouldReplaceMapWithScalar() {
        var combined = new CombinedParams(
                Params.of(Map.of("a", Map.of("b", "1"))),
                Params.of(Map.of("a", "scalar")));
        assertEquals("scalar", combined.asMap().get("a"));
    }

    @Test
    void shouldThrowOnEmptySources() {
        assertThrows(TemplateParamException.class, CombinedParams::new);
    }

    @Test
    void shouldThrowOnNullSourceArray() {
        assertThrows(TemplateParamException.class,
                () -> new CombinedParams((Params[]) null));
    }

    @Test
    void shouldThrowOnNullSourceElement() {
        assertThrows(TemplateParamException.class,
                () -> new CombinedParams(Params.of(Map.of("a", "1")), null));
    }
}