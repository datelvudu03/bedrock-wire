package cz.syntea.bedrock.wire.template.exception;

/**
 * Base exception for all {@code bedrock-wire-template} errors. All exceptions emitted
 * by the template renderer, parameter sources, and engine implementations are subtypes
 * of this class.
 *
 * <p>Concrete subtypes:
 * <ul>
 *   <li>{@link TemplateNotFoundException} — template file missing or unreadable</li>
 *   <li>{@link TemplateParamException}    — parameter source malformed</li>
 *   <li>{@link TemplateRenderException}   — engine render failure</li>
 * </ul>
 *
 * @since 1.0
 */
public abstract class BedrockWireTemplateException extends RuntimeException {

    /**
     * Creates a new exception with a message.
     *
     * @param message never {@code null}
     */
    protected BedrockWireTemplateException(String message) {
        super(message);
    }

    /**
     * Creates a new exception with a message and a cause.
     *
     * @param message never {@code null}
     * @param cause   underlying cause; may be {@code null}
     */
    protected BedrockWireTemplateException(String message, Throwable cause) {
        super(message, cause);
    }
}
