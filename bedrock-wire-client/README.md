# bedrock-wire-client

Reactive HTTP client library for Java 17+ with shared connection pools, TLS management, and pluggable observability.

## What it does

`bedrock-wire-client` sits between your application code and the network. You register client configurations once at startup, and the library manages connection pools, TLS handshakes, timeouts, and observability for every HTTP call that passes through it.

The library works exclusively with **text payloads** (JSON, XML, SOAP). Binary payloads (gzip, protobuf, multipart) are not supported.

It is designed to be used standalone or as the transport layer for higher-level modules (e.g. `bedrock-wire-monitor`).

### Core capabilities

- **Shared connection pools** — clients hitting the same host/port/TLS profile share one TCP pool automatically, even if they have different timeouts
- **TLS and mTLS** — custom trust stores, client certificates with alias pinning, hostname verification controls
- **Three-layer timeouts** — connect, response (first byte), and read (inter-byte idle) are independently configurable
- **Structured observability** — centralized logging with MDC fields, pluggable metrics SPI, pluggable distributed tracing SPI
- **Clean exception hierarchy** — every error that leaves the library is a `BedrockWireException` subtype; callers never see raw Netty/JDK exceptions
- **Reactive** — returns `Mono<HttpResponse>`; non-blocking by default, `.block()` for synchronous use

## Architecture

```
Your application code
    │
    ▼
HttpClientRegistry.get(HttpClientConfig)     ← creates or returns cached client
    │
    ▼
HttpClient.execute(HttpRequest)              ← returns Mono<HttpResponse>
    │
    ▼
ConnectionPool (per TransportTarget)         ← shared: scheme + host + port + tlsConfigName
    │
    ▼
Spring WebClient / Reactor Netty             ← actual HTTP transport
```

**Key separation:** `HttpClient` instances are lightweight wrappers with their own timeout settings. Connection pools are heavier resources managed by the registry. Multiple clients can share one pool.

A `TransportTarget` is the pool identity: `scheme + host + port + tlsConfigName`. Two clients with the same transport target share one pool. A `null` TLS config name means "use JVM-default TLS" and is treated as its own distinct identity.

## Quick start

### 1. Add the dependency

```xml
<dependency>
    <groupId>cz.syntea.bedrock</groupId>
    <artifactId>bedrock-wire-client</artifactId>
    <version>${bedrock.wire.version}</version>
</dependency>
```

Spring Boot WebFlux must be on the classpath:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
```

### 2. Auto-configuration

The library ships with a Spring Boot auto-configuration that registers:

| Bean                    | Default                        | Purpose                              |
|-------------------------|--------------------------------|--------------------------------------|
| `HttpClientRegistry`    | `HttpClientRegistryImpl`       | Manages clients and pools            |
| `WireMetricsCollector`  | `NoOpWireMetricsCollector`     | Replace with Micrometer adapter      |
| `TraceHeaderPropagator` | `NoOpTraceHeaderPropagator`    | Replace with OTel adapter            |

The registry is closed automatically on shutdown via `@PreDestroy`.

Spring Boot 3.x discovers the auto-configuration through:

```
src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

### 3. Configure clients

```java
@Configuration
@RequiredArgsConstructor
public class WireClientConfig {

    private final HttpClientRegistry registry;

    @Bean("paymentsClient")
    HttpClient paymentsClient() {
        registry.registerTlsConfig(TlsConfig.builder()
                .configName("payments-tls")
                .clientCert(Path.of("/certs/client.p12"))
                .clientCertPassword("secret")
                .clientCertType("PKCS12")
                .build());

        return registry.get(HttpClientConfig.builder()
                .clientId("payments")
                .baseUrl(URI.create("https://payments.example.com:8443"))
                .connectTimeout(Duration.ofSeconds(3))
                .responseTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(10))
                .tlsConfigName("payments-tls")
                .defaultHeader("Content-Type", List.of("text/xml"))
                .build());
    }
}
```

