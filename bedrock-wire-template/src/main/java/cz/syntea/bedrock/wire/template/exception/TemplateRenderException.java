package cz.syntea.bedrock.wire.template.exception;

/**
 * Thrown when the template engine fails to render — undefined variable, syntax error,
 * type mismatch, or any other engine-reported failure.
 *
 * <p>The message preserves the underlying engine's diagnostic verbatim and additionally
 * includes (when available): the template file path, line/column of the failure, and
 * the names of available parameters at render time.
 *
 * @since 1.0
 */
public class TemplateRenderException extends BedrockWireTemplateException {

    /**
     * Creates a new exception with a message.
     *
     * @param message never {@code null}
     */
    public TemplateRenderException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with a message and a cause.
     *
     * @param message never {@code null}
     * @param cause   underlying cause; may be {@code null}
     */
    public TemplateRenderException(String message, Throwable cause) {
        super(message, cause);
    }
}