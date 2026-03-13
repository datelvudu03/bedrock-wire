package cz.syntea.bedrock.wire.classic.exception;

/**
 * Thrown by {@code HttpClientRegistry.get()} when {@code maxClients} is exceeded.
 */
public class RegistryCapacityException extends BedrockWireException {

    private final int maxClients;

    public RegistryCapacityException(int maxClients) {
        super("Registry capacity exceeded: maxClients=" + maxClients);
        this.maxClients = maxClients;
    }

    public int getMaxClients() {
        return maxClients;
    }
}