### 4. Use in services

```java
@Service
@RequiredArgsConstructor
public class PaymentsService {

    private final HttpClient paymentsClient;

    public Mono<HttpResponse> sendPayment(String xmlBody) {
        return paymentsClient.execute(HttpRequest.builder()
                .method(HttpMethod.POST)
                .url(URI.create("/api/v1/payments"))
                .body(xmlBody)
                .build());
    }
}
```

### 5. URL rules

`baseUrl` is scheme + host + port only. No path, no trailing slash. The request URL is always relative and starts with `/`:

```
baseUrl:     https://api.example.com:8443
request url: /v1/health?check=deep
final URL:   https://api.example.com:8443/v1/health?check=deep
```

## Configuration reference

### HttpClientConfig

| Parameter                | Type       | Default | Description                                                |
|--------------------------|------------|---------|------------------------------------------------------------|
| `clientId`               | `String`   | —       | Unique identifier (required). Should be stable, not a UUID |
| `baseUrl`                | `URI`      | —       | Scheme + host + port only (required)                       |
| `connectTimeout`         | `Duration` | 5s      | DNS + TCP connect + TLS handshake                          |
| `poolAcquisitionTimeout` | `Duration` | 5s      | Max wait for a free pool slot                              |
| `responseTimeout`        | `Duration` | 30s     | Time from request sent to first byte received              |
| `readTimeout`            | `Duration` | 10s     | Max idle time between consecutive bytes during body transfer |
| `keepAliveTimeout`       | `Duration` | 60s     | Max idle time before a pooled connection is evicted        |
| `maxConnections`         | `int`      | 50      | Max concurrent TCP connections per transport target        |
| `maxPendingRequests`     | `int`      | 100     | Max requests queued waiting for a pool slot                |
| `maxResponseBodySize`    | `int`      | 1 MB    | Response body size limit in bytes                          |
| `defaultHeaders`         | `Map`      | empty   | Headers added to every request                             |
| `tlsConfigName`          | `String`   | null    | Reference to a registered TLS profile; null = JVM default  |
| `traceHeaderPropagator`  | SPI        | no-op   | Per-client trace header injection                          |

### TlsConfig

| Parameter                    | Type      | Default  | Description                                         |
|------------------------------|-----------|----------|-----------------------------------------------------|
| `configName`                 | `String`  | —        | Unique name (required)                               |
| `clientCert`                 | `Path`    | null     | Path to keystore; null = no mTLS                     |
| `clientCertPassword`         | `String`  | null     | Keystore password                                    |
| `clientCertType`             | `String`  | null     | `PKCS12` or `JKS`                                    |
| `clientCertAlias`            | `String`  | null     | Pin a specific alias in the keystore                 |
| `trustStore`                 | `Path`    | null     | Custom trust store; null = JVM default               |
| `trustStorePassword`         | `String`  | null     | Trust store password                                 |
| `trustStoreType`             | `String`  | null     | `PKCS12` or `JKS`                                    |
| `hostnameVerification`       | `boolean` | true     | Set false to disable; requires `allowInsecureInProduction` |
| `allowInsecureInProduction`  | `boolean` | false    | Explicit opt-in for disabled hostname verification   |
| `enabledProtocols`           | `List`    | TLS 1.2+ | TLS 1.0 and 1.1 are always disabled                 |
| `enabledCipherSuites`        | `List`    | JVM default | Custom cipher suite list                          |

### HttpClientRegistryConfig

| Parameter    | Type  | Default | Description                                                  |
|--------------|-------|---------|--------------------------------------------------------------|
| `maxClients` | `int` | 100     | Max distinct HttpClient instances; exceeding throws `RegistryCapacityException` |

Application property: `bedrock.wire.client.max-clients=100`

### Timeout diagram

