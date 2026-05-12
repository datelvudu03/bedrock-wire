package cz.syntea.bedrock.wire.template.exception;

/**
 * Thrown when a parameter source is malformed — invalid JSON, missing required prefix,
 * unsupported source structure, or any other parameter-construction failure.
 *
 * @since 1.0
 */
public class TemplateParamException extends BedrockWireTemplateException {

    /**
     * Creates a new exception with a message.
     *
     * @param message never {@code null}
     */
    public TemplateParamException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with a message and a cause.
     *
     * @param message never {@code null}
     * @param cause   underlying cause; may be {@code null}
     */
    public TemplateParamException(String message, Throwable cause) {
        super(message, cause);
    }
}