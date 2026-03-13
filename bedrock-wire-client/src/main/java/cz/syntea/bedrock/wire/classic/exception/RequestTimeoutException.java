package cz.syntea.bedrock.wire.classic.exception;

import java.net.URI;
import java.time.Duration;

/**
 * Emitted by {@code execute()} when {@code responseTimeout} elapses before
 * the first byte of the HTTP response is received.
 */
public class RequestTimeoutException extends BedrockWireException {

    private final String clientId;
    private final URI url;
    private final Duration timeout;

    public RequestTimeoutException(String clientId, URI url, Duration timeout) {
        super(String.format("Response timeout after %s: clientId=%s url=%s", timeout, clientId, url));
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