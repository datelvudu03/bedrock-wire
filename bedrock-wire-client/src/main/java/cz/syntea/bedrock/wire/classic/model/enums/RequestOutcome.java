package cz.syntea.bedrock.wire.classic.model.enums;

public enum RequestOutcome {
    SUCCESS, TIMEOUT, READ_TIMEOUT, POOL_EXHAUSTED,
    TRANSPORT_ERROR, REDIRECT_REJECTED, SIZE_EXCEEDED, REGISTRY_CLOSED
}