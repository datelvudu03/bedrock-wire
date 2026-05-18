package cz.syntea.bedrock.wire.template.source;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Scans a resolved configuration graph for template-visible variables.
 *
 * <p>Given a {@link Properties} instance — typically a {@code PropertiesCfg} whose
 * {@code ${...}} chains and {@code env.}/{@code sys.} builtins are already resolved —
 * this utility selects the keys that should be exposed to the FreeMarker model and
 * returns them as a flat map suitable for {@link Params#of(Map)}.
 *
 * <h3>Selection rules</h3>
 * <ul>
 *   <li><b>Framework namespaces are excluded.</b> Keys under {@code monitor.*} and
 *       {@code bedrock.wire.monitor.*} are configuration for the monitor and its
 *       Spring auto-configuration, not template variables; they are never exposed.</li>
 *   <li><b>All other non-blank keys are exposed verbatim.</b> Including keys whose
 *       names contain dots (e.g. {@code RUN.MODE}, {@code USER.TYPE}). The map is
 *       <b>flat</b> — a dotted key becomes a single flat entry, not a nested hash.</li>
 *   <li><b>Blank values are treated as absent.</b> A key whose resolved value is
 *       {@code null} or blank is omitted, so the template MAY supply a default via
 *       {@code ${name!'...'}}.</li>
 * </ul>
 *
 * <h3>Referencing dotted keys in a template</h3>
 * FreeMarker reads an unescaped dot as hash dereference, so the literal expression
 * {@code ${RUN.MODE}} would be interpreted as "property {@code MODE} of variable
 * {@code RUN}" — which is not what a flat dotted key represents. To read a flat
 * dotted key, escape the dot with a backslash (FreeMarker &ge; 2.3.22):
 *
 * <pre>
 *   ${RUN\.MODE}         &lt;-- reads the flat key "RUN.MODE"
 *   ${USER\.TYPE}        &lt;-- reads the flat key "USER.TYPE"
 *   ${.vars['RUN.MODE']} &lt;-- equivalent bracket-access form
 * </pre>
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
     *         string values; never {@code null}, may be empty
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