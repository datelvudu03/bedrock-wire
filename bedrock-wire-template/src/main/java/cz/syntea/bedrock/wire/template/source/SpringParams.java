package cz.syntea.bedrock.wire.template.source;

import cz.syntea.bedrock.wire.template.exception.TemplateParamException;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link Params} source bound to a Spring {@link Environment}. Reads all properties
 * matching the configured prefix, strips the prefix, and rebuilds the remaining
 * dot-separated key segments into a nested-map structure.
 *
 * <p>Example with prefix {@code "monitor.check.x."}:
 * <pre>
 *   monitor.check.x.a       = 1   →  {a: "1"}
 *   monitor.check.x.b.c     = 2   →  {b: {c: "2"}}
 * </pre>
 *
 * <p>The prefix is REQUIRED. There is no overload that exposes the entire environment —
 * this is a deliberate security choice to prevent leaking unrelated properties (e.g.
 * datasource passwords) into rendered templates.
 *
 * @since 1.0
 */
public final class SpringParams implements Params {

    private final Environment environment;
    private final String prefix;

    /**
     * Creates a new instance.
     *
     * @param environment Spring environment; never {@code null}
     * @param prefix      property prefix; never {@code null} or blank
     * @throws TemplateParamException if {@code environment} is {@code null} or
     *                                {@code prefix} is {@code null} / blank
     */
    public SpringParams(Environment environment, String prefix) {
        if (environment == null) {
            throw new TemplateParamException("SpringParams: environment MUST NOT be null");
        }
        if (prefix == null || prefix.isBlank()) {
            throw new TemplateParamException(
                    "SpringParams: prefix is REQUIRED (security: prevents leaking unrelated "
                            + "properties such as datasource passwords)");
        }
        this.environment = environment;
        this.prefix = prefix;
    }

    @SuppressWarnings("unchecked")
    private static void insertNested(Map<String, Object> root, String dottedKey, String value) {
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

    /**
     * {@inheritDoc}
     *
     * <p>Iterates {@link EnumerablePropertySource}s of the environment, filters keys by
     * prefix, and resolves each via {@link Environment#getProperty(String)} (so
     * placeholders are honored). Stripped tail keys are reassembled into a nested-map
     * structure.
     *
     * @return resolved parameter map
     */
    @Override
    public Map<String, Object> asMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : enumerateKeys()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String tail = key.substring(prefix.length());
            if (tail.isEmpty()) {
                continue;
            }
            String value = environment.getProperty(key);
            if (value != null) {
                insertNested(result, tail, value);
            }
        }
        return result;
    }

    private Iterable<String> enumerateKeys() {
        var keys = new java.util.LinkedHashSet<String>();
        if (environment instanceof ConfigurableEnvironment ce) {
            for (PropertySource<?> ps : ce.getPropertySources()) {
                if (ps instanceof EnumerablePropertySource<?> eps) {
                    for (String name : eps.getPropertyNames()) {
                        keys.add(name);
                    }
                }
            }
        }
        return keys;
    }
}