package cz.syntea.bedrock.wire.classic.exception;

/**
 * Emitted by {@code execute()} when either:
 * <ul>
 *   <li>no pool slot becomes available within {@code poolAcquisitionTimeout}, or</li>
 *   <li>{@code maxPendingRequests} is exceeded (immediate failure, no waiting).</li>
 * </ul>
 */
public class PoolAcquisitionTimeoutException extends BedrockWireException {

    public PoolAcquisitionTimeoutException(String message) {
        super(message);
    }

    public PoolAcquisitionTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}