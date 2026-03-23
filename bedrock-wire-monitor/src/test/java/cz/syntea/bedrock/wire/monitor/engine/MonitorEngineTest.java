package cz.syntea.bedrock.wire.monitor.engine;

import cz.syntea.bedrock.wire.monitor.config.CheckConfig;
import cz.syntea.bedrock.wire.monitor.config.MonitorConfigProvider;
import cz.syntea.bedrock.wire.monitor.config.ServiceConfig;
import cz.syntea.bedrock.wire.monitor.listener.MonitorResultListener;
import cz.syntea.bedrock.wire.monitor.model.HttpMethod;
import cz.syntea.bedrock.wire.monitor.model.MonitorExecutionResult;
import cz.syntea.bedrock.wire.monitor.support.StubTransport;
import cz.syntea.bedrock.wire.monitor.validation.ValidatorRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MonitorEngineTest {

    private StubTransport transport;
    private MonitorEngine engine;
    private List<MonitorExecutionResult> results;

    @BeforeEach
    void setUp() {
        transport = new StubTransport();
        results = Collections.synchronizedList(new ArrayList<>());
    }

    @AfterEach
    void tearDown() {
        if (engine != null && engine.isRunning()) {
            engine.stop();
        }
    }

    @Test
    void shouldStartAndStopLifecycle() {
        engine = createEngine(checkConfig("check1", "svc1", Duration.ofSeconds(60)));

        assertFalse(engine.isRunning());
        engine.start();
        assertTrue(engine.isRunning());
        assertTrue(transport.isInitialized());

        engine.stop();
        assertFalse(engine.isRunning());
        assertTrue(transport.isClosed());
    }

    @Test
    void shouldExecuteCheckOnStart() throws InterruptedException {
        engine = createEngine(checkConfig("check1", "svc1", Duration.ofSeconds(60)));
        transport.stub("svc1", StubTransport.response(200, "OK"));

        engine.start();

        // Wait for virtual thread to execute the first check
        Thread.sleep(500);

        assertTrue(results.size() >= 1, "At least one check run should have completed");
        assertEquals("check1", results.get(0).getCheckName());
    }

    @Test
    void shouldHandleMultipleChecks() throws InterruptedException {
        CheckConfig check1 = checkConfig("check1", "svc1", Duration.ofSeconds(60));
        CheckConfig check2 = checkConfig("check2", "svc1", Duration.ofSeconds(60));

        engine = createEngine(check1, check2);
        transport.stub("svc1", StubTransport.defaultOk());

        engine.start();
        Thread.sleep(500);

        assertTrue(results.size() >= 2, "Both checks should have run at least once");
    }

    @Test
    void shouldNotStartTwice() {
        engine = createEngine(checkConfig("check1", "svc1", Duration.ofSeconds(60)));

        engine.start();
        assertTrue(engine.isRunning());

        // Second start should be a no-op
        engine.start();
        assertTrue(engine.isRunning());
    }

    @Test
    void shouldReportCorrectPhase() {
        engine = createEngine(checkConfig("check1", "svc1", Duration.ofSeconds(60)));
        assertEquals(Integer.MAX_VALUE - 100, engine.getPhase());
    }

    @Test
    void shouldHandleEmptyConfiguration() {
        engine = createEngine(); // no checks
        engine.start();
        assertTrue(engine.isRunning());
        engine.stop();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private MonitorEngine createEngine(CheckConfig... checks) {
        ServiceConfig svc = ServiceConfig.builder()
                .serviceName("svc1")
                .url(URI.create("https://test.example.com"))
                .interval(Duration.ofSeconds(60))
                .build();

        MonitorConfigProvider provider = new MonitorConfigProvider() {
            @Override
            public List<CheckConfig> getChecks() {
                return List.of(checks);
            }

            @Override
            public List<ServiceConfig> getServices() {
                return List.of(svc);
            }

        };

        MonitorResultListener listener = results::add;

        return new MonitorEngine(provider, transport, new ValidatorRegistry(),
                new TemplateProcessor(), List.of(listener), Duration.ofSeconds(5));
    }

    private CheckConfig checkConfig(String checkName, String serviceName, Duration interval) {
        return CheckConfig.builder()
                .checkName(checkName)
                .serviceName(serviceName)
                .method(HttpMethod.GET)
                .path("/ping")
                .interval(interval)
                .build();
    }
}
