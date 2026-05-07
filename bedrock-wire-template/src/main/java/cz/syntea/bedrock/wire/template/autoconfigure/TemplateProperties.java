package cz.syntea.bedrock.wire.template.autoconfigure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Configuration properties for {@code bedrock-wire-template}. Bound to the
 * {@code bedrock.wire.template} namespace.
 *
 * <table>
 *   <caption>Properties</caption>
 *   <tr><th>Key</th><th>Type</th><th>Default</th></tr>
 *   <tr><td>{@code bedrock.wire.template.expose-static-methods}</td><td>boolean</td><td>{@code true}</td></tr>
 *   <tr><td>{@code bedrock.wire.template.encoding}</td><td>Charset</td><td>{@code UTF-8}</td></tr>
 *   <tr><td>{@code bedrock.wire.template.template-update-delay}</td><td>Duration</td><td>{@code 0s}</td></tr>
 * </table>
 *
 * @since 1.0
 */
@Data
@ConfigurationProperties(prefix = "bedrock.wire.template")
public class TemplateProperties {

    /**
     * Whether to expose Java static methods inside templates as
     * {@code statics["pkg.Class"].method()}. Default: {@code true}.
     */
    private boolean exposeStaticMethods = true;

    /**
     * Charset for reading template files. Default: UTF-8.
     */
    private Charset encoding = StandardCharsets.UTF_8;

    /**
     * Hot-reload polling interval. {@link Duration#ZERO} disables polling
     * (default — templates cached indefinitely).
     */
    private Duration templateUpdateDelay = Duration.ZERO;
}