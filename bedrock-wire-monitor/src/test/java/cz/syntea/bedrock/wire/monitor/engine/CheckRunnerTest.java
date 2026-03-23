package cz.syntea.bedrock.wire.monitor.engine;

import cz.syntea.bedrock.wire.monitor.config.CheckConfig;
import cz.syntea.bedrock.wire.monitor.config.ServiceConfig;
import cz.syntea.bedrock.wire.monitor.listener.MonitorResultListener;
import cz.syntea.bedrock.wire.monitor.model.HttpMethod;
import cz.syntea.bedrock.wire.monitor.model.MonitorExecutionResult;
import cz.syntea.bedrock.wire.monitor.model.MonitorStatus;
import cz.syntea.bedrock.wire.monitor.spi.MonitorResult;
import cz.syntea.bedrock.wire.monitor.spi.TransportStatus;
import cz.syntea.bedrock.wire.monitor.support.StubTransport;
import cz.syntea.bedrock.wire.monitor.validation.ValidatorRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckRunnerTest {

    private static final ServiceConfig SERVICE = ServiceConfig.builder()
            .serviceName("testSvc")
            .url(URI.create("https://test.example.com"))
            .interval(Duration.ofSeconds(30))
            .build();
    private StubTransport transport;
    private CheckRunner runner;
    private List<MonitorExecutionResult> listenerResults;

    @BeforeEach
    void setUp() {
        transport = new StubTransport();
        listenerResults = new ArrayList<>();
        MonitorResultListener listener = listenerResults::add;
        runner = new CheckRunner(
                transport,
                new ValidatorRegistry(),
                new TemplateProcessor(),
                List.of(listener)
        );
    }

    @Test
    void shouldReturnUpOnSuccessfulCheck() {
        transport.stub("testSvc", StubTransport.response(200, "<status>OK</status>"));

        CheckConfig check = baseCheck()
                .validators(List.of("httpStatus", "contains"))
                .validationParams(Map.of("httpStatus", "200", "contains", "<status>OK</status>"))
                .build();

        MonitorExecutionResult result = runner.execute(check, SERVICE, "test-request-id");

        assertEquals(MonitorStatus.UP, result.getStatus());
        assertEquals(1, result.getAttempts());
        assertNotNull(result.getRequestId());
        assertEquals(1, listenerResults.size());
    }

    @Test
    void shouldReturnDownOnValidationFailure() {
        transport.stub("testSvc", StubTransport.response(500, "error"));

        CheckConfig check = baseCheck()
                .validators(List.of("httpStatus"))
                .validationParams(Map.of("httpStatus", "200"))
                .build();

        MonitorExecutionResult result = runner.execute(check, SERVICE, "test-request-id");

        assertEquals(MonitorStatus.DOWN, result.getStatus());
        assertTrue(result.getMessage().contains("500"));
    }

    @Test
    void shouldReturnDownOnTimeout() {
        transport.stub("testSvc", StubTransport.timeout());

        CheckConfig check = baseCheck().build();
        MonitorExecutionResult result = runner.execute(check, SERVICE, "test-request-id");
        assertEquals(MonitorStatus.DOWN, result.getStatus());
        assertEquals("ResponseTimeout", result.getMessage());
    }

    @Test
    void shouldReturnErrorOnPoolExhausted() {
        transport.stub("testSvc", MonitorResult.builder()
                .transportStatus(TransportStatus.POOL_EXHAUSTED)
                .httpStatus(0)
                .errorMessage("PoolExhausted")
                .build());

        CheckConfig check = baseCheck().build();
        MonitorExecutionResult result = runner.execute(check, SERVICE, "test-request-id");

        assertEquals(MonitorStatus.ERROR, result.getStatus());
    }

    @Test
    void shouldRetryOnTimeout() {
        transport.stub("testSvc", StubTransport.timeout());

        CheckConfig check = baseCheck()
                .retryCount(2)
                .retryDelay(Duration.ofMillis(10))
                .build();

        MonitorExecutionResult result = runner.execute(check, SERVICE, "test-request-id");

        assertEquals(3, result.getAttempts()); // 1 initial + 2 retries
        assertEquals(MonitorStatus.DOWN, result.getStatus());
        assertEquals(3, transport.getInvocationCount("testSvc"));
    }

    @Test
    void shouldRetryOnConnectError() {
        transport.stub("testSvc", StubTransport.connectError());

        CheckConfig check = baseCheck()
                .retryCount(1)
                .retryDelay(Duration.ofMillis(10))
                .build();

        MonitorExecutionResult result = runner.execute(check, SERVICE, "test-request-id");

        assertEquals(2, result.getAttempts());
        assertEquals(MonitorStatus.DOWN, result.getStatus());
    }

    @Test
    void shouldNotRetryOnPoolExhausted() {
        transport.stub("testSvc", MonitorResult.builder()
                .transportStatus(TransportStatus.POOL_EXHAUSTED)
                .httpStatus(0)
                .errorMessage("PoolExhausted")
                .build());

        CheckConfig check = baseCheck()
                .retryCount(2)
                .retryDelay(Duration.ofMillis(10))
                .build();

        MonitorExecutionResult result = runner.execute(check, SERVICE, "test-request-id");

        assertEquals(1, result.getAttempts()); // no retry
        assertEquals(MonitorStatus.ERROR, result.getStatus());
    }

    @Test
    void shouldNotRetryOnResponseReceived() {
        transport.stub("testSvc", StubTransport.response(500, "error"));

        CheckConfig check = baseCheck()
                .retryCount(2)
                .retryDelay(Duration.ofMillis(10))
                .validators(List.of("httpStatus"))
                .validationParams(Map.of("httpStatus", "200"))
                .build();

        MonitorExecutionResult result = runner.execute(check, SERVICE, "test-request-id");

        assertEquals(1, result.getAttempts()); // no retry for received responses
        assertEquals(MonitorStatus.DOWN, result.getStatus());
    }

    @Test
    void shouldReturnWarnOnMaxDurationExceeded() {
        transport.stub("testSvc", MonitorResult.builder()
                .transportStatus(TransportStatus.RESPONSE_RECEIVED)
                .httpStatus(200)
                .responseBody("")
                .transportDuration(Duration.ofSeconds(10))
                .build());

        CheckConfig check = baseCheck()
                .validators(List.of("httpStatus", "maxDuration"))
                .validationParams(Map.of("httpStatus", "200", "maxDuration", "5s"))
                .build();

        MonitorExecutionResult result = runner.execute(check, SERVICE, "test-request-id");

        assertEquals(MonitorStatus.WARN, result.getStatus());
    }

    @Test
    void shouldBuildCorrectRelativeUrl() {
        assertEquals(URI.create("/api/health"), CheckRunner.buildRelativeUrl("/api/health", null));
        assertEquals(URI.create("/api/health"), CheckRunner.buildRelativeUrl("api/health", null));
        assertEquals(URI.create("/api/health?format=xml"),
                CheckRunner.buildRelativeUrl("/api/health", "format=xml"));
        assertEquals(URI.create("/"), CheckRunner.buildRelativeUrl(null, null));
    }

    @Test
    void shouldCatchListenerException() {
        transport.stub("testSvc", StubTransport.defaultOk());

        MonitorResultListener badListener = r -> {
            throw new RuntimeException("Listener error");
        };
        CheckRunner runnerWithBadListener = new CheckRunner(
                transport, new ValidatorRegistry(), new TemplateProcessor(),
                List.of(badListener, listenerResults::add));

        CheckConfig check = baseCheck().build();
        MonitorExecutionResult result = runner.execute(check, SERVICE, "test-request-id");

        assertEquals(MonitorStatus.UP, result.getStatus());
        assertEquals(1, listenerResults.size()); // second listener still called
    }

    private CheckConfig.CheckConfigBuilder baseCheck() {
        return CheckConfig.builder()
                .checkName("testCheck")
                .serviceName("testSvc")
                .method(HttpMethod.GET)
                .path("/ping")
                .interval(Duration.ofSeconds(30));
    }
}
