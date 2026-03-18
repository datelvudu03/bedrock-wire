package cz.syntea.bedrock.wire.monitor.transport;

import cz.syntea.bedrock.wire.classic.config.HttpClientConfig;
import cz.syntea.bedrock.wire.classic.exception.PoolAcquisitionTimeoutException;
import cz.syntea.bedrock.wire.classic.exception.ReadTimeoutException;
import cz.syntea.bedrock.wire.classic.exception.RedirectNotSupportedException;
import cz.syntea.bedrock.wire.classic.exception.RequestTimeoutException;
import cz.syntea.bedrock.wire.classic.exception.ResponseSizeExceededException;
import cz.syntea.bedrock.wire.classic.exception.TransportException;
import cz.syntea.bedrock.wire.classic.model.HttpRequest;
import cz.syntea.bedrock.wire.classic.model.HttpResponse;
import cz.syntea.bedrock.wire.classic.registry.HttpClient;
import cz.syntea.bedrock.wire.classic.registry.HttpClientRegistry;
import cz.syntea.bedrock.wire.monitor.config.ServiceConfig;
import cz.syntea.bedrock.wire.monitor.model.HttpMethod;
import cz.syntea.bedrock.wire.monitor.spi.MonitorRequest;
import cz.syntea.bedrock.wire.monitor.spi.MonitorResult;
import cz.syntea.bedrock.wire.monitor.spi.TransportStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WireClientTransportTest {

    @Mock
    private HttpClientRegistry registry;

    @Mock
    private HttpClient httpClient;

    private WireClientTransport transport;

    @BeforeEach
    void setUp() {
        when(registry.get(any(HttpClientConfig.class))).thenReturn(httpClient);
        transport = new WireClientTransport(registry);
    }

    private void initTransport() {
        ServiceConfig service = ServiceConfig.builder()
                .serviceName("svc")
                .url(URI.create("https://test.example.com"))
                .interval(Duration.ofSeconds(30))
                .transportProperties(Map.of("responseTimeout", "5s", "connectionTimeout", "3s"))
                .build();
        transport.init(List.of(service));
    }

    private MonitorRequest request() {
        return MonitorRequest.builder()
                .serviceName("svc")
                .method(HttpMethod.GET)
                .url(URI.create("/ping"))
                .headers(Map.of())
                .build();
    }

    @Test
    void shouldMapSuccessfulResponse() {
        initTransport();
        when(httpClient.execute(any(HttpRequest.class))).thenReturn(Mono.just(
                HttpResponse.builder()
                        .statusCode(200)
                        .responseBody("OK")
                        .headers(Map.of("Content-Type", List.of("text/plain")))
                        .duration(Duration.ofMillis(50))
                        .build()
        ));

        MonitorResult result = transport.execute(request());

        assertEquals(TransportStatus.RESPONSE_RECEIVED, result.getTransportStatus());
        assertEquals(200, result.getHttpStatus());
        assertEquals("OK", result.getResponseBody());
        assertNotNull(result.getTransportDuration());
    }

    @Test
    void shouldMapRequestTimeoutException() {
        initTransport();
        when(httpClient.execute(any(HttpRequest.class))).thenReturn(
                Mono.error(new RequestTimeoutException("svc", URI.create("/ping"), Duration.ofSeconds(5))));

        MonitorResult result = transport.execute(request());

        assertEquals(TransportStatus.TIMEOUT, result.getTransportStatus());
        assertEquals("ResponseTimeout", result.getErrorMessage());
    }

    @Test
    void shouldMapTransportExceptionWithConnectCause() {
        initTransport();
        when(httpClient.execute(any(HttpRequest.class))).thenReturn(
                Mono.error(new TransportException("Connection refused",
                        new ConnectException("Connection refused"))));

        MonitorResult result = transport.execute(request());

        assertEquals(TransportStatus.CONNECT_ERROR, result.getTransportStatus());
        assertEquals("ConnectionRefused", result.getErrorMessage());
    }

    @Test
    void shouldMapReadTimeoutException() {
        initTransport();
        when(httpClient.execute(any(HttpRequest.class))).thenReturn(
                Mono.error(new ReadTimeoutException("svc", URI.create("/ping"), Duration.ofSeconds(10))));

        MonitorResult result = transport.execute(request());

        assertEquals(TransportStatus.IO_ERROR, result.getTransportStatus());
        assertEquals("ReadTimeout", result.getErrorMessage());
    }

    @Test
    void shouldMapResponseSizeExceededException() {
        initTransport();
        when(httpClient.execute(any(HttpRequest.class))).thenReturn(
                Mono.error(new ResponseSizeExceededException(1048576)));

        MonitorResult result = transport.execute(request());

        assertEquals(TransportStatus.IO_ERROR, result.getTransportStatus());
        assertEquals("ResponseSizeExceeded", result.getErrorMessage());
    }

    @Test
    void shouldMapPoolAcquisitionTimeoutException() {
        initTransport();
        when(httpClient.execute(any(HttpRequest.class))).thenReturn(
                Mono.error(new PoolAcquisitionTimeoutException("Pool exhausted")));

        MonitorResult result = transport.execute(request());

        assertEquals(TransportStatus.POOL_EXHAUSTED, result.getTransportStatus());
        assertEquals("PoolExhausted", result.getErrorMessage());
    }

    @Test
    void shouldMapRedirectNotSupportedException() {
        initTransport();
        when(httpClient.execute(any(HttpRequest.class))).thenReturn(
                Mono.error(new RedirectNotSupportedException("svc", URI.create("/redirect"), 301)));

        MonitorResult result = transport.execute(request());

        assertEquals(TransportStatus.RESPONSE_RECEIVED, result.getTransportStatus());
        assertEquals(301, result.getHttpStatus());
    }

    @Test
    void shouldReturnIoErrorForUnknownService() {
        initTransport();

        MonitorRequest unknownReq = MonitorRequest.builder()
                .serviceName("unknown")
                .method(HttpMethod.GET)
                .url(URI.create("/ping"))
                .headers(Map.of())
                .build();

        MonitorResult result = transport.execute(unknownReq);

        assertEquals(TransportStatus.IO_ERROR, result.getTransportStatus());
    }

    @Test
    void shouldHandleUnexpectedException() {
        initTransport();
        when(httpClient.execute(any(HttpRequest.class))).thenReturn(
                Mono.error(new RuntimeException("Something unexpected")));

        MonitorResult result = transport.execute(request());

        assertEquals(TransportStatus.IO_ERROR, result.getTransportStatus());
    }
}
