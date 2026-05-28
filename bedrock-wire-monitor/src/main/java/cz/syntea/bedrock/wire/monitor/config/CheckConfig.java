package cz.syntea.bedrock.wire.monitor.config;

import cz.syntea.bedrock.wire.monitor.model.HttpMethod;
import lombok.Builder;
import lombok.Value;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Immutable configuration of a single monitor check, fully resolved after
 * applying the three-level lookup (check → service → default).
 *
 * <p>Represents the merged and validated result of the {@code monitor.check.<n>.*}
 * configuration namespace. Header values are already merged from all three levels
 * with the correct precedence (check overrides service overrides default).
 *
 * <h3>Validators</h3>
 * {@link #validators} contains the ordered list of validator aliases as defined
 * in {@code validation.validators}. The corresponding parameters are stored in
 * {@link #validationParams} with their {@code validation.} prefix stripped.
 *
 * <h3>Template parameters</h3>
 * {@link #templateParams} is the fully resolved template model for this check,
 * combining all seven precedence layers of the v3 template-context model
 * (spec §2.9). The layers are merged at parse time so that {@code CheckRunner}
 * needs only this single map at render time.
 */
@Value
@Builder(toBuilder = true)
public class CheckConfig {

    /**
     * Unique name identifying this check. Never {@code null} or blank.
     */
    String checkName;

    /**
     * Name of the target service (references a {@link ServiceConfig}).
     * Never {@code null} or blank.
     */
    String serviceName;

    /**
     * HTTP method for the check request. Default: {@code POST}.
     * Never {@code null}.
     */
    @Builder.Default
    HttpMethod method = HttpMethod.POST;

    /**
     * URL path appended to the service base URL. May be {@code null} if not configured.
     * When present, normalized to ensure a leading {@code /}.
     */
    String path;

    /**
     * Query string appended to the URL (without the leading {@code ?}).
     * May be {@code null} if not configured.
     */
    String query;

    /**
     * Path to the template file for the request body. May be {@code null}
     * if the check does not use a template.
     */
    String templateFile;

    /**
     * Number of retry attempts after the initial attempt. Default: {@code 0} (no retry).
     */
    @Builder.Default
    int retryCount = 0;

    /**
     * Delay between retry attempts. Default: {@code 1s}.
     * Implemented as {@code Thread.sleep()} on the virtual thread.
     */
    @Builder.Default
    Duration retryDelay = Duration.ofSeconds(1);

    /**
     * Whether to retry on {@code IO_ERROR} transport status.
     * Default: {@code false} (spec §2.5.4: IO_ERROR SHOULD NOT retry by default;
     * MAY be enabled via configuration).
     */
    @Builder.Default
    boolean retryOnIoError = false;

    /**
     * Scheduling interval for this check. Resolved via the three-level lookup:
     * check → service → default. Never {@code null} after resolution.
     *
     * @apiNote Implementation extension — not in the v3 spec's {@code CheckConfig}
     *          model (§2.4.6). Added for scheduling convenience; the interval is
     *          resolved from the three-level lookup at parse time rather than at
     *          scheduling time.
     */
    Duration interval;

    /**
     * Merged HTTP headers from all configuration levels (default → service → check).
     * Single-value strings; the monitor engine converts them to singleton lists
     * when building the {@link cz.syntea.bedrock.wire.monitor.spi.MonitorRequest}.
     * Never {@code null}; may be empty.
     */
    @Builder.Default
    Map<String, String> headers = Map.of();

    /**
     * Ordered list of validator aliases to execute against the response.
     * Never {@code null}; may be empty.
     */
    @Builder.Default
    List<String> validators = List.of();

    /**
     * Validator-specific parameters, keyed without the {@code validation.} prefix.
     * For example, if the configuration contains
     * {@code monitor.check.x.validation.httpStatus = 200}, this map contains
     * {@code {"httpStatus": "200"}}. Never {@code null}; may be empty.
     */
    @Builder.Default
    Map<String, String> validationParams = Map.of();

    /**
     * Fully resolved template model for this check, combining all eight precedence
     * layers of the v3 template-context model (spec §2.9):
     *
     * <ol start="0">
     *   <li>Bare-key scan of the {@code .param} graph outside the framework
     *       namespaces (flat) — lowest</li>
     *   <li>Spring {@link org.springframework.core.env.Environment} (under
     *       {@code springEnvPrefix}; nested)</li>
     *   <li>{@code monitor.default.config-file} contents (namespaced under
     *       {@code default.})</li>
     *   <li>{@code monitor.default.param.*} (flat)</li>
     *   <li>{@code monitor.service.<name>.config-file} contents (namespaced under
     *       {@code service.<name>.})</li>
     *   <li>{@code monitor.service.<name>.param.*} (flat)</li>
     *   <li>{@code monitor.check.<name>.config-file} contents (namespaced under
     *       {@code check.<name>.})</li>
     *   <li>{@code monitor.check.<name>.param.*} (flat) — highest</li>
     * </ol>
     *
     * <p>Higher layers override lower layers; nested maps are deep-merged. The
     * {@code config-file} layers contribute namespaced sub-trees, so they do
     * not collide with the flat {@code param.*} layers unless a {@code param.*}
     * key is literally named {@code default}, {@code service}, or {@code check}
     * — those three names are reserved at parse time and cause fail-fast.
     *
     * <p>Values are typed {@link Object} to allow nested {@link Map} structures
     * from the Spring and {@code config-file} layers. {@code String} values are
     * the common case (flat layers, scalar JSON values). Never {@code null};
     * may be empty.
     */
    @Builder.Default
    Map<String, Object> templateParams = Map.of();
}