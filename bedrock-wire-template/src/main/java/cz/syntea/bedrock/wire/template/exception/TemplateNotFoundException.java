package cz.syntea.bedrock.wire.template.exception;

/**
 * Thrown when a template file cannot be located or read — missing file, IO error,
 * or malformed bytes for the configured encoding.
 *
 * @since 1.0
 */
public class TemplateNotFoundException extends BedrockWireTemplateException {

    /**
     * Creates a new exception with a message.
     *
     * @param message never {@code null}
     */
    public TemplateNotFoundException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with a message and a cause.
     *
     * @param message never {@code null}
     * @param cause   underlying cause; may be {@code null}
     */
    public TemplateNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
