package cz.syntea.bedrock.wire.classic.model;

import cz.syntea.bedrock.wire.classic.config.HttpClientConfig;
import cz.syntea.bedrock.wire.classic.registry.HttpClient;

import java.net.URI;

/**
 * Identity of a connection pool: {@code scheme + host + port + tlsConfigName}.
 *
 * <p>Multiple {@link HttpClient } instances with the
 * same {@code TransportTarget} share one underlying TCP connection pool.
 *
 * <p>Two {@code TransportTarget} instances are equal only when all four fields
 * ({@code scheme}, {@code host}, {@code port}, {@code tlsConfigName}) match.
 * A {@code null} {@code tlsConfigName} means "use JVM-default TLS" and is
 * treated as a distinct value — it does not match any named TLS profile,
 * so it always gets its own connection pool.
 *
 * <p>Normalization rules applied by {@link #from(HttpClientConfig)}:
 * <ul>
 *   <li>scheme is lowercased</li>
 *   <li>host is lowercased; trailing dot is removed</li>
 *   <li>port defaults to 443 (https) or 80 (http) when not explicitly set</li>
 *   <li>IPv6 addresses must already be bracketed in the URI</li>
 * </ul>
 *
 * @param tlsConfigName {@code null} means JVM-default TLS (no client-provided {@code TlsConfig}).
 */
public record TransportTarget(String scheme, String host, int port, String tlsConfigName) {

    /**
     * Derives a normalized {@code TransportTarget} from the given client configuration.
     */
    public static TransportTarget from(HttpClientConfig config) {
        URI base = config.getBaseUrl();

        String scheme = base.getScheme().toLowerCase();
        String host = normalizeHost(base.getHost());
        int port = base.getPort();
        if (port == -1) {
            port = "https".equals(scheme) ? 443 : 80;
        }

        return new TransportTarget(scheme, host, port, config.getTlsConfigName());
    }

    private static String normalizeHost(String host) {
        if (host == null) return null;
        host = host.toLowerCase();
        // Remove trailing dot (FQDN notation)
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }

    /**
     * Human-readable pool name used in {@code ConnectionProvider} and log messages.
     */
    public String toPoolName() {
        String base = scheme + "://" + host + ":" + port;
        return tlsConfigName != null ? base + "@" + tlsConfigName : base;
    }

    @Override
    public String toString() {
        return toPoolName();
    }
}