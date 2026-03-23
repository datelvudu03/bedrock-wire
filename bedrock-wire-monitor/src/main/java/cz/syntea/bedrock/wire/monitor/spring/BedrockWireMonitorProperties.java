package cz.syntea.bedrock.wire.monitor.spring;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Boot configuration properties for {@code bedrock-wire-monitor}.
 *
 * <p>These properties control the monitor starter's behavior within the
 * Spring context. The actual monitor runtime configuration (services, checks,
 * TLS profiles) is resolved automatically:
 *
 * <ol>
 *   <li>If a {@link java.util.Properties} bean (e.g. {@code PropertiesCfg})
 *       is present in the application context, its {@code monitor.*} namespace
 *       is used directly — no additional configuration is needed.</li>
 *   <li>Otherwise, {@link #configFile} is used as a fallback to locate a
 *       standalone {@code .properties} / {@code .param} file.</li>
 * </ol>
 *
 * <p>Example — minimal setup (auto-detection from {@code PropertiesCfg} bean):
 * <pre>{@code
 * # application.properties — nothing monitor-related needed
 * }</pre>
 *
 * <p>Example — fallback (no {@code Properties} bean in context):
 * <pre>{@code
 * bedrock.wire.monitor.config-file=${app.configFile}
 * }</pre>
 */
@Data
@ConfigurationProperties(prefix = "bedrock.wire.monitor")
public class BedrockWireMonitorProperties {

    /**
     * Path to the {@code .properties} / {@code .param} file containing
     * the {@code monitor.*} configuration namespace.
     *
     * <p><strong>Optional.</strong> Only needed when no {@link java.util.Properties}
     * bean (e.g. {@code PropertiesCfg}) is registered in the application context.
     * When a {@code Properties} bean is auto-detected, this property is ignored.
     */
    private String configFile;

    /**
     * Whether the monitor is enabled. When {@code false}, no beans are
     * registered and the monitor does not start. Default: {@code true}.
     */
    private boolean enabled = true;

    /**
     * Grace period for graceful shutdown. Default: {@code 30s}.
     *
     * <p>This value is used as a fallback. If the {@code monitor.executor.shutdownTimeout}
     * key is present in the {@code .param} file, that value takes precedence.
     */
    private java.time.Duration shutdownTimeout = java.time.Duration.ofSeconds(30);
}