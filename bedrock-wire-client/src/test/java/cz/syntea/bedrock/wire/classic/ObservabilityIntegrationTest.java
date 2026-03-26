/*
package cz.syntea.bedrock.wire.classic;


import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import cz.syntea.bedrock.wire.classic.config.HttpClientConfig;
import cz.syntea.bedrock.wire.classic.config.HttpClientRegistryConfig;
import cz.syntea.bedrock.wire.classic.model.HttpMethod;
import cz.syntea.bedrock.wire.classic.model.HttpRequest;
import cz.syntea.bedrock.wire.classic.model.HttpResponse;
import cz.syntea.bedrock.wire.classic.model.TlsConfig;
import cz.syntea.bedrock.wire.classic.model.TransportTarget;
import cz.syntea.bedrock.wire.classic.model.enums.RequestOutcome;
import cz.syntea.bedrock.wire.classic.observability.TraceHeaderPropagator;
import cz.syntea.bedrock.wire.classic.observability.WireMetricsCollector;
import cz.syntea.bedrock.wire.classic.registry.HttpClient;
import cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry;
import cz.syntea.bedrock.wire.classic.registry.HttpClientRegistryImpl;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

*/
/**
 * Integration test for all three observability layers against a real endpoint:
 *
 * <ol>
 *   <li><strong>Logging</strong> — captures log events from {@code bedrock.wire.client},
 *       verifies level, message content, and MDC fields (transportTarget, clientId)</li>
 *   <li><strong>Metrics</strong> — injects a capturing {@link WireMetricsCollector},
 *       verifies {@code recordRequest()} is called with a correct outcome, status, and duration</li>
 *   <li><strong>Tracing</strong> — injects a capturing {@link TraceHeaderPropagator},
 *       verifies it was invoked per request and headers were propagated</li>
 * </ol>
 *
 *//*

@SpringBootTest(classes = ObservabilityIntegrationTest.TestConfig.class)
@ActiveProfiles("integration")
@Slf4j
class ObservabilityIntegrationTest {

    */
/**
     * Captures all WireMetricsCollector.recordRequest() calls.
 *//*

    static final List<RecordedMetric> RECORDED_METRICS = new CopyOnWriteArrayList<>();

    */
/**
     * Captures all WireMetricsCollector.recordPoolState() calls.
 *//*

    static final List<RecordedPoolState> RECORDED_POOL_STATES = new CopyOnWriteArrayList<>();

    */
/**
     * Captures all TraceHeaderPropagator.headersForRequest() invocations.
 *//*

    static final List<HttpRequest> TRACED_REQUESTS = new CopyOnWriteArrayList<>();

    */
