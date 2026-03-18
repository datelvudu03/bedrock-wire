package cz.syntea.bedrock.wire.monitor.config;

import java.util.List;

/**
 * Provides monitor configuration (services, checks, and TLS profiles).
 *
 * <p>The default implementation ({@code PropertiesFileConfigProvider}) reads
 * configuration from a {@code .properties} / {@code .param} file. Custom
 * implementations may load configuration from other sources (database,
 * remote config server, etc.).
 *
 * <h3>Contract</h3>
 * <ul>
 *   <li>Implementations MUST NOT return {@code null} from any method;
 *       empty collections are permitted.</li>
 *   <li>{@code serviceName} values MUST be unique across all returned services.</li>
 *   <li>{@code checkName} values MUST be unique across all returned checks.</li>
 *   <li>Each check's {@code serviceName} MUST reference an existing service.</li>
 *   <li>Each validator alias in {@code CheckConfig.validators} MUST correspond
 *       to a registered {@link cz.syntea.bedrock.wire.monitor.validation.Validator}.</li>
 *   <li>Violations of uniqueness or referential integrity MUST cause
 *       initialization to fail with a descriptive error message.</li>
 * </ul>
 */
public interface MonitorConfigProvider {

    /**
     * Returns all configured checks.
     *
     * @return immutable list of check configurations; never {@code null}
     */
    List<CheckConfig> getChecks();

    /**
     * Returns all configured services.
     *
     * @return immutable list of service configurations; never {@code null}
     */
    List<ServiceConfig> getServices();

    /**
     * Returns all configured TLS profiles.
     *
     * <p>TLS profiles are transport-specific. The monitor layer passes them
     * to the transport implementation during initialization.
     *
     * @return immutable list of TLS profile configurations; never {@code null}
     */
    List<TlsProfileConfig> getTlsProfiles();

    /**
     * Returns the configured shutdown timeout for the monitor executor.
     *
     * @return shutdown timeout; never {@code null}
     */
    java.time.Duration getShutdownTimeout();
}
