package cz.syntea.bedrock.wire.template.source;

import cz.syntea.bedrock.wire.template.exception.TemplateParamException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Utility for building nested-map structures from flat dotted-key inputs and wrapping
 * them under a single top-level namespace.
 *
 * <p>Used by callers that compose a template model from layered configuration sources
 * where each layer must be addressable under a stable namespace (e.g. a per-service
 * configuration file exposed to templates as {@code ${service.payments.…}}).
 *
 * <h3>Example</h3>
 * <pre>
 *   Map&lt;String, Object&gt; flat = Map.of("a", "1", "b.c", "2");
 *   Map&lt;String, Object&gt; nested = NamespacedNester.nest(flat);
 *   // → {a: "1", b: {c: "2"}}
 *
 *   Map&lt;String, Object&gt; wrapped = NamespacedNester.underNamespace("service.payments", nested);
 *   // → {service: {payments: {a: "1", b: {c: "2"}}}}
 * </pre>
 *
 * <h3>Merge semantics</h3>
 * Within {@link #nest(Map)}, when two flat keys share a prefix (e.g. {@code a.b} and
 * {@code a.c}), they are merged into a common nested map. A scalar value at a parent
 * path is replaced by a nested map if any descendant key is present; the LAST entry in
 * iteration order wins on direct collision.
 *
 * <p>Within {@link #underNamespace(String, Map)}, the namespace itself is split on
 * dots and each segment becomes a level of nesting.
 *
 * @since 1.0.6.0
 */
public final class NamespacedNester {

    private NamespacedNester() {
    }

    /**
     * Turns a flat map of dotted keys into a nested-map structure.
     *
     * <p>Values are inserted as-is; non-{@link String} values are preserved unchanged
     * (this allows already-nested {@code Map} values to flow through, e.g. when the
     * caller mixes JSON-derived nested maps with properties-derived flat keys).
     *
     * @param flat flat map whose keys may contain dots; never {@code null}
     * @return a new nested-map structure; never {@code null}, may be empty
     * @throws TemplateParamException if {@code flat} is {@code null}
     */
    public static Map<String, Object> nest(Map<String, Object> flat) {
        if (flat == null) {
            throw new TemplateParamException("NamespacedNester.nest: flat map MUST NOT be null");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : flat.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || key.isEmpty()) {
                continue;
            }
            insertDotted(result, key, value);
        }
        return result;
    }

    /**
     * Wraps the given model under a top-level namespace. The namespace is split on dots;
     * each segment becomes a nesting level.
     *
     * <p>Example: namespace {@code "service.payments"} with model {@code {a: 1}} returns
     * {@code {service: {payments: {a: 1}}}}.
     *
     * @param namespace dotted namespace path; never {@code null} or blank
     * @param model     the model to place at the deepest namespace level; never {@code null}
     * @return a new map with the model nested under {@code namespace}; never {@code null}
     * @throws TemplateParamException if {@code namespace} is {@code null} or blank, or
     *                                {@code model} is {@code null}
     */
    public static Map<String, Object> underNamespace(String namespace, Map<String, Object> model) {
        if (namespace == null || namespace.isBlank()) {
            throw new TemplateParamException(
                    "NamespacedNester.underNamespace: namespace MUST NOT be null or blank");
        }
        if (model == null) {
            throw new TemplateParamException(
                    "NamespacedNester.underNamespace: model MUST NOT be null");
        }
        String[] segments = namespace.split("\\.");
        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> cursor = root;
        for (int i = 0; i < segments.length - 1; i++) {
            Map<String, Object> next = new LinkedHashMap<>();
            cursor.put(segments[i], next);
            cursor = next;
        }
        cursor.put(segments[segments.length - 1], model);
        return root;
    }

    @SuppressWarnings("unchecked")
    private static void insertDotted(Map<String, Object> root, String dottedKey, Object value) {
        String[] parts = dottedKey.split("\\.");
        Map<String, Object> cursor = root;
        for (int i = 0; i < parts.length - 1; i++) {
            Object existing = cursor.get(parts[i]);
            Map<String, Object> next;
            if (existing instanceof Map<?, ?> existingMap) {
                next = (Map<String, Object>) existingMap;
            } else {
                next = new LinkedHashMap<>();
                cursor.put(parts[i], next);
            }
            cursor = next;
        }
        cursor.put(parts[parts.length - 1], value);
    }
}