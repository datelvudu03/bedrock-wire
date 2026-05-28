package cz.syntea.bedrock.wire.template.source;

import cz.syntea.bedrock.wire.template.exception.TemplateParamException;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link Params} source bound to a Spring {@link Environment}. Reads all properties
 * matching the configured prefix (or every property if no prefix is given), strips the
 * prefix, and rebuilds the remaining dot-separated key segments into a nested-map
 * structure.
 *
 * <p>Example with prefix {@code "monitor.check.x."}:
 * <pre>
 *   monitor.check.x.a       = 1   →  {a: "1"}
 *   monitor.check.x.b.c     = 2   →  {b: {c: "2"}}
 * </pre>
 *
 * <h3>Prefix policy (changed in 1.0.6.0)</h3>
 * Prior to 1.0.6.0 the prefix was REQUIRED at the type level. From 1.0.6.0 a {@code null}
 * or blank prefix is treated as <b>no prefix filter</b> — every key returned by the
 * environment's enumerable property sources is exposed. The change supports the
 * {@code bedrock-wire-monitor} v3 template-context model, whose layer-1 Spring binding
 * defaults to no prefix. See the Architecture-template ADR
 * <i>"SpringParams accepts a blank prefix"</i> for the full rationale.
 *
 * <p><b>Security note.</b> The earlier structural guarantee that no {@code SpringParams}
 * instance could expose {@code spring.datasource.password} (or other Spring-bound secrets)
 * is no longer enforced. Callers SHOULD pass a non-blank prefix unless they have
 * independently audited the Spring {@link Environment} and accepted exposure of every key.
 *
 * @since 1.0
 */
public final class SpringParams implements Params {

    private final Environment environment;
    private final String prefix;

    /**
     * Creates a new instance.
     *
     * <p>Pass a non-blank {@code prefix} to filter properties to a known configuration
     * section; pass {@code null} or blank to expose every property in the environment
     * (see class-level security note).
     *
     * @param environment Spring environment; never {@code null}
     * @param prefix      property prefix to filter and strip; {@code null} or blank means
     *                    no filter (every property is exposed)
     * @throws TemplateParamException if {@code environment} is {@code null}
     */
    public SpringParams(Environment environment, String prefix) {
        if (environment == null) {
            throw new TemplateParamException("SpringParams: environment MUST NOT be null");
        }
        this.environment = environment;
        this.prefix = (prefix == null || prefix.isBlank()) ? "" : prefix;
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
     * prefix (no filter if the prefix is empty), and resolves each via
     * {@link Environment#getProperty(String)} (so placeholders are honored). Stripped tail
     * keys are reassembled into a nested-map structure.
     *
     * @return resolved parameter map
     */
    @Override
    public Map<String, Object> asMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : enumerateKeys()) {
            if (!prefix.isEmpty() && !key.startsWith(prefix)) {
                continue;
            }
            String tail = prefix.isEmpty() ? key : key.substring(prefix.length());
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
                    keys.addAll(Arrays.asList(eps.getPropertyNames()));
                }
            }
        }
        return keys;
    }
}