```
 ┌─── connectTimeout ────┐┌──── responseTimeout ─────┐┌──── readTimeout ────────┐
 │  DNS + TCP + TLS      ││  request → first byte     ││  between bytes in body  │
 ▼                       ▼▼                           ▼▼                         ▼
[connect]................[send request]...............[first byte]...[body]...[last byte]
                          │                                                      │
                          └─────── HttpResponse.duration ────────────────────────┘
                                   (send → last byte)

 ┌─── poolAcquisitionTimeout ───┐
 │  waiting for a free pool slot │
 ▼                               ▼
[subscribe].....................[pool slot acquired]→[connect]→...
 │                                                               │
 └──────────────── metrics duration ─────────────────────────────┘
                   (subscribe → complete)
```

## Observability

### Logging

All log output uses a single logger name: `io.bedrock.wire.client`. No other logger is used in the library. Every log event populates MDC fields in `try/finally` to prevent leakage across reactive schedulers.

| Event                               | Level | MDC fields                         |
|-------------------------------------|-------|------------------------------------|
| Pool created                        | INFO  | `transportTarget`, `clientId`      |
| Pool closed                         | INFO  | `transportTarget`                  |
| Request timeout (responseTimeout)   | WARN  | `clientId`, `url`, `duration`      |
| Read timeout (readTimeout)          | WARN  | `clientId`, `url`, `duration`      |
| Pool exhausted                      | WARN  | `transportTarget`, `pendingRequests` |
| TLS hostname verification disabled  | WARN  | `configName`                       |
| maxClients threshold exceeded       | WARN  | `clientCount`, `maxClients`        |
| Response body size exceeded         | WARN  | `clientId`, `url`                  |
| close() while in-flight             | ERROR | `inFlightCount`                    |
| Registry closed on get()            | ERROR | `clientId`                         |

Filter in your logging config:

```xml
<logger name="io.bedrock.wire.client" level="INFO"/>
```

### Metrics

The `WireMetricsCollector` SPI has two methods:

- `recordRequest(clientId, method, statusCode, duration, outcome)` — called once per request
- `recordPoolState(target, activeConnections, pendingRequests)` — called every 30s by a background daemon

The `outcome` enum: `SUCCESS`, `TIMEOUT`, `READ_TIMEOUT`, `POOL_EXHAUSTED`, `TRANSPORT_ERROR`, `REDIRECT_REJECTED`, `SIZE_EXCEEDED`, `REGISTRY_CLOSED`.

To plug in Micrometer, provide your own `@Bean`:

```java
@Bean
public WireMetricsCollector wireMetricsCollector(MeterRegistry registry) {
    return new MicrometerWireMetricsCollector(registry);
}
```

### Distributed tracing

The `TraceHeaderPropagator` SPI injects trace headers (W3C `traceparent`, B3, etc.) per request. Configured per `HttpClientConfig`, not globally:

```java
HttpClientConfig.builder()
        .clientId("payments")
        .traceHeaderPropagator(new OtelTraceHeaderPropagator())
        .build();
```

Never use `defaultHeaders` for trace context — those are static and would stamp the same trace ID on every request.

### Header merge order (highest priority wins)

1. `HttpClientConfig.defaultHeaders` — static defaults
2. `TraceHeaderPropagator.headersForRequest()` — per-request trace context
3. `HttpRequest.headers` — caller-supplied; **replaces** (not appends)

## Known limitations

| #  | Description                                                                                     |
|----|-------------------------------------------------------------------------------------------------|
| 1  | Server-declared non-UTF-8 charset in `Content-Type` is silently ignored; UTF-8 is always used   |
| 2  | Certificate rotation requires application restart and full registry re-creation                  |
| 3  | Pool-exhausted log shows the configured `maxPendingRequests` ceiling, not the live queue depth (unavailable from Netty) |
| 4  | Multi-value header merge semantics when the same key appears in both `defaultHeaders` and `HttpRequest.headers` are not fully specified |