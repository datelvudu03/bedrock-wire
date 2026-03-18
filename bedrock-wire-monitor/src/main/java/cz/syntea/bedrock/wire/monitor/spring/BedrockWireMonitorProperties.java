package cz.syntea.bedrock.wire.monitor.spring;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Spring Boot configuration properties for {@code bedrock-wire-monitor}.
 *
 * <p>These properties control the monitor starter's behavior within the
 * Spring context. The actual monitor runtime configuration (services, checks,
 * TLS profiles) is loaded from the standalone {@code .param} file referenced
 * by {@link #configFile}.
 *
 * <p>Example {@code application.properties}:
 * <pre>{@code
 * bedrock.wire.monitor.config-file=${app.configFile}
 * bedrock.wire.monitor.enabled=true
 * }</pre>
 */
@Data
@ConfigurationProperties(prefix = "bedrock.wire.monitor")
public class BedrockWireMonitorProperties {

    /**
     * Path to the {@code .properties} / {@code .param} file containing
     * the {@code monitor.*} configuration namespace. Resolved from the
     * Spring environment (supports placeholders like {@code ${app.configFile}}).
     * Required when the monitor is enabled.
     */
    private String configFile;

    /**
     * Whether the monitor is enabled. When {@code false}, no beans are
     * registered and the monitor does not start. Default: {@code true}.
     */
    private boolean enabled = true;
}
