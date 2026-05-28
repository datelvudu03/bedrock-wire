package cz.syntea.bedrock.wire.monitor.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import cz.syntea.bedrock.wire.monitor.model.HttpMethod;
import cz.syntea.bedrock.wire.template.source.NamespacedNester;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
 * {@code monitor.*} namespace from a {@link Properties} instance (typically a
 * resolved {@code PropertiesCfg}) or a standalone {@code .properties} file.
 *
 * <p>Constructed via the {@link #builder()} — required: {@code props} and
 * {@code validatorAliases}; optional: Spring {@link Environment} (enables layer 1
 * of the v3 template-context model), {@code configFileRoot} {@link Path}
 * (controls relative file-path resolution).
 *
 * <h3>Path resolution (changed in 1.0.6.0)</h3>
 * Relative file paths referenced from configuration
 * ({@code monitor.tls.<n>.clientCert}, {@code monitor.tls.<n>.trustStore},
 * {@code monitor.{default|service|check}.config-file},
 * {@code monitor.check.<n>.templateFile}) are now resolved against the directory of
 * the root {@code .param} file when known — not the JVM working directory. The
 * path-based constructor sets the root automatically; the builder accepts an
 * explicit {@link Builder#configFileRoot(Path)}. When no root is set, paths remain
 * resolved against the JVM working directory and a WARN is logged.
 *
 * <h3>Template context (spec §2.9, 8-layer model)</h3>
 * Each check's {@link CheckConfig#getTemplateParams() templateParams} is the
 * deep-merge of eight layers, lowest precedence to highest:
 * <ol start="0">
 *   <li>Bare-key scan of the {@code .param} graph outside the framework
 *       namespaces ({@code monitor.*}, {@code bedrock.wire.monitor.*}) — flat</li>
 *   <li>Spring {@link Environment} under the resolved {@code springEnvPrefix} (nested)</li>
 *   <li>{@code monitor.default.config-file} contents (namespaced under {@code default.})</li>
 *   <li>{@code monitor.default.param.*} (flat)</li>
 *   <li>{@code monitor.service.<name>.config-file} contents (namespaced under
 *       {@code service.<name>.})</li>
 *   <li>{@code monitor.service.<name>.param.*} (flat)</li>
 *   <li>{@code monitor.check.<name>.config-file} contents (namespaced under
 *       {@code check.<name>.})</li>
 *   <li>{@code monitor.check.<name>.param.*} (flat)</li>
 * </ol>
 *
 * <p>Layer 0 (the bare-key scan) exposes the entire {@code .param} graph outside
 * the framework namespaces, flat, with no per-key opt-in. It exists for drop-in
 * compatibility with consumers migrating from 1.0.5.x and is the lowest precedence —
 * any {@code param.*}, {@code config-file}, or Spring value overrides it. See the
 * leak note on {@code scanBareKeys}.
 *
 * <p>{@code springEnvPrefix} is configurable at all three levels
 * ({@code monitor.{default|service|check}.springEnvPrefix}); the resolved value
 * follows check → service → default precedence with empty-string default (no
 * filter, every Spring property exposed). Spring data still enters at layer 1
 * only — the per-level key only changes which prefix is used.
 *
 * <h3>Reserved {@code param.*} names</h3>
 * The names {@code default}, {@code service}, and {@code check} are reserved as
 * top-level template keys for the namespaced config-file layers. A {@code param.*}
 * key whose first dotted segment is one of those three names causes fail-fast at
 * parse time.
 *
 * <h3>Validation (fail-fast)</h3>
 * <ul>
 *   <li>{@code serviceName} and {@code checkName} MUST be unique.</li>
 *   <li>Every {@code CheckConfig.serviceName} MUST reference an existing service.</li>
 *   <li>Every alias in {@code validation.validators} MUST be present in the
 *       provided set of known validator aliases.</li>
 *   <li>Every check MUST have a resolved {@code interval}.</li>
 *   <li>Duration values MUST include a time unit.</li>
 *   <li>Referenced {@code config-file} paths MUST exist and be readable.</li>
 *   <li>{@code param.*} top-level names MUST NOT be {@code default},
 *       {@code service}, or {@code check}.</li>
 * </ul>
 */
@Slf4j
public class PropertiesFileConfigProvider implements MonitorConfigProvider {

    private static final String PREFIX = "monitor.";
    private static final String EXECUTOR_PREFIX = "executor.";
    private static final String PARAM_RETRY_DELAY = "retry.delay";
    private static final String KEY_CONFIG_FILE = "config-file";
    private static final String KEY_SPRING_ENV_PREFIX = "springEnvPrefix";

    private static final Set<String> RESERVED_PARAM_NAMES =
            Set.of("default", "service", "check");

    /**
     * Prefixes excluded from the layer-0 bare-key scan (spec §2.9.0). Keys under
     * these prefixes are framework configuration and never reach templates via the
     * scan. Everything else in the resolved {@code .param} graph is exposed flat.
     */
    private static final Set<String> SCAN_EXCLUDED_PREFIXES =
            Set.of("monitor.", "bedrock.wire.monitor.");

    private static final Duration DEFAULT_RETRY_DELAY = Duration.ofSeconds(1);
    private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);
    private static final HttpMethod DEFAULT_METHOD = HttpMethod.POST;
    private static final int DEFAULT_RETRY_COUNT = 0;

    private static final Pattern DURATION_PATTERN = Pattern.compile("^(\\d+)(ms|s|m|h)$");

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<CheckConfig> checks;
    private final List<ServiceConfig> services;
    private final List<TlsProfileConfig> tlsProfiles;
    private final Duration shutdownTimeout;

    // ── Construction ────────────────────────────────────────────────────────

    /**
     * Convenience constructor: loads the file, infers {@code configFileRoot} from
     * its parent directory, no Spring {@link Environment}.
     *
     * @param configFilePath   path to the {@code .properties} / {@code .param} file
     * @param validatorAliases set of known validator aliases
     * @throws IllegalArgumentException     if the configuration is invalid
     * @throws java.io.UncheckedIOException if the file cannot be read
     */
    public PropertiesFileConfigProvider(Path configFilePath, Set<String> validatorAliases) {
        this(builder()
                .props(loadProperties(configFilePath))
                .validatorAliases(validatorAliases)
                .configFileRoot(configFilePath.toAbsolutePath().getParent()));
    }

    /**
     * Convenience constructor for in-memory configuration with no path context and
     * no Spring environment. Relative file paths will be resolved against the JVM
     * working directory.
     *
     * @param props            properties containing the {@code monitor.*} namespace
     * @param validatorAliases set of known validator aliases
     * @throws IllegalArgumentException if the configuration is invalid
     */
    public PropertiesFileConfigProvider(Properties props, Set<String> validatorAliases) {
        this(builder().props(props).validatorAliases(validatorAliases));
    }

    private PropertiesFileConfigProvider(Builder b) {
        if (b.props == null) {
            throw new IllegalArgumentException("Properties must not be null");
        }
        if (b.validatorAliases == null) {
            throw new IllegalArgumentException("Validator aliases must not be null");
        }

        Properties props = b.props;
        Set<String> validatorAliases = b.validatorAliases;
        Path configFileRoot = b.configFileRoot;
        Environment env = b.env;

        Map<String, String> monitorProps = extractMonitorProperties(props);

        Map<String, String> defaults = extractByPrefix(monitorProps, "default.");
        Map<String, Map<String, String>> serviceRawMap = extractGrouped(monitorProps, "service.");
        Map<String, Map<String, String>> checkRawMap = extractGrouped(monitorProps, "check.");
        Map<String, Map<String, String>> tlsRawMap = extractGrouped(monitorProps, "tls.");

        if (configFileRoot == null) {
            log.warn("PropertiesFileConfigProvider: no configFileRoot supplied; "
                            + "relative TLS / config-file / templateFile paths resolve "
                            + "against JVM working dir: {}",
                    Paths.get("").toAbsolutePath());
        }

        // Layer 0 — bare-key scan of the whole .param graph (spec §2.9.0).
        Map<String, String> scannedBareKeys = scanBareKeys(props);

        this.services = parseServices(serviceRawMap, defaults);
        validateServiceUniqueness(this.services);

        Map<String, ServiceConfig> serviceIndex = new HashMap<>();
        for (ServiceConfig sc : this.services) {
            serviceIndex.put(sc.getServiceName(), sc);
        }

        this.checks = parseChecks(checkRawMap, serviceRawMap, serviceIndex, defaults,
                validatorAliases, configFileRoot, env, scannedBareKeys);
        validateCheckUniqueness(this.checks);

        this.tlsProfiles = parseTlsProfiles(tlsRawMap, configFileRoot);
        this.shutdownTimeout = parseDurationOrDefault(
                monitorProps.get(EXECUTOR_PREFIX + "shutdownTimeout"),
                DEFAULT_SHUTDOWN_TIMEOUT,
                EXECUTOR_PREFIX + "shutdownTimeout");

        log.info("Monitor configuration loaded: {} services, {} checks, {} TLS profiles",
                this.services.size(), this.checks.size(), this.tlsProfiles.size());
        logConfigurationSummary();
    }

    /**
     * Creates a new {@link Builder}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Parses a duration string with a required time unit ({@code 500ms}, {@code 5s},
     * {@code 2m}, {@code 1h}).
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

    private static Map<String, Object> asObjectMap(Map<String, String> in) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : in.entrySet()) {
            if (e.getValue() != null && !e.getValue().isBlank()) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void deepMerge(Map<String, Object> target, Map<String, Object> overlay) {
        for (Map.Entry<String, Object> e : overlay.entrySet()) {
            String key = e.getKey();
            Object overlayValue = e.getValue();
            Object existing = target.get(key);
            if (existing instanceof Map<?, ?> existingMap
                    && overlayValue instanceof Map<?, ?> overlayMap) {
                Map<String, Object> merged = new LinkedHashMap<>((Map<String, Object>) existingMap);
                deepMerge(merged, (Map<String, Object>) overlayMap);
                target.put(key, merged);
            } else {
                target.put(key, overlayValue);
            }
        }
    }

    @Override
    public List<CheckConfig> getChecks() {
        return checks;
    }

    @Override
    public List<ServiceConfig> getServices() {
        return services;
    }

    // ── Configuration summary ───────────────────────────────────────────────

    public List<TlsProfileConfig> getTlsProfiles() {
        return tlsProfiles;
    }

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

    // ── Properties file loading ─────────────────────────────────────────────

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

    public Duration getShutdownTimeout() {
        return shutdownTimeout;
    }

    private Map<String, String> extractByPrefix(Map<String, String> props, String prefix) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : props.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                result.put(entry.getKey().substring(prefix.length()), entry.getValue());
            }
        }
        return result;
    }

    private void logConfigurationSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("\n                           MONITOR CONFIGURATION SUMMARY                         ");

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
                    sb.setLength(sb.length() - 2);
                    sb.append("]");
                }
            }
        }

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

        sb.append("\n  Shutdown timeout: ").append(formatDuration(shutdownTimeout));
        log.info("{}", sb);
    }

    /**
     * Layer-0 bare-key scan (spec §2.9.0). Exposes every non-blank key in the
     * resolved {@code .param} graph as a flat template variable, EXCEPT keys under
     * the framework prefixes in {@link #SCAN_EXCLUDED_PREFIXES}.
     *
     * <p>Behaviour (preserved from the pre-1.0.6.0 scanned-env feature):
     * <ul>
     *   <li>Keys are exposed <b>flat</b> — a dotted key such as {@code RUN.MODE}
     *       stays as the single key {@code "RUN.MODE"} (read from a template via
     *       {@code ${RUN\.MODE}} or {@code ${.vars['RUN.MODE']}}), it is NOT nested.</li>
     *   <li>Blank values are skipped (treated as absent so templates may use
     *       {@code ${name!'default'}}).</li>
     *   <li>Values are read via {@link Properties#getProperty(String)} so a
     *       {@code PropertiesCfg} resolves {@code ${...}} chains before exposure.</li>
     * </ul>
     *
     * <p><b>Leak note.</b> This layer exposes the entire {@code .param} graph
     * outside the framework namespaces with no per-key opt-in. The exposure is
     * intentional and accepted (drop-in compatibility for consumers migrating from
     * 1.0.5.x). Operators who want a tighter perimeter should prefer the explicit
     * {@code param.*} or {@code config-file} layers and keep sensitive values out
     * of the {@code .param} graph entirely.
     *
     * @param props the full resolved property graph; never {@code null}
     * @return flat map of scanned bare keys; never {@code null}, may be empty
     */
    private Map<String, String> scanBareKeys(Properties props) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String key : props.stringPropertyNames()) {
            boolean excluded = false;
            for (String prefix : SCAN_EXCLUDED_PREFIXES) {
                if (key.startsWith(prefix)) {
                    excluded = true;
                    break;
                }
            }
            if (excluded) {
                continue;
            }
            String value = props.getProperty(key);
            if (value == null || value.isBlank()) {
                continue;
            }
            result.put(key, value.trim());
        }
        if (!result.isEmpty()) {
            log.info("Layer-0 bare-key scan exposed {} template variable(s): {}",
                    result.size(), result.keySet());
        }
        return result;
    }

    // ── Service parsing ─────────────────────────────────────────────────────

    private Map<String, Map<String, String>> extractGrouped(Map<String, String> props,
                                                            String prefix) {
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

    /**
     * Resolves a possibly-relative file path against the configured root. Returns
     * the absolute path string; does NOT validate existence.
     */
    private String resolvePath(String raw, Path root) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        Path candidate = Paths.get(raw.trim());
        if (candidate.isAbsolute()) {
            return candidate.toString();
        }
        Path base = (root != null) ? root : Paths.get("").toAbsolutePath();
        return base.resolve(candidate).normalize().toAbsolutePath().toString();
    }

    // ── Template model assembly (spec §2.9 — 7 layers) ──────────────────────

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

            Map<String, String> serviceParams = extractByPrefix(raw, "param.");
            validateReservedParamNames(serviceParams, "service '" + name + "'");

            result.add(ServiceConfig.builder()
                    .serviceName(name)
                    .url(URI.create(urlStr))
                    .interval(interval)
                    .headers(Map.copyOf(headers))
                    .transportProperties(Map.copyOf(transportProps))
                    .templateParams(Map.copyOf(serviceParams))
                    .build());
        }
        return List.copyOf(result);
    }

    private List<CheckConfig> parseChecks(
            Map<String, Map<String, String>> checkRawMap,
            Map<String, Map<String, String>> serviceRawMap,
            Map<String, ServiceConfig> serviceIndex,
            Map<String, String> defaults,
            Set<String> validatorAliases,
            Path configFileRoot,
            Environment env,
            Map<String, String> scannedBareKeys) {

        Map<String, String> defaultParams = extractByPrefix(defaults, "param.");
        validateReservedParamNames(defaultParams, "default");

        Map<String, Object> defaultConfigFileNested = loadConfigFileNamespaced(
                defaults.get(KEY_CONFIG_FILE), "default", configFileRoot, "default-level");

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
                        "Check '" + checkName + "' references unknown service '"
                                + serviceName + "'");
            }
            Map<String, String> serviceRaw = serviceRawMap.getOrDefault(serviceName, Map.of());

            Duration interval;
            String checkInterval = raw.get("interval");
            if (checkInterval != null && !checkInterval.isBlank()) {
                interval = parseDuration(checkInterval.trim(),
                        "interval for '" + checkName + "'");
            } else if (serviceConfig.getInterval() != null) {
                interval = serviceConfig.getInterval();
            } else {
                String defaultInterval = defaults.get("interval");
                if (defaultInterval == null || defaultInterval.isBlank()) {
                    throw new IllegalArgumentException(
                            "Check '" + checkName + "' has no resolved interval "
                                    + "(not defined at check, service, or default level)");
                }
                interval = parseDuration(defaultInterval.trim(),
                        "interval for '" + checkName + "'");
            }

            HttpMethod method = parseMethod(
                    lookupString(raw.get("method"), defaults.get("method")),
                    checkName);

            int retryCount = lookupInt(raw.get("retry.count"), defaults.get("retry.count"),
                    DEFAULT_RETRY_COUNT);

            Duration retryDelay = lookupDuration(raw.get(PARAM_RETRY_DELAY),
                    defaults.get(PARAM_RETRY_DELAY), PARAM_RETRY_DELAY, checkName);
            if (retryDelay == null) {
                retryDelay = DEFAULT_RETRY_DELAY;
            }

            boolean retryOnIoError = parseBooleanOrDefault(
                    lookupString(raw.get("retry.ioError"), defaults.get("retry.ioError")),
                    false);

            Map<String, String> headers = mergeHeaders(
                    extractHeaders(defaults),
                    serviceConfig.getHeaders(),
                    extractHeaders(raw)
            );

            String validatorsStr = lookupString(
                    raw.get("validation.validators"),
                    defaults.get("validation.validators"));
            List<String> validatorList = parseValidatorList(validatorsStr);
            validateValidatorAliases(validatorList, validatorAliases, checkName);

            Map<String, String> validationParams = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : raw.entrySet()) {
                if (e.getKey().startsWith("validation.")
                        && !e.getKey().equals("validation.validators")) {
                    validationParams.put(e.getKey().substring("validation.".length()),
                            e.getValue());
                }
            }

            Map<String, Object> templateModel = buildTemplateModel(
                    checkName, serviceName, raw, serviceRaw, defaults, serviceConfig,
                    defaultParams, defaultConfigFileNested, configFileRoot, env,
                    scannedBareKeys);

            result.add(CheckConfig.builder()
                    .checkName(checkName)
                    .serviceName(serviceName)
                    .method(method)
                    .path(raw.get("path"))
                    .query(raw.get("query"))
                    .templateFile(resolvePath(raw.get("templateFile"), configFileRoot))
                    .retryCount(retryCount)
                    .retryDelay(retryDelay)
                    .retryOnIoError(retryOnIoError)
                    .interval(interval)
                    .headers(Map.copyOf(headers))
                    .validators(List.copyOf(validatorList))
                    .validationParams(Map.copyOf(validationParams))
                    .templateParams(Map.copyOf(templateModel))
                    .build());
        }
        return List.copyOf(result);
    }

    private Map<String, Object> buildTemplateModel(
            String checkName,
            String serviceName,
            Map<String, String> checkRaw,
            Map<String, String> serviceRaw,
            Map<String, String> defaults,
            ServiceConfig serviceConfig,
            Map<String, String> defaultParams,
            Map<String, Object> defaultConfigFileNested,
            Path configFileRoot,
            Environment env,
            Map<String, String> scannedBareKeys) {

        Map<String, Object> model = new LinkedHashMap<>();

        String resolvedPrefix = lookupString(
                checkRaw.get(KEY_SPRING_ENV_PREFIX),
                serviceRaw.get(KEY_SPRING_ENV_PREFIX),
                defaults.get(KEY_SPRING_ENV_PREFIX)
        );
        if (resolvedPrefix == null) {
            resolvedPrefix = "";
        }

        // Layer 0 — bare-key scan of the .param graph (flat, lowest precedence).
        // Inserted directly (not via NamespacedNester) to preserve flat dotted keys:
        // RUN.MODE stays the single key "RUN.MODE", read from a template as ${RUN\.MODE}.
        model.putAll(scannedBareKeys);

        // Layer 1 — Spring Environment.
        if (env != null) {
            Map<String, Object> springLayer = cz.syntea.bedrock.wire.template.source.Params
                    .fromSpring(env, resolvedPrefix.isEmpty() ? null : resolvedPrefix)
                    .asMap();
            deepMerge(model, springLayer);
        }

        // Layer 2 — default.config-file (namespaced under "default.").
        deepMerge(model, defaultConfigFileNested);

        // Layer 3 — default.param.* (flat, nested by dotted keys).
        deepMerge(model, NamespacedNester.nest(asObjectMap(defaultParams)));

        // Layer 4 — service.<n>.config-file (namespaced under "service.<n>.").
        deepMerge(model, loadConfigFileNamespaced(
                serviceRaw.get(KEY_CONFIG_FILE),
                "service." + serviceName,
                configFileRoot,
                "service '" + serviceName + "'"));

        // Layer 5 — service.<n>.param.* (flat).
        deepMerge(model, NamespacedNester.nest(asObjectMap(serviceConfig.getTemplateParams())));

        // Layer 6 — check.<n>.config-file (namespaced under "check.<n>.").
        deepMerge(model, loadConfigFileNamespaced(
                checkRaw.get(KEY_CONFIG_FILE),
                "check." + checkName,
                configFileRoot,
                "check '" + checkName + "'"));

        // Layer 7 — check.<n>.param.* (flat).
        Map<String, String> checkParams = extractByPrefix(checkRaw, "param.");
        validateReservedParamNames(checkParams, "check '" + checkName + "'");
        deepMerge(model, NamespacedNester.nest(asObjectMap(checkParams)));

        return model;
    }

    private Map<String, Object> loadConfigFileNamespaced(String rawPath, String namespace,
                                                         Path configFileRoot,
                                                         String contextLabel) {
        if (rawPath == null || rawPath.isBlank()) {
            return Map.of();
        }
        String resolved = resolvePath(rawPath, configFileRoot);
        Path file = Paths.get(resolved);
        if (!Files.exists(file)) {
            throw new IllegalArgumentException(
                    "config-file for " + contextLabel + " does not exist: " + resolved);
        }
        Map<String, Object> parsed = parseConfigFile(file, contextLabel);
        return NamespacedNester.underNamespace(namespace, parsed);
    }

    private Map<String, Object> parseConfigFile(Path file, String contextLabel) {
        String name = file.getFileName().toString().toLowerCase();
        try {
            if (name.endsWith(".json")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = JSON.readValue(file.toFile(), Map.class);
                return map != null ? map : Map.of();
            }
            if (name.endsWith(".properties") || name.endsWith(".param")) {
                Properties p = new Properties();
                try (InputStream is = Files.newInputStream(file)) {
                    p.load(is);
                }
                Map<String, Object> flat = new LinkedHashMap<>();
                for (String key : p.stringPropertyNames()) {
                    flat.put(key, p.getProperty(key));
                }
                return NamespacedNester.nest(flat);
            }
            throw new IllegalArgumentException(
                    "config-file for " + contextLabel + " has unsupported extension: " + file
                            + ". Supported: .json, .properties, .param");
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    "Failed to read config-file for " + contextLabel + ": " + file, e);
        }
    }

    // ── TLS parsing ─────────────────────────────────────────────────────────

    private List<TlsProfileConfig> parseTlsProfiles(Map<String, Map<String, String>> tlsRawMap,
                                                    Path configFileRoot) {
        List<TlsProfileConfig> result = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> entry : tlsRawMap.entrySet()) {
            String profileName = entry.getKey();
            Map<String, String> raw = entry.getValue();

            result.add(TlsProfileConfig.builder()
                    .profileName(profileName)
                    .clientCert(resolvePath(raw.get("clientCert"), configFileRoot))
                    .clientCertPassword(raw.get("clientCertPassword"))
                    .clientCertType(raw.get("clientCertType"))
                    .clientCertAlias(raw.get("clientCertAlias"))
                    .trustStore(resolvePath(raw.get("trustStore"), configFileRoot))
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

    // ── Header helpers ──────────────────────────────────────────────────────

    private Map<String, String> extractHeaders(Map<String, String> raw) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : raw.entrySet()) {
            if (e.getKey().startsWith("header.")) {
                headers.put(e.getKey().substring("header.".length()), e.getValue());
            }
        }
        return headers;
    }

    @SafeVarargs
    private Map<String, String> mergeHeaders(Map<String, String>... layers) {
        TreeMap<String, String> merged = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Map<String, String> layer : layers) {
            if (layer != null) {
                merged.putAll(layer);
            }
        }
        return new LinkedHashMap<>(merged);
    }

    // ── Lookup helpers ──────────────────────────────────────────────────────

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
        String resolved = lookupString(checkVal, defaultVal);
        if (resolved == null) {
            return null;
        }
        return parseDuration(resolved, paramName + " for '" + contextName + "'");
    }

    private Duration parseDurationOrDefault(String value, Duration defaultValue,
                                            String paramName) {
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return parseDuration(value, paramName);
    }

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

    private List<String> parseValidatorList(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private void validateValidatorAliases(List<String> aliases, Set<String> known,
                                          String checkName) {
        for (String alias : aliases) {
            if (!known.contains(alias)) {
                throw new IllegalArgumentException(
                        "Check '" + checkName + "' references unknown validator alias: '"
                                + alias + "'. Known aliases: " + known);
            }
        }
    }

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
                    "'" + name + "' is a reserved name and must not be used as a "
                            + type + " name");
        }
    }

    /**
     * Fails fast if any {@code param.*} key has a first dotted segment equal to
     * {@code default}, {@code service}, or {@code check} — those names are reserved
     * as top-level template keys for the namespaced config-file layers.
     */
    private void validateReservedParamNames(Map<String, String> params, String context) {
        for (String key : params.keySet()) {
            String first = key.contains(".") ? key.substring(0, key.indexOf('.')) : key;
            if (RESERVED_PARAM_NAMES.contains(first)) {
                throw new IllegalArgumentException(
                        "param.* at " + context + " uses reserved top-level name '" + first
                                + "' (reserved: " + RESERVED_PARAM_NAMES
                                + "). These names are used by the config-file layers in "
                                + "the v3 template-context model (spec §2.9).");
            }
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

    // ── Builder ─────────────────────────────────────────────────────────────

    /**
     * Builder for {@link PropertiesFileConfigProvider}.
     * Required: {@link #props(Properties)}, {@link #validatorAliases(Set)}.
     * Optional: {@link #configFileRoot(Path)} for relative-path resolution,
     * {@link #environment(Environment)} for layer 1 of the template-context model.
     */
    public static final class Builder {

        private Properties props;
        private Set<String> validatorAliases;
        private Path configFileRoot;
        private Environment env;

        private Builder() {
        }

        /**
         * Sets the source properties (typically a resolved {@code PropertiesCfg}).
         *
         * @param props source properties; never {@code null}
         * @return this builder
         */
        public Builder props(Properties props) {
            this.props = props;
            return this;
        }

        /**
         * Sets the set of known validator aliases.
         *
         * @param aliases known aliases; never {@code null}
         * @return this builder
         */
        public Builder validatorAliases(Set<String> aliases) {
            this.validatorAliases = aliases;
            return this;
        }

        /**
         * Sets the root directory for resolving relative file paths (TLS keystores,
         * per-level {@code config-file} entries, {@code templateFile}).
         *
         * @param root the root directory; may be {@code null}
         * @return this builder
         */
        public Builder configFileRoot(Path root) {
            this.configFileRoot = root;
            return this;
        }

        /**
         * Sets the Spring {@link Environment} used by layer 1 of the template model.
         *
         * @param env Spring environment; may be {@code null}
         * @return this builder
         */
        public Builder environment(Environment env) {
            this.env = env;
            return this;
        }

        /**
         * Builds the configured {@link PropertiesFileConfigProvider}.
         *
         * @return new provider instance
         * @throws IllegalArgumentException if required parameters are missing or
         *                                  the configuration is invalid
         */
        public PropertiesFileConfigProvider build() {
            return new PropertiesFileConfigProvider(this);
        }
    }
}