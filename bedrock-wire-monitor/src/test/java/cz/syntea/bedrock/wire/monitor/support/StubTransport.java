package cz.syntea.bedrock.wire.monitor.support;

import cz.syntea.bedrock.wire.monitor.config.ServiceConfig;
import cz.syntea.bedrock.wire.monitor.spi.MonitorRequest;
import cz.syntea.bedrock.wire.monitor.spi.MonitorResult;
import cz.syntea.bedrock.wire.monitor.spi.MonitorTransport;
import cz.syntea.bedrock.wire.monitor.spi.TransportStatus;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test-only {@link MonitorTransport} implementation that returns pre-configured
 * (canned) responses. No network communication occurs.
 *
 * <p>Supports per-service stubbing and tracks invocation counts for assertions.
 */
public class StubTransport implements MonitorTransport {

    private final Map<String, MonitorResult> responses = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> invocationCounts = new ConcurrentHashMap<>();
    private volatile boolean initialized = false;
    private volatile boolean closed = false;

    /**
     * Returns a default OK response (HTTP 200, empty body).
     *
     * @return a passing monitor result
     */
    public static MonitorResult defaultOk() {
        return MonitorResult.builder()
                .transportStatus(TransportStatus.RESPONSE_RECEIVED)
                .httpStatus(200)
                .responseBody("")
                .headers(Map.of())
                .transportDuration(Duration.ofMillis(50))
                .build();
    }

    /**
     * Creates a response with a specific HTTP status and body.
     *
     * @param statusCode   the HTTP status code
     * @param responseBody the response body
     * @return a monitor result
     */
    public static MonitorResult response(int statusCode, String responseBody) {
        return MonitorResult.builder()
                .transportStatus(TransportStatus.RESPONSE_RECEIVED)
                .httpStatus(statusCode)
                .responseBody(responseBody)
                .headers(Map.of())
                .transportDuration(Duration.ofMillis(100))
                .build();
    }

    /**
     * Creates a timeout error result.
     *
     * @return a timeout monitor result
     */
    public static MonitorResult timeout() {
        return MonitorResult.builder()
                .transportStatus(TransportStatus.TIMEOUT)
                .httpStatus(0)
                .errorMessage("ResponseTimeout")
                .transportDuration(Duration.ofSeconds(5))
                .build();
    }

    /**
     * Creates a connect error result.
     *
     * @return a connect error monitor result
     */
    public static MonitorResult connectError() {
        return MonitorResult.builder()
                .transportStatus(TransportStatus.CONNECT_ERROR)
                .httpStatus(0)
                .errorMessage("ConnectionRefused")
                .transportDuration(Duration.ofMillis(10))
                .build();
    }

    /**
     * Registers a canned response for a given service.
     *
     * @param serviceName the service name
     * @param result      the canned result
     */
    public void stub(String serviceName, MonitorResult result) {
        responses.put(serviceName, result);
    }

    @Override
    public void init(List<ServiceConfig> services) {
        initialized = true;
    }

    @Override
    public MonitorResult execute(MonitorRequest request) {
        invocationCounts.computeIfAbsent(request.getServiceName(), k -> new AtomicInteger(0))
                .incrementAndGet();

        return responses.getOrDefault(request.getServiceName(), defaultOk());
    }

    @Override
    public void close(Duration timeout) {
        closed = true;
    }

    /**
     * Returns the number of times {@code execute()} was called for a service.
     *
     * @param serviceName the service name
     * @return invocation count
     */
    public int getInvocationCount(String serviceName) {
        AtomicInteger counter = invocationCounts.get(serviceName);
        return counter != null ? counter.get() : 0;
    }

    /**
     * Returns whether {@link #init(List)} was called.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Returns whether {@link #close(Duration)} was called.
     *
     * @return true if closed
     */
    public boolean isClosed() {
        return closed;
    }

    /**
     * Resets all state (responses, counters, flags).
     */
    public void reset() {
        responses.clear();
        invocationCounts.clear();
        initialized = false;
        closed = false;
    }
}
