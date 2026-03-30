package cz.syntea.bedrock.wire.monitor.engine;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads template files and performs variable substitution.
 *
 * <h3>Static parameters</h3>
 * Tokens in the format {@code $(paramName)} are replaced with values from
 * the check's {@code param.*} configuration.
 *
 * <h3>Dynamic variables</h3>
 * <ul>
 *   <li>{@code $(uuid)} — random UUID v4, unique per request</li>
 *   <li>{@code $(timestamp)} — current UTC time in ISO 8601 format</li>
 * </ul>
 *
 * <h3>Error handling</h3>
 * Invalid UTF-8 in the template file causes the check run to fail with
 * {@code MonitorStatus.ERROR}. Missing template files also cause {@code ERROR}.
 */
@Slf4j
public class TemplateProcessor {

    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\(([^)]+)\\)");
    private static final DateTimeFormatter ISO_UTC = DateTimeFormatter.ISO_INSTANT;

    /**
     * Loads a template file and performs variable substitution.
     *
     * @param templateFilePath path to the template file (UTF-8)
     * @param templateParams   static parameters from {@code monitor.check.<n>.param.*}
     * @return the processed template body; never {@code null}
     * @throws TemplateException if the file cannot be read, contains invalid UTF-8,
     *                           or is missing
     */
    public String process(String templateFilePath, Map<String, String> templateParams) {
        if (templateFilePath == null || templateFilePath.isBlank()) {
            throw new TemplateException("Template file path is null or blank");
        }

        Path path = Path.of(templateFilePath);
        if (!Files.exists(path)) {
            throw new TemplateException("Template file does not exist: " + templateFilePath);
        }

        String content;
        try {
            byte[] bytes = Files.readAllBytes(path);
            content = decodeUtf8(bytes, templateFilePath);
        } catch (IOException e) {
            throw new TemplateException("Failed to read template file: " + templateFilePath, e);
        }

        return substitute(content, templateParams);
    }

    /**
     * Performs variable substitution on the given content.
     *
     * @param content        the raw template content
     * @param templateParams static parameters
     * @return the content with all variables substituted
     */
    String substitute(String content, Map<String, String> templateParams) {
        Matcher matcher = VARIABLE_PATTERN.matcher(content);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String varName = matcher.group(1);
            String replacement = resolveVariable(varName, templateParams);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    private String resolveVariable(String varName, Map<String, String> templateParams) {
        // Dynamic variables
        if ("uuid".equals(varName)) {
            return UUID.randomUUID().toString();
        }
        if ("timestamp".equals(varName)) {
            return ISO_UTC.format(Instant.now().atOffset(ZoneOffset.UTC));
        }

        // Static parameters
        String value = templateParams.get(varName);
        if (value != null) {
            return value;
        }

        log.warn("Unresolved template variable: $({})", varName);
        return "$(" + varName + ")";
    }

    /**
     * Decodes a byte array as UTF-8 with strict validation.
     *
     * @param bytes            the raw bytes
     * @param filePathForError file path for error messages
     * @return the decoded string
     * @throws TemplateException if the bytes contain invalid UTF-8 sequences
     */
    private String decodeUtf8(byte[] bytes, String filePathForError) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .decode(java.nio.ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new TemplateException(
                    "Template file contains invalid UTF-8: " + filePathForError, e);
        }
    }

    /**
     * Exception thrown when template processing fails.
     * Causes the check run to result in {@code MonitorStatus.ERROR}.
     */
    public static class TemplateException extends RuntimeException {

        /**
         * Creates a new template exception.
         *
         * @param message descriptive error message
         */
        public TemplateException(String message) {
            super(message);
        }

        /**
         * Creates a new template exception with a cause.
         *
         * @param message descriptive error message
         * @param cause   the underlying cause
         */
        public TemplateException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
