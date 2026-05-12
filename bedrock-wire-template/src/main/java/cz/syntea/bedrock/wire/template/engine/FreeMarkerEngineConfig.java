package cz.syntea.bedrock.wire.template.engine;

import lombok.Builder;
import lombok.Getter;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Immutable configuration for {@link FreeMarkerEngine}.
 *
 * <p>Constructed via {@link #builder()}; all fields have sensible defaults.
 *
 * @since 1.0
 */
@Getter
@Builder
public final class FreeMarkerEngineConfig {

    /**
     * Whether to expose Java static methods inside templates as the
     * {@code statics["pkg.Class"].method()} accessor.
     *
     * <p>Default: {@code true}. Set to {@code false} for untrusted templates.
     */
    @Builder.Default
    private final boolean exposeStaticMethods = true;

    /**
     * Charset used to read template files from disk.
     *
     * <p>Default: {@link StandardCharsets#UTF_8}.
     */
    @Builder.Default
    private final Charset encoding = StandardCharsets.UTF_8;

    /**
     * FreeMarker template-update delay (hot-reload polling interval). A value of
     * {@code Duration.ZERO} disables polling — templates are loaded once and cached
     * indefinitely. Positive values cause FreeMarker to re-check file modification
     * times after the configured delay.
     *
     * <p>Default: {@link Duration#ZERO}.
     */
    @Builder.Default
    private final Duration templateUpdateDelay = Duration.ZERO;
}