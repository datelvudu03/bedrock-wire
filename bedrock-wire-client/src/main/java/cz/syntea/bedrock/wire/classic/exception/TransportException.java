package cz.syntea.bedrock.wire.classic.exception;

/**
 * Emitted by {@code execute()} when a low-level transport error occurs:
 * connection refused, network unreachable, IO error, or premature connection close.
 */
public class TransportException extends BedrockWireException {

    public TransportException(String message) {
        super(message);
    }

    public TransportException(String message, Throwable cause) {
        super(message, cause);
    }
}