package cz.syntea.bedrock.wire.classic.exception;

/**
 * Thrown (fail-fast) during connection pool initialization when TLS setup fails:
 * invalid certificate, wrong password, missing keystore alias, or a trust-all
 * {@code TrustManager} detected.
 */
public class TlsConfigurationException extends BedrockWireException {

    public TlsConfigurationException(String message) {
        super(message);
    }

    public TlsConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}