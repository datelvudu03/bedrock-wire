package cz.syntea.bedrock.wire.template.source;

import cz.syntea.bedrock.wire.template.exception.TemplateParamException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Deep-merging composite of multiple {@link Params} sources. Sources are resolved in
 * argument order; later sources overwrite earlier ones on key collision. Nested maps
 * are merged recursively rather than replaced wholesale.
 *
 * <p>Example:
 * <pre>
 *   {a: {b: 1}}        ⊕  {a: {c: 2}}        =  {a: {b: 1, c: 2}}
 *   {a: 1}             ⊕  {a: 2}             =  {a: 2}
 *   {a: {b: 1}}        ⊕  {a: 2}             =  {a: 2}    (scalar wins, replaces map)
 * </pre>
 *
 * @since 1.0
 */
public final class CombinedParams implements Params {

    private final Params[] sources;

    /**
     * Creates a new combined source.
     *
     * @param sources sources to merge in order; never {@code null} or empty
     * @throws TemplateParamException if {@code sources} is {@code null} or empty
     */
    public CombinedParams(Params... sources) {
        if (sources == null || sources.length == 0) {
            throw new TemplateParamException("CombinedParams: sources MUST NOT be null or empty");
        }
        for (int i = 0; i < sources.length; i++) {
            if (sources[i] == null) {
                throw new TemplateParamException(
                        "CombinedParams: source at index " + i + " MUST NOT be null");
            }
        }
        this.sources = sources.clone();
    }

    @SuppressWarnings("unchecked")
    private static void deepMerge(Map<String, Object> target, Map<String, Object> overlay) {
        for (Map.Entry<String, Object> entry : overlay.entrySet()) {
            String key = entry.getKey();
            Object overlayValue = entry.getValue();
            Object existing = target.get(key);
            if (existing instanceof Map<?, ?> existingMap && overlayValue instanceof Map<?, ?> overlayMap) {
                Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) existingMap);
                deepMerge(merged, (Map<String, Object>) overlayMap);
                target.put(key, merged);
            } else {
                target.put(key, overlayValue);
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>Resolves each constituent source via {@link Params#asMap()} and deep-merges
     * the results in argument order.
     *
     * @return merged parameter map
     */
    @Override
    public Map<String, Object> asMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Params source : sources) {
            deepMerge(result, source.asMap());
        }
        return result;
    }
}