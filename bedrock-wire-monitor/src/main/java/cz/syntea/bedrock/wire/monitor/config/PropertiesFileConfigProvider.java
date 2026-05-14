package cz.syntea.bedrock.wire.monitor.config;

import cz.syntea.bedrock.wire.monitor.model.HttpMethod;
import cz.syntea.bedrock.wire.template.source.TemplateVarScanner;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Default {@link MonitorConfigProvider} implementation that parses the
 * {@code monitor.*} namespace from a standalone {@code .properties} file.
 *
 * <p>Implements the three-level lookup for monitor-owned parameters
 * (check → service → default), case-insensitive header merging, Duration
 * parsing with required time units, transport property extraction, and
 * TLS profile parsing.
 *
 * <h3>Validation (fail-fast)</h3>
 * <ul>
 *   <li>{@code serviceName} and {@code checkName} MUST be unique.</li>
 *   <li>Every {@code CheckConfig.serviceName} MUST reference an existing service.</li>
 *   <li>Every alias in {@code validation.validators} MUST be present in the
 *       provided set of known validator aliases.</li>
 *   <li>Every check MUST have a resolved {@code interval} (from check, service, or default).</li>
 *   <li>Duration values MUST include a time unit (e.g. {@code 5s}, {@code 500ms}).</li>
 * </ul>
 */
@Slf4j
public class PropertiesFileConfigProvider implements MonitorConfigProvider {

    private static final String PREFIX = "monitor.";
    private static final String DEFAULT_PREFIX = PREFIX + "default.";
    private static final String SERVICE_PREFIX = PREFIX + "service.";
    private static final String CHECK_PREFIX = PREFIX + "check.";
    private static final String TLS_PREFIX = PREFIX + "tls.";
    private static final String EXECUTOR_PREFIX = PREFIX + "executor.";

