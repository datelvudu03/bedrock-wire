package cz.syntea.bedrock.wire.monitor.engine;

import cz.syntea.bedrock.wire.monitor.config.CheckConfig;
import cz.syntea.bedrock.wire.monitor.config.PropertiesFileConfigProvider;
import cz.syntea.bedrock.wire.monitor.config.ServiceConfig;
import cz.syntea.bedrock.wire.monitor.spi.MonitorRequest;
import cz.syntea.bedrock.wire.monitor.spi.MonitorResult;
import cz.syntea.bedrock.wire.monitor.spi.MonitorTransport;
import cz.syntea.bedrock.wire.monitor.support.StubTransport;
import cz.syntea.bedrock.wire.monitor.validation.ValidatorRegistry;
import cz.syntea.bedrock.wire.template.TemplateRenderer;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end check that a configuration key supplied outside the {@code monitor.*}
 * namespace is auto-scanned and reaches the rendered template body through the full
 * provider &rarr; {@link CheckConfig} &rarr; {@link CheckRunner} &rarr;
 * {@link TemplateRenderer} chain — with no {@code monitor.templateEnv.vars}
 * declaration and no {@code monitor.*.param.*} declaration (spec §2.9.4).
 */
class TemplateEnvPassthroughIntegrationTest {

    @Test
    void scannedEnvVarReachesRenderedBody() {
        // _MODE lives outside monitor.* — the analogue of an ##include'd env.param key.
        // It is auto-exposed by the scanner; nothing declares it.
        Properties props = new Properties();
        props.setProperty("_MODE", "DEV");
        props.setProperty("monitor.service.svc.url", "https://a.example.com");
        props.setProperty("monitor.check.envCheck.service", "svc");
        props.setProperty("monitor.check.envCheck.interval", "5s");
        props.setProperty("monitor.check.envCheck.method", "POST");
        props.setProperty("monitor.check.envCheck.templateFile",
                "src/test/resources/templates/env-passthrough.xml");

        PropertiesFileConfigProvider provider =
                new PropertiesFileConfigProvider(props, new ValidatorRegistry().getAliases());
        CheckConfig check = provider.getChecks().get(0);
        ServiceConfig service = provider.getServices().get(0);

        AtomicReference<MonitorRequest> captured = new AtomicReference<>();
        MonitorTransport transport = new MonitorTransport() {
            @Override
            public void init(List<ServiceConfig> services) {
            }

            @Override
            public MonitorResult execute(MonitorRequest request) {
                captured.set(request);
                return StubTransport.response(200, "");
            }

            @Override
            public void close(Duration timeout) {
            }
        };

        CheckRunner runner = new CheckRunner(
                transport, new ValidatorRegistry(), TemplateRenderer.create(), List.of());
        runner.execute(check, service, "it-request-id");

        assertNotNull(captured.get());
        assertTrue(captured.get().getBody().contains("mode=\"DEV\""),
                "rendered body should contain the scanned env value; was: " + captured.get().getBody());
    }
}