/**
     * Captures log events from bedrock.wire.client.
 *//*

    static final List<CapturedLog> CAPTURED_LOGS = new CopyOnWriteArrayList<>();
    private static final String PING_BODY =
            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n" +
                    "<ns2:Envelope xmlns:ns2=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                    "xmlns:ws=\"http://www.supin.cz/soap/2023/04\">\n" +
                    "    <ns2:Header>\n" +
                    "        <ws:Request IdentZpravy=\"PX1PS_oKgNHDOC\" Mode=\"TST\"/>\n" +
                    "    </ns2:Header>\n" +
                    "    <ns2:Body>\n" +
                    "        <ws:Ping/>\n" +
                    "    </ns2:Body>\n" +
                    "</ns2:Envelope>\n";
    private CapturingAppender appender;
    @Value("${integration.certifications.path}")
    private String certPath;
    @Value("${integration.aaa.url}")
    private String aaaUrl;
    @Value("${integration.aaa.certName}")
    private String certName;
    @Value("${integration.aaa.certPassword}")
    private String certPassword;
    @Value("${integration.aaa.connectTimeout}")
    private long connectTimeout;
    @Value("${integration.aaa.readTimeout}")
    private long readTimeout;
    @Value("${integration.aaa.responseTimeout}")
    private long responseTimeout;

    // ── Setup / teardown ─────────────────────────────────────────────────────

    private static URI toBaseUrl(URI uri) {
        int port = uri.getPort();
        String portPart = port == -1 ? "" : ":" + port;
        return URI.create(uri.getScheme() + "://" + uri.getHost() + portPart);
    }

    private static URI toPath(URI uri) {
        String path = uri.getRawPath();
        String query = uri.getRawQuery();
        return URI.create(query != null ? path + "?" + query : path);
    }

    // ── Test ─────────────────────────────────────────────────────────────────

    @BeforeEach
    void setUp() {
        RECORDED_METRICS.clear();
        RECORDED_POOL_STATES.clear();
        TRACED_REQUESTS.clear();
        CAPTURED_LOGS.clear();

        // Attach a capturing appender to the library's logger
        Logger wireLogger = (Logger) LoggerFactory.getLogger("bedrock.wire.client");
        appender = new CapturingAppender();
        appender.start();
        wireLogger.addAppender(appender);
    }

    // ── Spring config ────────────────────────────────────────────────────────

    @AfterEach
    void tearDown() {
        Logger wireLogger = (Logger) LoggerFactory.getLogger("bedrock.wire.client");
        wireLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    void allObservabilityLayersFire() {
        URI fullUrl = URI.create(aaaUrl);
        URI baseUrl = toBaseUrl(fullUrl);
        URI path = toPath(fullUrl);

        HttpClientRegistry registry = TestConfig.REGISTRY;

        registry.registerTlsConfig(TlsConfig.builder()
                .configName("obs-tls")
                .clientCert(Path.of(certPath, certName))
                .clientCertPassword(certPassword)
                .clientCertType("PKCS12")
                .build());

        HttpClient client = registry.get(HttpClientConfig.builder()
                .clientId("obs-test")
                .baseUrl(baseUrl)
                .connectTimeout(Duration.ofMillis(connectTimeout))
                .responseTimeout(Duration.ofMillis(responseTimeout))
                .readTimeout(Duration.ofMillis(readTimeout))
                .defaultHeader("Content-Type", List.of("text/xml"))
                .defaultHeader("Accept", List.of("text/xml"))
                .tlsConfigName("obs-tls")
                .traceHeaderPropagator(new CapturingTraceHeaderPropagator())
                .build());

        HttpResponse response = client.execute(HttpRequest.builder()
                .method(HttpMethod.POST)
                .url(path)
                .body(PING_BODY)
                .build()
        ).block();

        log.info("[ObservabilityTest] result{}", response);


        // Pool created — INFO with MDC fields transportTarget + clientId
        CapturedLog poolCreated = CAPTURED_LOGS.stream()
                .filter(l -> l.message.contains("Pool created"))
                .findFirst()
                .orElse(null);

        assertThat(poolCreated)
                .as("Expected 'Pool created' log event")
                .isNotNull();
        assertThat(poolCreated.level).isEqualTo("INFO");
        assertThat(poolCreated.mdc).containsKey("transportTarget");
        assertThat(poolCreated.mdc).containsEntry("clientId", "obs-test");

        log.info("[ObservabilityTest] LOGGING OK — Pool created: level={} mdc={}",
                poolCreated.level, poolCreated.mdc);

        // recordRequest should have been called exactly once with SUCCESS
        assertThat(RECORDED_METRICS)
                .as("Expected exactly one recordRequest() call")
                .hasSize(1);

        RecordedMetric metric = RECORDED_METRICS.get(0);
        assertThat(metric.clientId).isEqualTo("obs-test");
        assertThat(metric.method).isEqualTo(HttpMethod.POST);
        assertThat(metric.statusCode).isBetween(200, 299);
        assertThat(metric.outcome).isEqualTo(RequestOutcome.SUCCESS);
        assertThat(metric.duration).isPositive();

        log.info("[ObservabilityTest] METRICS OK — clientId={} method={} status={} outcome={} duration={}",
                metric.clientId, metric.method, metric.statusCode,
                metric.outcome, metric.duration);


        // headersForRequest should have been called exactly once
        assertThat(TRACED_REQUESTS)
                .as("Expected exactly one headersForRequest() call")
                .hasSize(1);

        HttpRequest tracedRequest = TRACED_REQUESTS.getFirst();
        assertThat(tracedRequest.getMethod()).isEqualTo(HttpMethod.POST);
        assertThat(tracedRequest.getUrl().toString()).hasToString(path.toString());

        log.info("[ObservabilityTest] TRACING OK — propagator called for {} {}",
                tracedRequest.getMethod(), tracedRequest.getUrl());
    }

    @Configuration
    @EnableAutoConfiguration
    static class TestConfig {

        static HttpClientRegistryImpl REGISTRY;

        @Bean
        public WireMetricsCollector capturingMetricsCollector() {
            return new CapturingMetricsCollector();
        }

        @Bean
        public HttpClientRegistry httpClientRegistry(WireMetricsCollector metricsCollector) {
            REGISTRY = new HttpClientRegistryImpl(
                    HttpClientRegistryConfig.defaults(), metricsCollector);
            return REGISTRY;
        }
    }

    */
/**
     * Records every recordRequest() call for later assertion.
 *//*

    static class CapturingMetricsCollector implements WireMetricsCollector {

        @Override
        public void recordRequest(String clientId, HttpMethod method, int statusCode,
                                  Duration duration, RequestOutcome outcome) {
            RECORDED_METRICS.add(new RecordedMetric(clientId, method, statusCode, duration, outcome));
        }

        @Override
        public void recordPoolState(TransportTarget target, int activeConnections, int pendingRequests) {
            RECORDED_POOL_STATES.add(new RecordedPoolState(target, activeConnections, pendingRequests));
        }
    }


    */
/**
     * Injects a traceparent header and records that it was called.
 *//*

    static class CapturingTraceHeaderPropagator implements TraceHeaderPropagator {

        @Override
        public Map<String, String> headersForRequest(HttpRequest request) {
            TRACED_REQUESTS.add(request);
            // Inject a fake W3C traceparent to prove it reaches the outbound request
            return Map.of("traceparent", "00-" + UUID.randomUUID().toString().replace("-", "")
                    + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16) + "-01");
        }
    }

    */
/**
     * Logback appender that captures events from bedrock.wire.client.
 *//*

    static class CapturingAppender extends AppenderBase<ILoggingEvent> {

        @Override
        protected void append(ILoggingEvent event) {
            CAPTURED_LOGS.add(new CapturedLog(
                    event.getLevel().toString(),
                    event.getFormattedMessage(),
                    new HashMap<>(event.getMDCPropertyMap())
            ));
        }
    }

    record RecordedMetric(String clientId, HttpMethod method, int statusCode,
                          Duration duration, RequestOutcome outcome) {
    }


    record RecordedPoolState(TransportTarget target, int activeConnections,
                             int pendingRequests) {
    }

    record CapturedLog(String level, String message, Map<String, String> mdc) {
    }
}*/
