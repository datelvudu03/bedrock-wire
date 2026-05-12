package cz.syntea.bedrock.wire.template.source;

import cz.syntea.bedrock.wire.template.exception.TemplateParamException;
import org.springframework.core.env.Environment;

import java.nio.file.Path;
import java.util.Map;

/**
 * Parameter source for template rendering. Sealed — only the four built-in source types
 * are permitted. Use the static factories below to construct instances.
 *
 * <p>Implementations MUST resolve parameters lazily where possible; {@link #asMap()} is
 * called once per render by {@code TemplateRenderer}.
 *
 * <h3>Sources</h3>
 * <ul>
 *   <li>{@link #of(Map)}                — inline programmatic parameters</li>
 *   <li>{@link #fromJson(Path)}         — JSON file with nested objects</li>
 *   <li>{@link #fromSpring(Environment, String)} — Spring properties under a required prefix</li>
 *   <li>{@link #combined(Params...)}    — deep-merge of multiple sources; last wins</li>
 * </ul>
 *
 * @since 1.0
 */
public sealed interface Params permits MapParams, JsonParams, SpringParams, CombinedParams {

    /**
     * Wraps an explicit parameter map. The map is defensively copied; subsequent mutation
     * of the original does not affect the returned source.
     *
     * @param map parameter key-value pairs; never {@code null}, may be empty
     * @return a new {@link MapParams} instance
     * @throws TemplateParamException if {@code map} is {@code null}
     */
    static Params of(Map<String, Object> map) {
        return new MapParams(map);
    }

    /**
     * Loads parameters from a JSON file. The file is read on first {@link #asMap()} call
     * (UTF-8) and cached for subsequent calls. The root JSON value MUST be an object;
     * nested objects are exposed as nested maps and accessed via dot-notation in
     * templates ({@code ${a.b.c}}).
     *
     * <p>Flat dotted keys (e.g. {@code "a.b": "x"}) are NOT supported — use nested objects.
     *
     * @param jsonFile path to the JSON file; never {@code null}
     * @return a new {@link JsonParams} instance
     */
    static Params fromJson(Path jsonFile) {
        return new JsonParams(jsonFile);
    }

    /**
     * Reads parameters from the Spring {@link Environment} under the given prefix. The
     * prefix is stripped from each property key; remaining dotted segments become nested
     * maps. Example: with prefix {@code "monitor.check.x."}, the property
     * {@code monitor.check.x.a.b = 1} resolves to {@code {a: {b: "1"}}}.
     *
     * <p>The prefix is REQUIRED to prevent accidental exposure of sensitive properties
     * (datasource passwords, secrets). There is no overload without a prefix.
     *
     * @param env    Spring {@link Environment} to read from; never {@code null}
     * @param prefix property prefix to filter and strip; never {@code null} or blank
     * @return a new {@link SpringParams} instance
     * @throws TemplateParamException if {@code prefix} is {@code null} or blank
     */
    static Params fromSpring(Environment env, String prefix) {
        return new SpringParams(env, prefix);
    }

    /**
     * Deep-merges multiple sources in argument order. Later sources overwrite earlier
     * ones on key collision; nested maps are merged recursively.
     *
     * @param sources sources to merge; never {@code null} or empty
     * @return a new {@link CombinedParams} instance
     * @throws TemplateParamException if {@code sources} is {@code null} or empty
     */
    static Params combined(Params... sources) {
        return new CombinedParams(sources);
    }

    /**
     * Resolves and returns the parameter map. Top-level keys are directly addressable in
     * templates; nested {@link Map} values are traversed via dot-notation.
     *
     * @return resolved parameter map; never {@code null}
     * @throws TemplateParamException when this source cannot be resolved (e.g. malformed JSON)
     */
    Map<String, Object> asMap();
}