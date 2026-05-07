package cz.syntea.bedrock.wire.template.engine;

import cz.syntea.bedrock.wire.template.exception.TemplateNotFoundException;
import cz.syntea.bedrock.wire.template.exception.TemplateRenderException;
import freemarker.core.ParseException;
import freemarker.ext.beans.BeansWrapper;
import freemarker.ext.beans.BeansWrapperBuilder;
import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.CharacterCodingException;
import java.util.Map;
import java.util.TreeSet;

/**
 * Default {@link TemplateEngine} implementation backed by Apache FreeMarker 2.3.x.
 *
 * <p>A single shared {@link Configuration} instance is used for all renders. The
 * configuration is wired with an {@link AbsolutePathTemplateLoader} so that file-based
 * renders go through FreeMarker's caching pipeline; inline string renders bypass the
 * cache and use a fresh {@link Template} per invocation.
 *
 * <p>Thread-safe: FreeMarker's {@link Configuration} is documented as thread-safe after
 * the setup phase, and this class performs no further mutation after construction.
 *
 * @since 1.0
 */
@Slf4j
public final class FreeMarkerEngine implements TemplateEngine {

    /**
     * Engine alias used in diagnostics.
     */
    public static final String ALIAS = "freemarker";

    private static final freemarker.template.Version VERSION = Configuration.VERSION_2_3_34;

    private final Configuration configuration;

    /**
     * Creates a new engine with the given configuration.
     *
     * @param config engine configuration; never {@code null}
     */
    public FreeMarkerEngine(FreeMarkerEngineConfig config) {
        this.configuration = buildConfiguration(config);
    }

    private static boolean isCharacterCodingError(Throwable t) {
        Throwable cursor = t;
        while (cursor != null) {
            if (cursor instanceof CharacterCodingException) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    private static Configuration buildConfiguration(FreeMarkerEngineConfig config) {
        Configuration cfg = new Configuration(VERSION);
        cfg.setIncompatibleImprovements(VERSION);
        cfg.setDefaultEncoding(config.getEncoding().name());
        cfg.setOutputEncoding(config.getEncoding().name());
        cfg.setLogTemplateExceptions(false);
        cfg.setWrapUncheckedExceptions(true);
        cfg.setFallbackOnNullLoopVariable(false);
        cfg.setTemplateExceptionHandler(TemplateExceptionHandler.RETHROW_HANDLER);
        cfg.setTemplateLoader(new AbsolutePathTemplateLoader());
        cfg.setTemplateUpdateDelayMilliseconds(config.getTemplateUpdateDelay().toMillis());

        if (config.isExposeStaticMethods()) {
            BeansWrapper wrapper = new BeansWrapperBuilder(VERSION).build();
            cfg.setSharedVariable("statics", wrapper.getStaticModels());
        }

        log.debug("FreeMarkerEngine configured: encoding={}, exposeStatics={}, updateDelayMs={}",
                config.getEncoding(), config.isExposeStaticMethods(),
                config.getTemplateUpdateDelay().toMillis());
        return cfg;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@value #ALIAS}
     */
    @Override
    public String alias() {
        return ALIAS;
    }

    /**
     * {@inheritDoc}
     *
     * <p>Inline rendering: the template string is wrapped in a fresh {@link Template}
     * built from a {@link StringReader}, bypassing the file cache.
     *
     * @param template raw template text; never {@code null}
     * @param params   resolved parameter map; never {@code null}
     * @return rendered output
     * @throws TemplateRenderException when FreeMarker reports a parse or render failure
     */
    @Override
    public String render(String template, Map<String, Object> params) {
        try {
            Template tmpl = new Template("inline", new StringReader(template), configuration);
            return executeRender(tmpl, params, "inline");
        } catch (ParseException ex) {
            throw new TemplateRenderException(buildErrorMessage(ex, "inline", params), ex);
        } catch (IOException ex) {
            throw new TemplateRenderException("Failed to read inline template: " + ex.getMessage(), ex);
        }
    }

    /**
     * Renders a template by absolute filesystem path. Uses FreeMarker's cache via
     * {@link AbsolutePathTemplateLoader}, so {@code templateUpdateDelay} controls
     * hot-reload behavior.
     *
     * <p>If the file's bytes cannot be decoded with the configured charset, this method
     * throws {@link TemplateNotFoundException} (the file exists but is not readable as
     * the expected encoding).
     *
     * @param absolutePath absolute path to the template file; never {@code null}
     * @param params       resolved parameter map; never {@code null}
     * @return rendered output
     * @throws TemplateNotFoundException when the file cannot be decoded with the
     *                                   configured charset
     * @throws TemplateRenderException   when FreeMarker reports a parse or render failure,
     *                                   or when the file cannot be loaded for other reasons
     */
    public String renderFromPath(String absolutePath, Map<String, Object> params) {
        try {
            Template tmpl = configuration.getTemplate(absolutePath);
            return executeRender(tmpl, params, absolutePath);
        } catch (ParseException ex) {
            throw new TemplateRenderException(buildErrorMessage(ex, absolutePath, params), ex);
        } catch (IOException ex) {
            if (isCharacterCodingError(ex)) {
                throw new TemplateNotFoundException(
                        "Template file '" + absolutePath
                                + "' cannot be decoded with the configured charset: "
                                + ex.getMessage(), ex);
            }
            throw new TemplateRenderException(
                    "Failed to load template '" + absolutePath + "': " + ex.getMessage(), ex);
        }
    }

    private String executeRender(Template tmpl, Map<String, Object> params, String templateName) {
        StringWriter writer = new StringWriter();
        try {
            tmpl.process(params, writer);
        } catch (TemplateException ex) {
            throw new TemplateRenderException(buildErrorMessage(ex, templateName, params), ex);
        } catch (IOException ex) {
            throw new TemplateRenderException(
                    "IO failure during render of '" + templateName + "': " + ex.getMessage(), ex);
        }
        return writer.toString();
    }

    private String buildErrorMessage(Exception ex, String templateName, Map<String, Object> params) {
        StringBuilder sb = new StringBuilder();
        sb.append(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
        sb.append(" [template=").append(templateName);
        if (ex instanceof ParseException pe) {
            sb.append(", line=").append(pe.getLineNumber())
                    .append(", column=").append(pe.getColumnNumber());
        } else if (ex instanceof TemplateException te) {
            sb.append(", line=").append(te.getLineNumber())
                    .append(", column=").append(te.getColumnNumber());
        }
        sb.append(", availableParams=").append(new TreeSet<>(params.keySet())).append("]");
        return sb.toString();
    }
}