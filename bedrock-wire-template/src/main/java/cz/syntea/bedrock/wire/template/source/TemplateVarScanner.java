package cz.syntea.bedrock.wire.template.source;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Scans a resolved configuration graph for template-visible variables.
 *
 * <p>Given a {@link Properties} instance — typically a {@code PropertiesCfg} whose
 * {@code ${...}} chains and {@code env.}/{@code sys.} builtins are already resolved —
 * this utility selects the keys that may be referenced directly from a template as
 * bare FreeMarker variables ({@code ${name}}) and returns them as a flat model map
 * suitable for {@link Params#of(Map)}.
 *
 * <h3>Selection rules</h3>
 * <ul>
 *   <li><b>Framework namespaces are excluded.</b> Keys under {@code monitor.*} and
 *       {@code bedrock.wire.monitor.*} are configuration for the monitor and its
 *       Spring auto-configuration, not template variables; they are never exposed.</li>
 *   <li><b>Only legal bare identifiers are exposed.</b> A key is included only if it
 *       matches {@code ^[A-Za-z_][A-Za-z0-9_]*$}. Dotted keys ({@code a.b}) are
 *       skipped: FreeMarker reads {@code ${a.b}} as hash access, so a flat dotted
 *       key is unreachable as a bare variable. Skipping is silent — a configuration
 *       file legitimately contains many dotted keys that were never intended as
 *       template variables.</li>
 *   <li><b>Blank values are treated as absent.</b> A key whose resolved value is
 *       {@code null} or blank is omitted, so the template MAY supply a default via
 *       {@code ${name!'...'}}.</li>
 * </ul>
 *
 * <h3>Note on framework coupling</h3>
 * The excluded prefixes ({@code monitor.}, {@code bedrock.wire.monitor.}) are
 * hard-coded here by deliberate decision: it keeps the standalone API a single
 * zero-argument-policy call ({@link #scan(Properties)}) at the cost of this module
 * carrying knowledge of the monitor's namespace.
 *
 * @since 1.0
 */
public final class TemplateVarScanner {

    /**
     * Configuration-key prefixes that are framework configuration, never template
     * variables. Keys under these prefixes are excluded from the scanned model.
     */
    public static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "monitor.",
            "bedrock.wire.monitor."
    );

    private static final Pattern LEGAL_BARE_IDENTIFIER = Pattern.compile(
            "^[A-Za-z_][A-Za-z0-9_]*$"
    );

    private TemplateVarScanner() {
    }

    /**
     * Scans the given configuration graph and returns the template-visible variables
     * as an immutable flat map.
     *
     * <p>The map is intended to be passed straight to {@link Params#of(Map)}, or
     * composed with per-template parameters via {@link Params#combined(Params...)}
     * (the scanned variables forming the lowest-precedence layer).
     *
     * @param cfg the resolved configuration graph; typically a {@code PropertiesCfg}.
     *            Must not be {@code null}. Values are read via
     *            {@link Properties#getProperty(String)}, so for a {@code PropertiesCfg}
     *            all variable substitution has already been applied.
     * @return immutable map of template-visible variable names to their resolved
     * string values; never {@code null}, may be empty
     * @throws IllegalArgumentException if {@code cfg} is {@code null}
     */
    public static Map<String, Object> scan(Properties cfg) {
        if (cfg == null) {
            throw new IllegalArgumentException("cfg must not be null");
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        for (String key : cfg.stringPropertyNames()) {
            if (isExcludedNamespace(key)) {
                continue;
            }
            if (!LEGAL_BARE_IDENTIFIER.matcher(key).matches()) {
                continue;
            }
            String value = cfg.getProperty(key);
            if (value != null && !value.isBlank()) {
                vars.put(key, value.trim());
            }
        }
        return Map.copyOf(vars);
    }

    private static boolean isExcludedNamespace(String key) {
        for (String prefix : EXCLUDED_PREFIXES) {
            if (key.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}