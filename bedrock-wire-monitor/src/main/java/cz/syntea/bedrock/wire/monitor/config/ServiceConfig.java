package cz.syntea.bedrock.wire.monitor.config;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * Immutable configuration for a single monitored service.
 *
 * <p>Represents a target HTTP service with its base URL, polling interval,
 * default headers, and transport-specific properties.
 *
 * <h3>Transport properties</h3>
 * The {@link #getTransportProperties()} map is an opaque bag of key-value pairs
 * passed to the {@link cz.syntea.bedrock.wire.monitor.spi.MonitorTransport}
 * implementation during {@code init()}. The monitor layer does not parse,
 * validate, or interpret these properties — that responsibility belongs
 * entirely to the transport implementation.
 *
 * <h3>URL contract</h3>
 * {@link #getUrl()} contains the base URL of the service. The exact format
 * and validation rules are defined by the transport implementation. The monitor
 * layer only validates basic URI syntax.
 */
@Value
@Builder(toBuilder = true)
public class ServiceConfig {

    /**
     * Unique name identifying this service. Used as a lookup key throughout
     * the monitor layer and in {@link cz.syntea.bedrock.wire.monitor.spi.MonitorRequest}.
     */
    String serviceName;

    /**
     * Base URL of the service. Format depends on the transport implementation.
     * For {@code WireClientTransport}: scheme + host + port only (no path, no query).
     */
    URI url;

    /**
     * Polling interval for checks targeting this service.
     * May be overridden at the check level.
     */
    Duration interval;

    /**
     * Service-level HTTP headers. Merged with default and check-level headers
     * during request construction (default → service → check).
     */
    @Singular("header")
    Map<String, String> headers;

    /**
     * Opaque transport-specific properties. Passed to the transport implementation
     * during {@code init()} without any processing by the monitor layer.
     *
     * <p>Example keys for {@code WireClientTransport}: {@code responseTimeout},
     * {@code connectionTimeout}, {@code readTimeout}, {@code maxConnections},
     * {@code tlsProfile}.
     */
    @Singular("transportProperty")
    Map<String, String> transportProperties;
}
