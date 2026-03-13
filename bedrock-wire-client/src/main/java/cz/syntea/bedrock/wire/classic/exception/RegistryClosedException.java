package cz.syntea.bedrock.wire.classic.exception;

/**
 * Emitted by {@code execute()} when the underlying
 * {@link cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry} has been closed.
 */
public class RegistryClosedException extends BedrockWireException {

    private final String clientId;

    public RegistryClosedException(String clientId) {
        super("Registry is closed; cannot execute request for clientId=" + clientId);
        this.clientId = clientId;
    }

    public String getClientId() {
        return clientId;
    }
}