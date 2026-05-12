package cz.syntea.bedrock.wire.template.source;

import cz.syntea.bedrock.wire.template.exception.TemplateParamException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Inline {@link Params} source backed by an explicit {@link Map}. The supplied map is
 * defensively copied at construction; subsequent external mutation has no effect.
 *
 * @since 1.0
 */
public final class MapParams implements Params {

    private final Map<String, Object> map;

    /**
     * Creates a new instance from the given map.
     *
     * @param map parameter key-value pairs; never {@code null}
     * @throws TemplateParamException if {@code map} is {@code null}
     */
    public MapParams(Map<String, Object> map) {
        if (map == null) {
            throw new TemplateParamException("MapParams: source map MUST NOT be null");
        }
        this.map = new LinkedHashMap<>(map);
    }

    /**
     * {@inheritDoc}
     *
     * @return defensively copied snapshot of the parameter map
     */
    @Override
    public Map<String, Object> asMap() {
        return new LinkedHashMap<>(map);
    }
}