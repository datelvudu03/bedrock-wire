package cz.syntea.bedrock.wire.template;

import cz.syntea.bedrock.wire.template.engine.FreeMarkerEngine;
import cz.syntea.bedrock.wire.template.engine.FreeMarkerEngineConfig;
import cz.syntea.bedrock.wire.template.engine.TemplateEngine;
import cz.syntea.bedrock.wire.template.exception.TemplateNotFoundException;
import cz.syntea.bedrock.wire.template.exception.TemplateParamException;
import cz.syntea.bedrock.wire.template.exception.TemplateRenderException;
import cz.syntea.bedrock.wire.template.source.Params;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Entry point for template rendering. Immutable and thread-safe — instances are intended
 * to be shared (a single bean in Spring contexts).
 *
 * <p>Two render modes:
 * <ul>
 *   <li>{@link #render(Path, Params)}   — reads a file from the filesystem; the file is
 *       loaded through the engine's caching pipeline (FreeMarker {@code templateUpdateDelay}
 *       applies). Default engine is {@link FreeMarkerEngine}.</li>
 *   <li>{@link #render(String, Params)} — renders an inline template string; bypasses the
 *       file cache.</li>
 * </ul>
 *
 * <p>Construction:
 * <pre>
 *   TemplateRenderer renderer = TemplateRenderer.create();           // defaults
 *   TemplateRenderer renderer = TemplateRenderer.builder()
 *           .exposeStaticMethods(false)
 *           .encoding(StandardCharsets.UTF_8)
 *           .templateUpdateDelay(Duration.ofSeconds(5))
 *           .build();
 * </pre>
 *
 * @since 1.0
 */
@Slf4j
public final class TemplateRenderer {

    private final TemplateEngine engine;
    private final Charset encoding;

    private TemplateRenderer(TemplateEngine engine, Charset encoding) {
        this.engine = engine;
        this.encoding = encoding;
    }

    /**
     * Creates a renderer with default settings (FreeMarker engine, UTF-8, statics enabled,
     * no hot-reload).
     *
     * @return a new renderer
     */
    public static TemplateRenderer create() {
        return builder().build();
    }

    /**
     * Returns a new {@link Builder}.
     *
     * @return a new builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Renders a template loaded from a file.
     *
     * <p>When the engine is the default {@link FreeMarkerEngine}, the file is loaded via
     * FreeMarker's caching pipeline using its absolute path as the cache key. For custom
     * engines, the file is read in full as a String and passed to {@link TemplateEngine#render}.
     *
     * @param templateFile path to the template file; never {@code null}
     * @param params       parameter source; never {@code null}
     * @return rendered output
     * @throws TemplateNotFoundException if the file does not exist, is not a regular file,
     *                                   or cannot be decoded with the configured encoding
     * @throws TemplateParamException    if {@code params} cannot be resolved
     * @throws TemplateRenderException   if the engine fails to render
     */
    public String render(Path templateFile, Params params) {
        if (templateFile == null) {
            throw new TemplateNotFoundException("templateFile MUST NOT be null");
        }
        if (params == null) {
            throw new TemplateParamException("params MUST NOT be null");
        }
        Path absolute = templateFile.toAbsolutePath().normalize();
        if (!Files.isRegularFile(absolute)) {
            throw new TemplateNotFoundException(
                    "Template file not found or not a regular file: " + absolute);
        }

        Map<String, Object> resolved = params.asMap();

        if (engine instanceof FreeMarkerEngine fm) {
            // Path-based rendering goes through FreeMarker's cache (templateUpdateDelay honored)
            return fm.renderFromPath(absolute.toString(), resolved);
        }
        // Custom engines: read full content with strict decoding, then delegate
        String content = readFileStrict(absolute);
        return engine.render(content, resolved);
    }

    /**
     * Renders an inline template string.
     *
     * @param templateContent raw template text; never {@code null}
     * @param params          parameter source; never {@code null}
     * @return rendered output
     * @throws TemplateParamException  if {@code params} cannot be resolved
     * @throws TemplateRenderException if the engine fails to render
     */
    public String render(String templateContent, Params params) {
        if (templateContent == null) {
            throw new TemplateRenderException("templateContent MUST NOT be null");
        }
        if (params == null) {
            throw new TemplateParamException("params MUST NOT be null");
        }
        return engine.render(templateContent, params.asMap());
    }

    private String readFileStrict(Path absolute) {
        try {
            byte[] bytes = Files.readAllBytes(absolute);
            var decoder = encoding.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT);
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException ex) {
            throw new TemplateNotFoundException(
                    "Template file " + absolute + " is not valid " + encoding + ": " + ex.getMessage(), ex);
        } catch (IOException ex) {
            throw new TemplateNotFoundException(
                    "Failed to read template file " + absolute + ": " + ex.getMessage(), ex);
        }
    }

    /**
     * Builder for {@link TemplateRenderer}.
     *
     * @since 1.0
     */
    public static final class Builder {

        private TemplateEngine engine;
        private boolean exposeStaticMethods = true;
        private Charset encoding = StandardCharsets.UTF_8;
        private Duration templateUpdateDelay = Duration.ZERO;

        private Builder() {
        }

        /**
         * Sets a custom template engine, replacing the default {@link FreeMarkerEngine}.
         *
         * <p>When a custom engine is set, the {@code exposeStaticMethods},
         * {@code encoding}, and {@code templateUpdateDelay} settings are NOT applied to it
         * — they pertain to the default FreeMarker configuration only. Custom engines
         * carry their own configuration.
         *
         * @param engine custom engine; never {@code null}
         * @return this builder
         */
        public Builder engine(TemplateEngine engine) {
            this.engine = engine;
            return this;
        }

        /**
         * Enables or disables exposure of {@code statics["pkg.Class"]} inside templates.
         *
         * @param expose {@code true} to enable (default), {@code false} for untrusted templates
         * @return this builder
         */
        public Builder exposeStaticMethods(boolean expose) {
            this.exposeStaticMethods = expose;
            return this;
        }

        /**
         * Sets the charset used to read template files from disk.
         *
         * @param encoding charset; never {@code null}
         * @return this builder
         */
        public Builder encoding(Charset encoding) {
            this.encoding = encoding;
            return this;
        }

        /**
         * Sets the FreeMarker template-update polling interval. {@link Duration#ZERO}
         * (default) disables hot-reload — templates are loaded once and cached indefinitely.
         *
         * @param delay polling interval; never {@code null}
         * @return this builder
         */
        public Builder templateUpdateDelay(Duration delay) {
            this.templateUpdateDelay = delay;
            return this;
        }

        /**
         * Builds the renderer.
         *
         * @return a new {@link TemplateRenderer}
         */
        public TemplateRenderer build() {
            TemplateEngine resolvedEngine = engine != null ? engine : new FreeMarkerEngine(
                    FreeMarkerEngineConfig.builder()
                    .exposeStaticMethods(exposeStaticMethods)
                    .encoding(encoding)
                    .templateUpdateDelay(templateUpdateDelay)
                    .build());
            return new TemplateRenderer(resolvedEngine, encoding);
        }
    }
}