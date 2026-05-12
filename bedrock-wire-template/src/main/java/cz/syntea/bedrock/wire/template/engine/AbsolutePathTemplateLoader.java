package cz.syntea.bedrock.wire.template.engine;

import freemarker.cache.TemplateLoader;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * FreeMarker {@link TemplateLoader} that resolves template names as absolute filesystem
 * paths. Used by {@link FreeMarkerEngine} so that file-based renders go through
 * FreeMarker's caching pipeline (honoring {@code templateUpdateDelay}) without requiring
 * a fixed base directory.
 *
 * <p>Decoding is strict: malformed or unmappable bytes throw
 * {@link java.nio.charset.MalformedInputException} (an {@link IOException}) rather than
 * being silently replaced with {@code ?} as the default {@link InputStreamReader}
 * behavior would do.
 *
 * <p>OS-portable: uses {@link java.nio.file.Path} for resolution and
 * {@link Files#isRegularFile(Path, java.nio.file.LinkOption...)} for existence checks.
 *
 * <p>Thread-safe: stateless.
 *
 * @since 1.0
 */
final class AbsolutePathTemplateLoader implements TemplateLoader {

    /**
     * {@inheritDoc}
     *
     * @param name absolute path string of the template file
     * @return a {@link File} handle if the path resolves to a regular file; {@code null}
     * otherwise (signals "not found" to FreeMarker)
     */
    @Override
    public Object findTemplateSource(String name) {
        Path path = Paths.get(name);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        return path.toFile();
    }

    /**
     * {@inheritDoc}
     *
     * @param templateSource the {@link File} previously returned by
     *                       {@link #findTemplateSource(String)}
     * @return file last-modified timestamp in milliseconds since epoch
     */
    @Override
    public long getLastModified(Object templateSource) {
        return ((File) templateSource).lastModified();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Configures the decoder with {@link CodingErrorAction#REPORT} for both malformed
     * and unmappable input — invalid bytes for the requested charset trigger an
     * {@link IOException} on read instead of silent replacement.
     *
     * @param templateSource the {@link File} previously returned by
     *                       {@link #findTemplateSource(String)}
     * @param encoding       charset name to decode the file contents
     * @return reader over the file contents
     * @throws IOException if the file cannot be opened
     */
    @Override
    public Reader getReader(Object templateSource, String encoding) throws IOException {
        Charset charset = Charset.forName(encoding);
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return new InputStreamReader(new FileInputStream((File) templateSource), decoder);
    }

    /**
     * {@inheritDoc}
     *
     * <p>No-op: file streams are owned by the {@link Reader} returned from
     * {@link #getReader(Object, String)} and closed by FreeMarker.
     *
     * @param templateSource ignored
     */
    @Override
    public void closeTemplateSource(Object templateSource) {
        // no-op
    }
}