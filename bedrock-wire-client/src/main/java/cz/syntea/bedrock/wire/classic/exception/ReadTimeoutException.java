package cz.syntea.bedrock.wire.classic.exception;

import java.net.URI;
import java.time.Duration;

/**
 * Emitted by {@code execute()} when {@code readTimeout} elapses between
 * consecutive bytes during response body transfer.
 */
public class ReadTimeoutException extends BedrockWireException {

    private final String clientId;
    private final URI url;
    private final Duration timeout;

    public ReadTimeoutException(String clientId, URI url, Duration timeout) {
        super(String.format("Read timeout after %s: clientId=%s url=%s", timeout, clientId, url));
        this.clientId = clientId;
        this.url = url;
        this.timeout = timeout;
    }

    public String getClientId() {
        return clientId;
    }

    public URI getUrl() {
        return url;
    }

    public Duration getTimeout() {
        return timeout;
    }
}