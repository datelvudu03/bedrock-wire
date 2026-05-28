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
 * default headers, transport-specific properties, and service-level template
 * parameters.
 *
 * <h3>Transport properties</h3>
 * The {@link #getTransportProperties()} map is an opaque bag of key-value pairs
 * passed to the {@link cz.syntea.bedrock.wire.monitor.spi.MonitorTransport}
 * implementation during {@code init()}. The monitor layer does not parse,
 * validate, or interpret these properties — that responsibility belongs
 * entirely to the transport implementation.
 *
 * <h3>Template parameters</h3>
 * The {@link #getTemplateParams()} map carries the service-level
 * {@code monitor.service.<name>.param.*} layer of the template-context model
 * (spec §2.9, layer 5). Keys are stored flat (without the {@code param.}
 * prefix) and are merged into every check's template model at parse time, sitting
 * between the {@code default.param.*} layer (3) and the {@code check.param.*}
 * layer (7).
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

    /**
     * Service-level template parameters parsed from
     * {@code monitor.service.<name>.param.*}. Keys are stored without the
     * {@code param.} prefix.
     *
     * <p>Per spec §2.9 (template-context model), these are layer 5 of the
     * 7-layer precedence; they are merged into every check's template model
     * between {@code default.param.*} (layer 3) and {@code check.param.*} (layer 7).
     * Flat semantics — {@code monitor.service.payments.param.clientId = x} is
     * read in templates as {@code ${clientId}}, not {@code ${service.payments.clientId}}.
     *
     * <p>Never {@code null}; may be empty.
     */
    @Singular("templateParam")
    Map<String, String> templateParams;
}