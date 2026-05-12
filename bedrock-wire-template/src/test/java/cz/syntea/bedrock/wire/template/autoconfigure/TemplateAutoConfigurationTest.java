package cz.syntea.bedrock.wire.template.autoconfigure;

import cz.syntea.bedrock.wire.template.TemplateRenderer;
import cz.syntea.bedrock.wire.template.exception.TemplateRenderException;
import cz.syntea.bedrock.wire.template.source.Params;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link TemplateAutoConfiguration}. Uses {@link ApplicationContextRunner}
 * for lightweight context testing without starting a full Spring Boot application.
 */
class TemplateAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TemplateAutoConfiguration.class));

    @Test
    void shouldRegisterDefaultRenderer() {
        contextRunner.run(ctx ->
                assertThat(ctx).hasSingleBean(TemplateRenderer.class));
    }

    @Test
    void shouldBackOffWhenUserBeanPresent() {
        contextRunner
                .withUserConfiguration(CustomRendererConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(TemplateRenderer.class);
                    assertThat(ctx.getBean(TemplateRenderer.class))
                            .isSameAs(ctx.getBean("customRenderer"));
                });
    }

    @Test
    void shouldHonorExposeStaticMethodsProperty() {
        contextRunner
                .withPropertyValues("bedrock.wire.template.expose-static-methods=false")
                .run(ctx -> {
                    var renderer = ctx.getBean(TemplateRenderer.class);
                    assertThatThrownBy(() -> renderer.render(
                            "${statics['java.util.UUID'].randomUUID()}",
                            Params.of(Map.of())))
                            .isInstanceOf(TemplateRenderException.class);
                });
    }

    @Test
    void shouldHonorEncodingProperty() {
        contextRunner
                .withPropertyValues("bedrock.wire.template.encoding=UTF-8")
                .run(ctx -> assertThat(ctx).hasSingleBean(TemplateRenderer.class));
    }

    @Test
    void shouldHonorTemplateUpdateDelayProperty() {
        contextRunner
                .withPropertyValues("bedrock.wire.template.template-update-delay=10s")
                .run(ctx -> assertThat(ctx).hasSingleBean(TemplateRenderer.class));
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomRendererConfig {

        @Bean
        TemplateRenderer customRenderer() {
            return TemplateRenderer.builder().build();
        }
    }
}