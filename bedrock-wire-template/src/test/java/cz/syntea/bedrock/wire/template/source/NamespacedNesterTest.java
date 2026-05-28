package cz.syntea.bedrock.wire.template.source;

import cz.syntea.bedrock.wire.template.exception.TemplateParamException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link NamespacedNester}: flat-to-nested conversion and namespace wrapping.
 */
class NamespacedNesterTest {

    /**
     * Traverses a nested map by dotted-style path arguments.
     */
    @SuppressWarnings("unchecked")
    private static Object deep(Map<String, Object> root, String... path) {
        Object cursor = root;
        for (String s : path) {
            assertNotNull(cursor, "expected map at intermediate step");
            if (!(cursor instanceof Map<?, ?> m)) {
                return null;
            }
            cursor = ((Map<String, Object>) m).get(s);
        }
        return cursor;
    }

    @Test
    void nestFlatKeyStaysFlat() {
        Map<String, Object> result = NamespacedNester.nest(Map.of("a", "1", "b", "2"));
        assertEquals("1", result.get("a"));
        assertEquals("2", result.get("b"));
    }

    @Test
    void nestDottedKeyBecomesNested() {
        Map<String, Object> result = NamespacedNester.nest(Map.of("a.b.c", "x"));
        assertEquals("x", deep(result, "a", "b", "c"));
    }

    @Test
    void nestSharedPrefixMerges() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("a.b", "1");
        in.put("a.c", "2");
        Map<String, Object> result = NamespacedNester.nest(in);

        assertEquals("1", deep(result, "a", "b"));
        assertEquals("2", deep(result, "a", "c"));
    }

    @Test
    void nestPreservesAlreadyNestedValues() {
        Map<String, Object> nested = Map.of("inner", "v");
        Map<String, Object> result = NamespacedNester.nest(Map.of("outer", nested));
        assertEquals("v", deep(result, "outer", "inner"));
    }

    @Test
    void nestEmptyInputReturnsEmpty() {
        Map<String, Object> result = NamespacedNester.nest(Map.of());
        assertTrue(result.isEmpty());
    }

    @Test
    void nestNullThrows() {
        assertThrows(TemplateParamException.class, () -> NamespacedNester.nest(null));
    }

    @Test
    void underNamespaceSingleSegment() {
        Map<String, Object> result =
                NamespacedNester.underNamespace("ns", Map.of("a", "1"));
        assertEquals("1", deep(result, "ns", "a"));
    }

    @Test
    void underNamespaceDottedSegmentBecomesNesting() {
        Map<String, Object> result =
                NamespacedNester.underNamespace("service.payments", Map.of("a", "1"));
        assertEquals("1", deep(result, "service", "payments", "a"));
        assertNull(deep(result, "service.payments"));
    }

    @Test
    void underNamespacePreservesNestedInput() {
        Map<String, Object> nested = Map.of("a", "1", "b", Map.of("c", "2"));
        Map<String, Object> result = NamespacedNester.underNamespace("default", nested);
        assertEquals("1", deep(result, "default", "a"));
        assertEquals("2", deep(result, "default", "b", "c"));
    }

    @Test
    void underNamespaceNullThrows() {
        assertThrows(TemplateParamException.class, () ->
                NamespacedNester.underNamespace(null, Map.of()));
        assertThrows(TemplateParamException.class, () ->
                NamespacedNester.underNamespace("", Map.of()));
        assertThrows(TemplateParamException.class, () ->
                NamespacedNester.underNamespace("x", null));
    }
}