    private static final String PARAM_RETRY_DELAY = "retry.delay";

    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);
    private static final HttpMethod DEFAULT_METHOD = HttpMethod.POST;
    private static final int DEFAULT_RETRY_COUNT = 0;

    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "^(\\d+)(ms|s|m|h)$"
    );

    private final List<CheckConfig> checks;
    private final List<ServiceConfig> services;
    private final List<TlsProfileConfig> tlsProfiles;
    private final Duration shutdownTimeout;
    private final Map<String, Object> templateEnv;

    /**
     * Creates a new provider by parsing the given properties file.
     *
     * @param configFilePath   path to the {@code .properties} file; must not be {@code null}
     * @param validatorAliases set of known validator aliases for validation; must not be {@code null}
     * @throws IllegalArgumentException     if the configuration is invalid
     * @throws java.io.UncheckedIOException if the file cannot be read
     */
    public PropertiesFileConfigProvider(Path configFilePath, Set<String> validatorAliases) {
        this(loadProperties(configFilePath), validatorAliases);
    }

    /**
     * Creates a new provider from an already-loaded {@link Properties} instance.
     *
     * <p>This constructor is designed to accept any {@code Properties} subclass
     * (e.g. {@code PropertiesCfg}) that has already been loaded and resolved.
     * The {@code monitor.*} namespace is extracted from the given properties;
     * all other keys are ignored.
     *
     * <p>When a {@code PropertiesCfg} instance is passed, variable substitution
     * ({@code ${...}}, {@code ${env.*}}, etc.) is already performed by
     * {@code PropertiesCfg.getProperty()}, so the monitor receives fully
     * resolved values.
     *
     * @param props            properties containing the {@code monitor.*} namespace;
     *                         must not be {@code null}
     * @param validatorAliases set of known validator aliases for validation;
     *                         must not be {@code null}
     * @throws IllegalArgumentException if the configuration is invalid
     */
    public PropertiesFileConfigProvider(Properties props, Set<String> validatorAliases) {
        if (props == null) {
            throw new IllegalArgumentException("Properties must not be null");
        }
        if (validatorAliases == null) {
            throw new IllegalArgumentException("Validator aliases must not be null");
        }

        Map<String, String> monitorProps = extractMonitorProperties(props);

        // Environment-passthrough variables: every legal bare-identifier key in the
        // full configuration graph that is NOT under a framework namespace is exposed
        // to all templates. Scanned from the raw props (a resolved PropertiesCfg), so
        // ${...} chains and env./sys. builtins are already applied. See spec §2.9.4.
        this.templateEnv = TemplateVarScanner.scan(props);

        Map<String, String> defaults = extractByPrefix(monitorProps, "default.");
        Map<String, Map<String, String>> serviceRawMap = extractGrouped(monitorProps, "service.");
        Map<String, Map<String, String>> checkRawMap = extractGrouped(monitorProps, "check.");
        Map<String, Map<String, String>> tlsRawMap = extractGrouped(monitorProps, "tls.");

        this.services = parseServices(serviceRawMap, defaults);
        validateServiceUniqueness(this.services);

        Map<String, ServiceConfig> serviceIndex = new HashMap<>();
        for (ServiceConfig sc : this.services) {
            serviceIndex.put(sc.getServiceName(), sc);
        }

        this.checks = parseChecks(checkRawMap, serviceIndex, defaults, this.templateEnv, validatorAliases);
        validateCheckUniqueness(this.checks);

        this.tlsProfiles = parseTlsProfiles(tlsRawMap);
        this.shutdownTimeout = parseDurationOrDefault(
                monitorProps.get("executor.shutdownTimeout"), DEFAULT_SHUTDOWN_TIMEOUT, "executor.shutdownTimeout"
        );

        log.info("Monitor configuration loaded: {} services, {} checks, {} TLS profiles",
                this.services.size(), this.checks.size(), this.tlsProfiles.size());
        logConfigurationSummary();
    }

    /**
     * Logs a detailed, human-readable summary of the loaded configuration
     * at INFO level. Designed to give operators immediate visibility into
     * what the monitor will do at startup.
     */
    private void logConfigurationSummary() {
        StringBuilder sb = new StringBuilder();

        sb.append("\n                           MONITOR CONFIGURATION SUMMARY                         ");


        // Services
        sb.append("\n  Services (").append(services.size()).append("):");
        if (services.isEmpty()) {
            sb.append("\n    (none)");
        } else {
            for (ServiceConfig svc : services) {
                sb.append("\n    ").append(svc.getServiceName())
                        .append(" → ").append(svc.getUrl());
                if (!svc.getTransportProperties().isEmpty()) {
                    sb.append(" [");
                    svc.getTransportProperties().forEach((k, v) ->
                            sb.append(k).append("=").append(v).append(", "));
                    sb.setLength(sb.length() - 2); // remove trailing ", "
                    sb.append("]");
                }
            }
        }

        // Checks
        sb.append("\n  Checks (").append(checks.size()).append("):");
        if (checks.isEmpty()) {
            sb.append("\n    (none)");
        } else {
            for (CheckConfig chk : checks) {
                sb.append("\n    ").append(chk.getCheckName())
                        .append(" → service=").append(chk.getServiceName())
                        .append(", ").append(chk.getMethod())
                        .append(" ").append(chk.getPath() != null ? chk.getPath() : "/");
                if (chk.getQuery() != null) {
                    sb.append("?").append(chk.getQuery());
                }
                sb.append(", interval=").append(formatDuration(chk.getInterval()));
                if (chk.getRetryCount() > 0) {
                    sb.append(", retry=").append(chk.getRetryCount())
                            .append("×").append(formatDuration(chk.getRetryDelay()));
                }
                if (!chk.getValidators().isEmpty()) {
                    sb.append(", validators=").append(chk.getValidators());
                }
                if (chk.getTemplateFile() != null) {
                    sb.append(", template=").append(chk.getTemplateFile());
                }
            }
        }

        // TLS profiles
        sb.append("\n  TLS profiles (").append(tlsProfiles.size()).append("):");
        if (tlsProfiles.isEmpty()) {
            sb.append("\n    (none)");
        } else {
            for (TlsProfileConfig tls : tlsProfiles) {
                sb.append("\n    ").append(tls.getProfileName());
                if (tls.getClientCert() != null) {
                    sb.append(" [mTLS: ").append(tls.getClientCertType())
                            .append(" ").append(tls.getClientCert()).append("]");
                }
                if (tls.getTrustStore() != null) {
                    sb.append(" [trustStore: ").append(tls.getTrustStoreType())
                            .append(" ").append(tls.getTrustStore()).append("]");
                }
                if (!tls.isHostnameVerification()) {
                    sb.append(" [hostnameVerification=DISABLED]");
                }
            }
        }

        // Shutdown
        sb.append("\n  Shutdown timeout: ").append(formatDuration(shutdownTimeout));
        log.info("{}", sb);
    }

    /**
     * Formats a {@link Duration} as a human-readable string (e.g. "30s", "500ms", "2m").
     *
     * @param duration the duration to format; may be {@code null}
     * @return a human-readable representation, or {@code "null"} if {@code duration} is {@code null}
     */
    private static String formatDuration(Duration duration) {
        if (duration == null) {
            return "null";
        }
        long millis = duration.toMillis();
        if (millis < 1000) {
            return millis + "ms";
        }
        long seconds = duration.toSeconds();
        if (seconds < 60) {
            return seconds + "s";
        }
        return duration.toMinutes() + "m";
    }

    /**
     * Parses a duration string with a required time unit.
     *
     * <p>Supported formats: {@code 500ms}, {@code 5s}, {@code 2m}, {@code 1h}.
     * A bare number without a unit is rejected.
     *
     * @param value     the duration string to parse
     * @param paramName parameter name for error messages
     * @return the parsed duration; never {@code null}
     * @throws IllegalArgumentException if the format is invalid
     */
    public static Duration parseDuration(String value, String paramName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Duration value for '" + paramName + "' must not be empty");
        }
        String trimmed = value.trim();
        Matcher matcher = DURATION_PATTERN.matcher(trimmed);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Invalid duration for '" + paramName + "': '" + trimmed
                            + "'. Expected format: <number><unit> (ms, s, m, h). "
                            + "Bare numbers without unit are not allowed.");
        }
        long amount = Long.parseLong(matcher.group(1));
        String unit = matcher.group(2);
        return switch (unit) {
            case "ms" -> Duration.ofMillis(amount);
            case "s" -> Duration.ofSeconds(amount);
            case "m" -> Duration.ofMinutes(amount);
            case "h" -> Duration.ofHours(amount);
            default -> throw new IllegalArgumentException("Unknown time unit: " + unit);
        };
    }

    @Override
    public List<CheckConfig> getChecks() {
        return checks;
    }

    @Override
    public List<ServiceConfig> getServices() {
        return services;
    }

    public List<TlsProfileConfig> getTlsProfiles() {
        return tlsProfiles;
    }

    // ── Properties file loading ─────────────────────────────────────────────

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    /**
     * Returns the environment-passthrough variables scanned from the full
     * configuration graph (spec §2.9.4): every legal bare-identifier key outside
     * the {@code monitor.*} and {@code bedrock.wire.monitor.*} namespaces. These
     * are merged into every check's {@code templateParams} as the lowest-precedence
     * layer.
     *
     * @return immutable map of environment variables; never {@code null}, may be empty
     */
    public Map<String, Object> getTemplateEnv() {
        return templateEnv;
    }

    private static Properties loadProperties(Path path) {
        if (path == null) {
            throw new IllegalArgumentException("Config file path must not be null");
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Config file does not exist: " + path);
        }
        Properties props = new Properties();
        try (InputStream is = Files.newInputStream(path)) {
            props.load(is);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException("Failed to load config file: " + path, e);
        }
        return props;
    }

    // ── Extraction helpers ──────────────────────────────────────────────────

    private Map<String, String> extractMonitorProperties(Properties props) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith(PREFIX)) {
                result.put(key.substring(PREFIX.length()), props.getProperty(key).trim());
            }
        }
        return result;
    }

    /**
     * Extracts all keys with a given prefix into a flat map (prefix stripped).
     *
     * @param props  the source map
     * @param prefix the prefix to match and strip
     * @return a new map containing the matching entries with the prefix removed
     */
    private Map<String, String> extractByPrefix(Map<String, String> props, String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : props.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return result;
    }

    /**
     * Copies entries from {@code source} into {@code target}, skipping entries
     * whose value is {@code null} or blank.
     *
     * <p>Used for template-parameter merging so that an empty value (e.g. an
     * unresolved {@code ${...}} placeholder that collapsed to an empty string)
     * behaves as if the key were not present: it neither shadows a per-check
     * {@code param.*} value nor defeats a template-level {@code ${name!'default'}}.
     *
     * @param target the map to copy non-blank entries into
     * @param source the map to read entries from
     */
    private void putNonBlank(Map<String, String> target, Map<String, String> source) {
        for (Map.Entry<String, String> e : source.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) {
                target.put(e.getKey(), e.getValue());
            }
        }
    }

    /**
     * Copies entries from an {@code Object}-valued {@code source} into a
     * {@code String}-valued {@code target}, skipping entries whose value is
     * {@code null} or (after {@code toString()}) blank.
     *
     * <p>Used to merge the scanned {@code templateEnv} layer (typed
     * {@code Map<String, Object>} to match the template engine's model type) into
     * the {@code String}-valued {@code templateParams} map. In practice
     * {@link TemplateVarScanner} only ever emits trimmed, non-blank {@code String}
     * values, so this is a straightforward copy.
     *
     * @param target the {@code String}-valued map to copy non-blank entries into
     * @param source the {@code Object}-valued map to read entries from
     */
    private void putNonBlankObjects(Map<String, String> target, Map<String, Object> source) {
        for (Map.Entry<String, Object> e : source.entrySet()) {
            Object value = e.getValue();
            if (value != null && !value.toString().isBlank()) {
                target.put(e.getKey(), value.toString());
            }
        }
    }

    // ── Service parsing ─────────────────────────────────────────────────────

    /**
     * Groups properties by the name segment after the prefix.
     * E.g. {@code service.payments.url} &rarr; group "payments", key "url".
     *
     * @param props  the source map
     * @param prefix the grouping prefix
     * @return a map of group name to the group's key/value entries
     */
    private Map<String, Map<String, String>> extractGrouped(Map<String, String> props, String prefix) {
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : props.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith(prefix)) {
                continue;
            }
            String remainder = key.substring(prefix.length());
            int dot = remainder.indexOf('.');
            if (dot < 0) {
                continue;
            }
            String groupName = remainder.substring(0, dot);
            String paramKey = remainder.substring(dot + 1);
            groups.computeIfAbsent(groupName, k -> new LinkedHashMap<>())
                    .put(paramKey, entry.getValue());
        }
        return groups;
    }

    // ── Check parsing ───────────────────────────────────────────────────────

    private List<ServiceConfig> parseServices(
            Map<String, Map<String, String>> serviceRawMap,
            Map<String, String> defaults) {

        List<ServiceConfig> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : serviceRawMap.entrySet()) {
            String name = entry.getKey();
            validateNotReserved(name, "service");
            Map<String, String> raw = entry.getValue();

            String urlStr = raw.get("url");
            if (urlStr == null || urlStr.isBlank()) {
                throw new IllegalArgumentException(
                        "Service '" + name + "' is missing required parameter 'url'");
            }

            Duration interval = lookupDuration(raw.get("interval"),
                    defaults.get("interval"), "interval", name);

            Map<String, String> headers = mergeHeaders(
                    extractHeaders(defaults),
                    extractHeaders(raw)
            );

            Map<String, String> transportProps = extractByPrefix(raw, "transport.");

            result.add(ServiceConfig.builder()
                    .serviceName(name)
                    .url(URI.create(urlStr))
                    .interval(interval)
                    .headers(Map.copyOf(headers))
                    .transportProperties(Map.copyOf(transportProps))
                    .build());
        }
        return List.copyOf(result);
    }

    // ── TLS profile parsing ─────────────────────────────────────────────────

    private List<CheckConfig> parseChecks(
            Map<String, Map<String, String>> checkRawMap,
            Map<String, ServiceConfig> serviceIndex,
            Map<String, String> defaults,
            Map<String, Object> templateEnv,
            Set<String> validatorAliases) {

        List<CheckConfig> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : checkRawMap.entrySet()) {
            String checkName = entry.getKey();
            validateNotReserved(checkName, "check");
            Map<String, String> raw = entry.getValue();

            String serviceName = raw.get("service");
            if (serviceName == null || serviceName.isBlank()) {
                throw new IllegalArgumentException(
                        "Check '" + checkName + "' is missing required parameter 'service'");
            }

            ServiceConfig serviceConfig = serviceIndex.get(serviceName);
            if (serviceConfig == null) {
                throw new IllegalArgumentException(
                        "Check '" + checkName + "' references unknown service '" + serviceName + "'");
            }

            // 3-level lookup for interval: check → service → default
            // Service interval is already parsed as Duration — do not convert back to string.
            Duration interval = null;
            String checkInterval = raw.get("interval");
            if (checkInterval != null && !checkInterval.isBlank()) {
                interval = parseDuration(checkInterval.trim(), "interval for '" + checkName + "'");
            } else if (serviceConfig.getInterval() != null) {
                interval = serviceConfig.getInterval();
            } else {
                String defaultInterval = defaults.get("interval");
                if (defaultInterval != null && !defaultInterval.isBlank()) {
                    interval = parseDuration(defaultInterval.trim(), "interval for '" + checkName + "'");
                }
            }
            if (interval == null) {
                throw new IllegalArgumentException(
                        "Check '" + checkName + "' has no resolved interval "
                                + "(not defined at check, service, or default level)");
            }

            HttpMethod method = parseMethod(
                    lookupString(raw.get("method"), defaults.get("method")),
                    checkName
            );

            int retryCount = lookupInt(raw.get("retry.count"), defaults.get("retry.count"), DEFAULT_RETRY_COUNT);

            Duration retryDelay = lookupDuration(raw.get(PARAM_RETRY_DELAY),
                    defaults.get(PARAM_RETRY_DELAY), PARAM_RETRY_DELAY, checkName);
            if (retryDelay == null) {
                retryDelay = DEFAULT_RETRY_DELAY;
            }

            boolean retryOnIoError = parseBooleanOrDefault(
                    lookupString(raw.get("retry.ioError"), defaults.get("retry.ioError")),
                    false);

            // Header merge: default → service → check
            Map<String, String> headers = mergeHeaders(
                    extractHeaders(defaults),
                    serviceConfig.getHeaders(),
                    extractHeaders(raw)
            );

            // Validators
            String validatorsStr = lookupString(
                    raw.get("validation.validators"),
                    defaults.get("validation.validators")
            );
            List<String> validatorList = parseValidatorList(validatorsStr);
            validateValidatorAliases(validatorList, validatorAliases, checkName);

            // Validation params (strip "validation." prefix, exclude "validation.validators")
            Map<String, String> validationParams = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : raw.entrySet()) {
                if (e.getKey().startsWith("validation.") && !e.getKey().equals("validation.validators")) {
                    validationParams.put(e.getKey().substring("validation.".length()), e.getValue());
                }
            }

            // Template params: merge templateEnv → default param.* → check param.*.
            // templateEnv (scanned environment-passthrough vars, spec §2.9.4) is the
            // lowest-precedence layer — a param.* key of the same name overrides it.
            // Blank values are treated as absent so an unresolved ${...} placeholder
            // does not shadow a higher-precedence value.
            Map<String, String> templateParams = new LinkedHashMap<>();
            putNonBlankObjects(templateParams, templateEnv);
            putNonBlank(templateParams, extractByPrefix(defaults, "param."));
            putNonBlank(templateParams, extractByPrefix(raw, "param."));

            result.add(CheckConfig.builder()
                    .checkName(checkName)
                    .serviceName(serviceName)
                    .method(method)
                    .path(raw.get("path"))
                    .query(raw.get("query"))
                    .templateFile(raw.get("templateFile"))
                    .retryCount(retryCount)
                    .retryDelay(retryDelay)
                    .retryOnIoError(retryOnIoError)
                    .interval(interval)
                    .headers(Map.copyOf(headers))
                    .validators(List.copyOf(validatorList))
                    .validationParams(Map.copyOf(validationParams))
                    .templateParams(Map.copyOf(templateParams))
                    .build());
        }
        return List.copyOf(result);
    }

    // ── Header merging ──────────────────────────────────────────────────────

    private List<TlsProfileConfig> parseTlsProfiles(Map<String, Map<String, String>> tlsRawMap) {
        List<TlsProfileConfig> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : tlsRawMap.entrySet()) {
            String profileName = entry.getKey();
            Map<String, String> raw = entry.getValue();

            result.add(TlsProfileConfig.builder()
                    .profileName(profileName)
                    .clientCert(raw.get("clientCert"))
                    .clientCertPassword(raw.get("clientCertPassword"))
                    .clientCertType(raw.get("clientCertType"))
                    .clientCertAlias(raw.get("clientCertAlias"))
                    .trustStore(raw.get("trustStore"))
                    .trustStorePassword(raw.get("trustStorePassword"))
                    .trustStoreType(raw.get("trustStoreType"))
                    .hostnameVerification(
                            parseBooleanOrDefault(raw.get("hostnameVerification"), true))
                    .allowInsecureInProduction(
                            parseBooleanOrDefault(raw.get("allowInsecureInProduction"), false))
                    .build());
        }
        return List.copyOf(result);
    }

    /**
     * Extracts header entries from a property map.
     * Keys matching {@code header.<name>} are extracted with the {@code header.} prefix stripped.
     *
     * @param raw the source property map
     * @return a map of header name to header value
     */
    private Map<String, String> extractHeaders(Map<String, String> raw) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (e.getKey().startsWith("header.")) {
                headers.put(e.getKey().substring("header.".length()), e.getValue());
            }
        }
        return headers;
    }

    // ── Lookup helpers (3-level) ────────────────────────────────────────────

    /**
     * Merges header maps with case-insensitive key comparison.
     * Later maps override earlier maps for the same header name (case-insensitive).
     *
     * @param layers the header maps to merge, in increasing precedence order
     * @return a new map with the merged headers
     */
    @SafeVarargs
    private Map<String, String> mergeHeaders(Map<String, String>... layers) {
        // TreeMap with case-insensitive ordering preserves last-wins semantics
        TreeMap<String, String> merged = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map<String, String> layer : layers) {
            if (layer != null) {
                merged.putAll(layer);
            }
        }
        return new LinkedHashMap<>(merged);
    }

    private String lookupString(String... candidates) {
        for (String c : candidates) {
            if (c != null && !c.isBlank()) {
                return c.trim();
            }
        }
        return null;
    }

    private Duration lookupDuration(String checkVal, String defaultVal,
                                    String paramName, String contextName) {
        return lookupDuration(checkVal, null, defaultVal, paramName, contextName);
    }

    private Duration lookupDuration(String checkVal, String serviceVal, String defaultVal,
                                    String paramName, String contextName) {
        String resolved = lookupString(checkVal, serviceVal, defaultVal);
        if (resolved == null) {
            return null;
        }
        return parseDuration(resolved, paramName + " for '" + contextName + "'");
    }

    private Duration parseDurationOrDefault(String value, Duration defaultValue, String paramName) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return parseDuration(value, paramName);
    }

    // ── Duration parsing ────────────────────────────────────────────────────

    private int lookupInt(String checkVal, String defaultVal, int fallback) {
        String resolved = lookupString(checkVal, defaultVal);
        if (resolved == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(resolved);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid integer value: '" + resolved + "'");
        }
    }

    // ── Method parsing ──────────────────────────────────────────────────────

    private HttpMethod parseMethod(String value, String checkName) {
        if (value == null || value.isBlank()) {
            return DEFAULT_METHOD;
        }
        try {
            return HttpMethod.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Check '" + checkName + "' has unsupported HTTP method: '" + value + "'");
        }
    }

    // ── Validator parsing ───────────────────────────────────────────────────

    private List<String> parseValidatorList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private void validateValidatorAliases(List<String> aliases, Set<String> known, String checkName) {
        for (String alias : aliases) {
            if (!known.contains(alias)) {
                throw new IllegalArgumentException(
                        "Check '" + checkName + "' references unknown validator alias: '" + alias
                                + "'. Known aliases: " + known);
            }
        }
    }

    // ── Boolean parsing ─────────────────────────────────────────────────────

    private boolean parseBooleanOrDefault(String value, boolean defaultValue) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    // ── Validation ──────────────────────────────────────────────────────────

    private void validateNotReserved(String name, String type) {
        if ("default".equalsIgnoreCase(name)) {
            throw new IllegalArgumentException(
                    "'" + name + "' is a reserved name and must not be used as a " + type + " name");
        }
    }

    private void validateServiceUniqueness(List<ServiceConfig> services) {
        Set<String> seen = new LinkedHashSet<>();
        for (ServiceConfig sc : services) {
            if (!seen.add(sc.getServiceName())) {
                throw new IllegalArgumentException(
                        "Duplicate service name: '" + sc.getServiceName() + "'");
            }
        }
    }

    private void validateCheckUniqueness(List<CheckConfig> checks) {
        Set<String> seen = new LinkedHashSet<>();
        for (CheckConfig cc : checks) {
            if (!seen.add(cc.getCheckName())) {
                throw new IllegalArgumentException(
                        "Duplicate check name: '" + cc.getCheckName() + "'");
            }
        }
    }
}