package cz.syntea.bedrock.wire.classic.exception;

/**
 * Thrown by {@code registerTlsConfig()} when {@code hostnameVerification=false}
 * is set without the explicit {@code allowInsecureInProduction=true} opt-in.
 */
public class InsecureConfigurationException extends BedrockWireException {

    public InsecureConfigurationException(String message) {
        super(message);
    }
}