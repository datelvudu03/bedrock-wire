package cz.syntea.bedrock.wire.monitor.config;

import java.util.List;

/**
 * Provides monitor configuration (services and checks).
 *
 * <p>The default implementation ({@code PropertiesFileConfigProvider}) reads
 * configuration from a {@code .properties} / {@code .param} file. Custom
 * implementations may load configuration from other sources (database,
 * remote config server, etc.).
 *
 * <p>This interface is transport-agnostic per spec §2.4.7. Transport-specific
 * configuration (TLS profiles, shutdown timeout) is NOT part of this contract
 * and is handled by the concrete implementation or Spring properties.
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
}