package cz.syntea.bedrock.wire.monitor.spi;

/**
 * Status of a single HTTP transport attempt.
 *
 * <p>Semantics are normative for all {@link MonitorTransport} implementations.
 * Each value maps to a specific category of transport outcome; implementations
 * MUST adhere to the mapping rules defined in the SPI specification.
 *
 * @see MonitorResult
 */
public enum TransportStatus {

    /**
     * HTTP response received from the server (any HTTP status code).
     * The response body and headers are available in {@link MonitorResult}.
     */
    RESPONSE_RECEIVED,

    /**
     * Response timeout expired before the first byte was received.
     */
    TIMEOUT,

    /**
     * Unable to establish a connection: connection refused, DNS failure,
     * or TLS handshake error.
     */
    CONNECT_ERROR,

    /**
     * I/O error during communication: read timeout, premature close,
     * response body too large, or general I/O failure.
     */
    IO_ERROR,

    /**
     * Connection pool exhausted. Indicates an internal resource problem
     * in the transport, not a target service issue.
     *
     * <p>MUST NOT be mapped to {@code TIMEOUT} or {@code CONNECT_ERROR}.
     * Incorrect mapping causes retry amplification.
     */
    POOL_EXHAUSTED
}
