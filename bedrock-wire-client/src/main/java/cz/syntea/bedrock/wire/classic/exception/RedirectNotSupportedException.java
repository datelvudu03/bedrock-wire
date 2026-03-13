package cz.syntea.bedrock.wire.classic.exception;

import java.net.URI;

/**
 * Emitted by {@code execute()} when the server returns an HTTP 3xx response
 * (except 304 Not Modified, which is returned as a normal response).
 * The client does not follow redirects.
 */
public class RedirectNotSupportedException extends BedrockWireException {

    private final int statusCode;
    private final URI url;

    public RedirectNotSupportedException(String clientId, URI url, int statusCode) {
        super(String.format("Redirect not supported: clientId=%s url=%s status=%d", clientId, url, statusCode));
        this.statusCode = statusCode;
        this.url = url;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public URI getUrl() {
        return url;
    }
}