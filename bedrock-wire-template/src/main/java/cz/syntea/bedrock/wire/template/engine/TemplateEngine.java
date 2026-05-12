package cz.syntea.bedrock.wire.template.engine;

import cz.syntea.bedrock.wire.template.exception.TemplateRenderException;

import java.util.Map;

/**
 * Pluggable template-engine SPI. The default implementation is {@link FreeMarkerEngine};
 * callers may supply alternative implementations via
 * {@code TemplateRenderer.builder().engine(...)}.
 *
 * <p>Implementations MUST be thread-safe — a single instance is shared across all renders.
 *
 * @since 1.0
 */
public interface TemplateEngine {

    /**
     * Returns a short, stable alias for this engine (e.g. {@code "freemarker"}).
     *
     * @return engine alias; never {@code null} or blank
     */
    String alias();

    /**
     * Renders the given template content with the given parameters.
     *
     * @param template raw template text; never {@code null}
     * @param params   resolved parameter map (top-level keys); never {@code null}
     * @return rendered output; never {@code null}
     * @throws TemplateRenderException when the engine fails to render
     */
    String render(String template, Map<String, Object> params);
}