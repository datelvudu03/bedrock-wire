package cz.syntea.bedrock.wire.template.autoconfigure;

import cz.syntea.bedrock.wire.template.TemplateRenderer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Autoconfiguration for {@code bedrock-wire-template}. Registers a single
 * {@link TemplateRenderer} bean wired from {@link TemplateProperties}.
 *
 * <p>The bean backs off via {@code @ConditionalOnMissingBean(TemplateRenderer.class)} —
 * any user-defined {@link TemplateRenderer} bean takes precedence and the auto-config
 * does nothing.
 *
 * @since 1.0
 */
@Slf4j
@AutoConfiguration
@ConditionalOnClass(TemplateRenderer.class)
@EnableConfigurationProperties(TemplateProperties.class)
public class TemplateAutoConfiguration {

    /**
     * Creates the default {@link TemplateRenderer} bean from properties.
     *
     * @param properties bound configuration properties
     * @return a new renderer; never {@code null}
     */
    @Bean
    @ConditionalOnMissingBean(TemplateRenderer.class)
    public TemplateRenderer templateRenderer(TemplateProperties properties) {
        log.info("Auto-configuring TemplateRenderer (exposeStatics={}, encoding={}, updateDelay={})",
                properties.isExposeStaticMethods(),
                properties.getEncoding(),
                properties.getTemplateUpdateDelay());
        return TemplateRenderer.builder()
                .exposeStaticMethods(properties.isExposeStaticMethods())
                .encoding(properties.getEncoding())
                .templateUpdateDelay(properties.getTemplateUpdateDelay())
                .build();
    }
}