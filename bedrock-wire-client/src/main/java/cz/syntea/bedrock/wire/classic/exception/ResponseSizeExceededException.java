package cz.syntea.bedrock.wire.classic.exception;

/**
 * Emitted by {@code execute()} when the response body exceeds
 * {@link cz.syntea.bedrock.wire.classic.config.HttpClientConfig#getMaxResponseBodySize()}.
 */
public class ResponseSizeExceededException extends BedrockWireException {

    private final int limitBytes;

    public ResponseSizeExceededException(int limitBytes) {
        super(String.format("Response body exceeds limit of %d bytes", limitBytes));
        this.limitBytes = limitBytes;
    }

    public int getLimitBytes() {
        return limitBytes;
    }
}