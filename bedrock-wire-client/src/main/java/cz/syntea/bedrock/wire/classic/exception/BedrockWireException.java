package cz.syntea.bedrock.wire.classic.exception;

/**
 * Base exception for all bedrock-wire-client errors.
 * All exceptions emitted by HttpClient.execute() or thrown by HttpClientRegistry
 * are subtypes of this class, isolating callers from underlying JDK / Netty details.
 */

public class BedrockWireException extends RuntimeException {
    public BedrockWireException(String message) {
        super(message);
    }

    public BedrockWireException(String message, Throwable cause) {
        super(message, cause);
    }
}
