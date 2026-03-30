package cz.syntea.bedrock.wire.monitor.model;

/**
 * Supported HTTP methods for monitor checks.
 *
 * <p>This enum is owned by the monitor module and is decoupled from
 * {@code bedrock-wire-client}'s {@code HttpMethod}. The mapping between
 * the two occurs inside the transport implementation.
 *
 * <p>Values are uppercase per RFC 7230 §3.1.1.
 */
public enum HttpMethod {

    /**
     * HTTP GET.
     */
    GET,

    /**
     * HTTP POST (default for monitor checks).
     */
    POST,

    /**
     * HTTP PUT.
     */
    PUT,

    /**
     * HTTP DELETE.
     */
    DELETE,

    /**
     * HTTP HEAD.
     */
    HEAD,

    /**
     * HTTP PATCH.
     */
    PATCH,

    /**
     * HTTP OPTIONS.
     */
    OPTIONS;

    /**
     * Parses an HTTP method string (case-insensitive).
     *
     * @param value the method string to parse; must not be {@code null}
     * @return the matching {@link HttpMethod}
     * @throws IllegalArgumentException if the value does not match any known method
     */
    public static HttpMethod parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("HTTP method must not be null");
        }
        return valueOf(value.trim().toUpperCase());
    }
}
