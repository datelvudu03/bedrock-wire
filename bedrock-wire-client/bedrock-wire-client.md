# bedrock-wire-client — specification

---

## 0. Uvod

`bedrock-wire` je Java knihovna pro spolehlivé HTTP volání a monitorování HTTP endpointů. Skládá se ze dvou samostatných
modulů:

| modul                  | popis                                                                                                                   |
|------------------------|-------------------------------------------------------------------------------------------------------------------------|
| `bedrock-wire-client`  | reaktivní HTTP klient se sdílenými connection pooly a správou TLS; použitelný samostatně mimo monitor modul             |
| `bedrock-wire-monitor` | konfigurovatelný HTTP monitor postavený na `bedrock-wire-client`; spouští checky, validuje odpovědi, reportuje výsledky |

**Předpoklady**

- Java 17+
- Project Reactor (`reactor-core`, `reactor-netty`)
- Spring WebClient (volitelné; `bedrock-wire-client` abstrahuje implementaci přes `HttpClient` interface)

**Vztah modulů**

`bedrock-wire-monitor` závisí na `bedrock-wire-client`. `bedrock-wire-client` nemá závislost na monitor modulu.

---

# 1. bedrock-wire-client

## 1.1 Uvod a architektura

`bedrock-wire-client` je Java knihovna pro reaktivní HTTP komunikaci se sdíleným connection poolingem.

Knihovna poskytuje:

- jednotné rozhraní pro provádění HTTP požadavků
- sdílené connection pooly podle transport target
- správu TLS konfigurace
- nezávislost na vyšší aplikační logice (monitor, scheduler apod.)

**Omezení:** `bedrock-wire-client` pracuje výhradně s **textovými payloady** (request body i response body jsou
`String`). Binární payloady (gzip, protobuf, multipart) nejsou podporovány.

`bedrock-wire-client` je použitelný samostatně jako HTTP klientská komponenta v aplikacích pracujících s textovými
protokoly (REST/JSON, SOAP/XML apod.), kde jsou cílové endpointy předem známy a konfigurovány přes `baseUrl`.

---

## 1.2 Základní pojmy

**Globální pravidlo kódování:** Veškeré textové payloady (request body, response body, template soubory) MUST být
interpretovány jako UTF-8 bez ohledu na `Content-Type` charset deklarovaný serverem.

<!-- TODO: define behavior when server declares a non-UTF-8 charset in Content-Type (e.g. ISO-8859-1); currently charset declared by server is ignored and UTF-8 is always used -->

| pojem                | definice                                                          |
|----------------------|-------------------------------------------------------------------|
| `HttpClient`         | rozhraní pro provedení jednoho HTTP požadavku                     |
| `HttpClientRegistry` | správce instancí `HttpClient` a jejich connection poolů           |
| `TransportTarget`    | identita connection poolu: `scheme + host + port + tlsConfigName` |
| `HttpClientConfig`   | konfigurační objekt pro vytvoření `HttpClient`                    |
| `TlsConfig`          | konfigurační objekt pro TLS parametry                             |
| `HttpRequest`        | popis HTTP požadavku                                              |
| `HttpResponse`       | výsledek HTTP požadavku                                           |

---

## 1.3 Architektura

### 1.3.1 Architektonický diagram

```
klient kodu
    ↓
HttpClientRegistry.get(HttpClientConfig)
    ↓
HttpClient (per config, cacheovany)
    ↓
ConnectionPool (per TransportTarget, sdileny)
    ↓
Spring WebClient / Reactor Netty
```

### 1.3.2 Vrstvení odpovědností

| vrstva               | odpovědnost                                        |
|----------------------|----------------------------------------------------|
| `HttpClientRegistry` | cache HttpClient instancí, správa connection poolů |
| `HttpClient`         | provedení HTTP požadavku, aplikace timeout         |
| `ConnectionPool`     | sdílení TCP spojení per TransportTarget            |
| Spring WebClient     | reaktivní HTTP implementace                        |

### 1.3.3 Oddělení lifecycle

**Architektonické pravidlo:** `HttpClient` instance ≠ connection pool.

`HttpClientRegistry` spravuje connection pooly nezávisle na `HttpClient` instancích. `HttpClient` instance jsou lehké
wrappery nad transport klientem a mohou být vytvářeny nezávisle na connection poolech.

Lifecycle connection poolů je řízen výhradně `HttpClientRegistry`. Vytvoření nového `HttpClient` nevytváří nový pool –
pool je sdílen dle transport target.

Toto oddělení umožňuje:

- sdílení poolů mezi různými volajícími
- nezávislé nastavení timeoutů per klient
- centrální správu TLS konfigurace
- znovupoužitelnost mimo monitor modul

---

## 1.4 Konfiguracni model

### 1.4.1 HttpClientConfig

```java
public class HttpClientConfig {

    String clientId;                       // unique identifier (for caching in registry)

    URI baseUrl;                           // scheme + host + port; MUST NOT contain path or query

    // -- Timeout configuration --

    Duration connectTimeout;               // TCP connect + TLS handshake + DNS resolution;
    // default: 5s

    Duration poolAcquisitionTimeout;       // max wait time for a free slot in a full connection pool;
    // default: 5s
    // if no slot becomes available within this duration,
    // execute() MUST emit Mono.error(PoolAcquisitionTimeoutException)

    Duration responseTimeout;              // time from sending the HTTP request to receiving the
    // **first byte** of the HTTP response;
    // does NOT include DNS lookup, TCP connect, or TLS handshake
    // (covered by connectTimeout);
    // default: 30s

    Duration readTimeout;                  // max time allowed between consecutive bytes during
    // response body transfer;
    // applies after the first byte is received;
    // default: 10s

    Duration keepAliveTimeout;             // max idle time for a pooled connection before it is
    // closed and evicted from the pool;
    // default: 60s

    // -- Pool configuration --

    int maxConnections;                    // max number of concurrent TCP connections per TransportTarget;
    // default: 50

    int maxPendingRequests;                // max number of requests waiting for a pool slot;
    // requests exceeding this limit MUST immediately emit
    // Mono.error(PoolAcquisitionTimeoutException);
    // default: 100

    // -- Headers --

    Map<String, List<String>> defaultHeaders; // headers added to every request

    // -- TLS --

    String tlsConfigName;                  // name of TlsConfig in registry; null = JVM default TLS

}
```

**Rules:**

- `clientId` is the identity of the **transport client** (not the monitored service); the monitor module MAY use
  `serviceName` as `clientId`, but this is not a library requirement — they are independent identities at different
  layers; SHOULD match the service name to eliminate a class of misconfiguration errors where the same `clientId` refers
  to different `baseUrl` values
- `clientId` SHOULD be a stable, human-readable identifier (not a generated UUID); an auto-generated `clientId` MAY
  result in a new client instance on every restart
- `HttpClientConfig` MUST be immutable; changing configuration requires creating a new `clientId`
- `clientId` MUST be unique within a single `HttpClientRegistry`
- `baseUrl` MUST contain only `scheme + host + port` (no path, no trailing slash); the implementation MUST normalize
  `baseUrl` according to the canonical form rules (see section 1.4.3)
- `tlsConfigName` references a `TlsConfig` registered in `HttpClientRegistry`; if not defined, the default JVM TLS
  profile is used
- `connectTimeout` covers DNS resolution, TCP connect, and TLS handshake; DNS resolution is included in `connectTimeout`
- `poolAcquisitionTimeout` and `responseTimeout` are independent; both may fire on the same request

### 1.4.2 TlsConfig

```java
public class TlsConfig {

    // TlsConfig MUST be immutable. Use the builder() factory method.

    String configName;                  // unique identifier for this TLS configuration

    // client certificate (mTLS)
    Path clientCert;                  // path to keystore file; null = no client certificate
    String clientCertPassword;          // keystore password; ignored if clientCert == null
    String clientCertType;              // "PKCS12" or "JKS"; ignored if clientCert == null
    String clientCertAlias;             // alias in keystore; ignored if clientCert == null;
    // if specified and alias does not exist in the keystore,
    // initialization MUST fail with TlsConfigurationException

    // server truststore
    Path trustStore;                  // null = JVM default truststore
    String trustStorePassword;          // ignored if trustStore == null
    String trustStoreType;              // "PKCS12" or "JKS"; ignored if trustStore == null

    boolean hostnameVerification;       // default: true
    boolean allowInsecureInProduction;  // default: false
    // when hostnameVerification == false AND
    // allowInsecureInProduction == false (default),
    // registerTlsConfig() MUST throw InsecureConfigurationException;
    // setting allowInsecureInProduction = true requires explicit
    // opt-in and MUST produce a WARNING log at registration time;
    // MUST NOT be used in production

    // optional TLS parameter constraints
    List<String> enabledProtocols;      // null = library default (see rules below)
    List<String> enabledCipherSuites;   // null = JVM default

}
```

**Rules:**

- `TlsConfig` MUST be constructed via `TlsConfig.builder()` — direct field mutation is not permitted
- `clientCertPassword`, `clientCertType`, `clientCertAlias` are ignored if `clientCert` is not defined
- if `clientCert` is defined and the certificate requires a password but `clientCertPassword` is absent → initialization
  MUST fail (fail-fast)
- if `clientCertAlias` is specified and no entry with that alias exists in the keystore → initialization MUST fail with
  `TlsConfigurationException`
- if `enabledProtocols` is null, the implementation MUST NOT negotiate TLS versions below TLS 1.2; TLS 1.0 and TLS 1.1
  MUST be disabled regardless of JVM defaults
- custom `TrustManager` implementations that accept all certificates (trust-all) MUST NOT be used; implementations
  SHOULD detect and reject trust-all configurations at initialization time
- **Certificate rotation:** `TlsConfig` registration is final; replacing a TLS configuration requires restarting the
  application and re-creating the `HttpClientRegistry`. This is a known limitation. Operators MUST provision
  certificates with sufficient lead time before expiry.

### 1.4.3 Kanonická forma URL

Všechny base URL používané v systému MUST být normalizovány podle jednotného algoritmu kanonické formy.

Kanonická forma:

- `scheme` MUST být normalizován na lowercase
- `host` MUST být normalizován na lowercase
- port MUST být uveden explicitně (`80` pro `http`, `443` pro `https`, pokud není specifikován)
- trailing slash MUST být odstraněn

Normalizace MUST být provedena při vytvoření konfiguračního objektu:

- `HttpClientConfig.baseUrl` – při vytvoření `HttpClientConfig`
- `ServiceConfig.url` – při načtení konfigurace v `MonitorConfigProvider`

Bez konzistentní normalizace mohou vzniknout duplicitní connection pooly pro stejný endpoint.

---

## 1.5 Transport target a connection pooling

### 1.5.1 Definice TransportTarget

**Transport target** je identita connection poolu:

```
scheme + host + port + tlsConfigName
```

Identita je odvozena z `baseUrl` (`HttpClientConfig`) a `tlsConfigName`. Více `HttpClient` instancí se stejným transport
target sdílí jeden connection pool.

**Normativní pravidlo:** `TransportTarget` identifikuje výhradně connection pool. `HttpClientConfig` identifikuje
transportního klienta. Více `HttpClient` instancí (různé `clientId`, různé timeouty) může sdílet jeden pool, pokud mají
stejný `TransportTarget`.

Transport target identita je založena na **názvu** `tlsConfigName`, nikoli na jeho obsahu.

Absence `tlsConfigName` tvoří vlastní transport target identitu (JVM default TLS).

Normalizace `TransportTarget` MUST být provedena v `HttpClientRegistry` při volání `get()`.

- `scheme` MUST být normalizován na lowercase
- `host` MUST být normalizován na lowercase
- pokud port není explicitně uveden, implementace MUST použít default (`80` pro `http`, `443` pro `https`)

Další pravidla canonical form:

- `host` MUST být použit přesně jak je uveden v URL (DNS resolution není součástí identity `TransportTarget`)
- trailing dot v hostname MUST být odstraněn
- IPv6 adresy MUST být uzavřeny v hranatých závorkách, např. `[2001:db8::1]`

### 1.5.2 Pravidla poolingu

- pokud pool pro daný transport target existuje, MUST být znovu použit
- nový pool MUST být vytvořen pouze pokud kompatibilní pool neexistuje
- `HttpClient` instance sdílející stejný transport target MUST sdílet jeden pool
- `HttpClient` s odlišným `tlsConfigName` pool sdílet nesmí

---

## 1.6 HttpClientRegistry

### 1.6.1 Rozhraní

```java
public interface HttpClientRegistry extends AutoCloseable {

    // Registration of TLS configuration; MUST be called before get() references tlsConfigName
    void registerTlsConfig(TlsConfig config);

    // Returns a cached or newly created HttpClient for the given config
    HttpClient get(HttpClientConfig config);

    // Immediately closes all connection pools and releases resources (hard close)
    void close();

}
```

### 1.6.2 Registry configuration

```java
public class HttpClientRegistryConfig {

    int maxClients;   // max number of distinct HttpClient instances (by clientId) in the registry;
                      // if exceeded, get() MUST throw RegistryCapacityException;
                      // default: 100

}
```

### 1.6.3 Lifecycle

`HttpClientRegistry` SHOULD be a singleton within the application. Creating multiple instances may result in duplicate
connection pools for the same `TransportTarget`.

**Spring integration:** Declare `HttpClientRegistry` as a `@Bean` and annotate the `close()` method with `@PreDestroy`.
Failure to do so will result in connection pool resources not being released on application shutdown.

```java

@Bean
public HttpClientRegistry httpClientRegistry() {
    return new HttpClientRegistryImpl();
}

@PreDestroy
public void shutdownRegistry() {
    httpClientRegistry().close();
}
```

### 1.6.4 Chování

- `HttpClientRegistry` MUST be thread-safe
- `get()` MUST be safe for concurrent calls
- `close()` SHOULD be idempotent (repeated calls SHOULD be safe)
- `get(config)` MUST return the same instance for the same `clientId`
- if `get()` is called with the same `clientId` but any different parameter, the implementation MUST throw an exception
- if `tlsConfigName` references an unregistered `TlsConfig` → `get()` MUST throw `IllegalArgumentException`
- `close()` performs a **hard close**: all connection pools are immediately closed and all resources released; any
  `Mono` subscription that is in-flight at the time of `close()` MUST immediately emit
  `Mono.error(RegistryClosedException)`
- after `close()` is called, every subsequent call to `get()` MUST throw `IllegalStateException`
- `close()` SHOULD only be called during application shutdown — it is a globally destructive operation
- `HttpClient` instances obtained from the registry MUST be safe for concurrent use from multiple threads and reactive
  schedulers

### 1.6.5 Caching

`HttpClient` instances are cached by `clientId`. The cache size is bounded by `HttpClientRegistryConfig.maxClients` (
default: 100). When the limit is exceeded, `get()` MUST throw `RegistryCapacityException`.

`HttpClientRegistry` SHOULD be initialized with static configuration at application startup. Dynamic creation of
`HttpClientConfig` at runtime (e.g., with UUID as `clientId`) is not recommended — it leads to pool exhaustion and
potential TCP connection leaks.

Configuration changes do not take effect automatically — `close()` must be called and a new `HttpClientRegistry`
created.

---

## 1.7 Rozhraní HttpClient

```java
public interface HttpClient {

    Mono<HttpResponse> execute(HttpRequest request);

}
```

- `execute()` performs the HTTP request asynchronously (reactive)
- timeouts from `HttpClientConfig` (`responseTimeout`, `readTimeout`) apply to every call
- `execute()` MUST NOT block the calling thread
- `execute()` MUST be safe for concurrent use from multiple threads and reactive schedulers
- if called after the underlying registry has been closed, `execute()` MUST immediately emit
  `Mono.error(RegistryClosedException)`
- `execute()` MUST NOT perform automatic retries; retry logic is the responsibility of the caller to prevent silent
  duplication of non-idempotent requests (POST, PATCH)

#### Příklad použití

```java
// 1. TLS profile
TlsConfig tls = TlsConfig.builder()
                .configName("payments-tls")
                .clientCert(Path.of("/certs/client.p12"))
                .clientCertPassword("secret")
                .clientCertType("PKCS12")
                .trustStore(Path.of("/certs/truststore.p12"))
                .trustStorePassword("trustsecret")
                .trustStoreType("PKCS12")
                .build();

// 2. Client configuration
HttpClientConfig config = HttpClientConfig.builder()
        .clientId("payments")
        .baseUrl(URI.create("https://payments.example.com"))
        .connectTimeout(Duration.ofSeconds(3))
        .poolAcquisitionTimeout(Duration.ofSeconds(3))
        .responseTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(10))
        .tlsConfigName("payments-tls")
        .build();

// 3. Registry and client
HttpClientRegistry registry = new HttpClientRegistryImpl();
registry.

registerTlsConfig(tls);

HttpClient client = registry.get(config);

// 4. Execute request
HttpRequest request = HttpRequest.builder()
        .method(HttpMethod.POST)
        .url(URI.create("/api/health"))
        .header("Content-Type", "application/xml")
        .body("<request><check>health</check></request>")
        .build();

Mono<HttpResponse> response = client.execute(request);

// 5. Shutdown (Spring @PreDestroy handles this; manual call for non-Spring usage)
registry.

close();
```

---

## 1.8 Požadavek a odpověď

### 1.8.1 HttpRequest

```java
public class HttpRequest {

    HttpMethod method;                        // GET, POST, PUT, DELETE, HEAD;
    // case-insensitive; implementation SHOULD normalize
    // to uppercase on construction

    URI url;                                  // MUST be relative (starts with '/');
    // absolute URLs MUST cause execute() to throw
    // IllegalArgumentException immediately (fail-fast);
    // a relative URL that does not start with '/' MUST
    // cause execute() to throw IllegalArgumentException
    // (fail-fast; no silent normalization)

    Map<String, List<String>> headers;        // MUST NOT be null; empty map is allowed;
    // merged with defaultHeaders from config

    String body;                              // null MUST be interpreted as an empty request body

}
```

**URL composition rule:**

The final request URL is formed by appending `HttpRequest.url` to `HttpClientConfig.baseUrl`:

```
final URL = baseUrl + url
```

Example: `baseUrl = https://api.example.com` + `url = /v1/health` → `https://api.example.com/v1/health`

`url` MUST start with `/`. If it does not, `execute()` MUST throw `IllegalArgumentException` (fail-fast). No silent
normalization is performed.

**Rules:**

- `method` value is case-insensitive; implementation SHOULD normalize to uppercase
- headers from the request take higher priority than `defaultHeaders` from `HttpClientConfig`
- if the same header exists in both `defaultHeaders` and `HttpRequest.headers`, the value from `HttpRequest.headers`
  MUST replace the value from `defaultHeaders` (not be appended); this prevents duplicate header values for headers like
  `Content-Type` or `Authorization`
- header names MUST be compared case-insensitively (per RFC 7230)
- if `method` is `HEAD` and `body` is non-null, the body MUST be silently discarded

<!-- TODO: define merge semantics when both defaultHeaders and HttpRequest.headers contain multi-value entries for the same header key -->

### 1.8.2 HttpResponse

```java
public class HttpResponse {

    int statusCode;                           // HTTP status code
    Map<String, List<String>> headers;
    String responseBody;                      // SHOULD be empty string if the HTTP response
    // contains no body; implementation SHOULD NOT use null
    Duration duration;                        // time from sending the request to receiving
    // the last byte of the response

}
```

**Rules:**

- `statusCode` represents the actual HTTP status code returned by the server; if no response was received (transport
  error), `execute()` MUST emit `Mono.error(...)` — a zero `statusCode` is never used
- HTTP 3xx responses (301, 302, 303, 307, 308) are NOT followed; the implementation MUST emit
  `Mono.error(RedirectNotSupportedException)`
- HTTP 304 Not Modified is NOT treated as a redirect; it is returned as a normal `HttpResponse` to the caller

### 1.8.3 Maximum response body size

The implementation SHOULD limit the maximum size of the loaded `responseBody`. The recommended default value is **1 MB
**. Exceeding the limit MUST cause `execute()` to emit `Mono.error(ResponseSizeExceededException)`.

The value may be configurable via `HttpClientConfig` (implementation decision).

---

## 1.9 TLS konfigurace

### 1.9.1 Registrace

`TlsConfig` musí být registrován v `HttpClientRegistry` před prvním voláním `get()` s odkazem na daný `tlsConfigName`.

`TlsConfig` MUST be immutable (use `TlsConfig.builder()`). Registration of the same `configName` twice MUST throw an
exception. Registration after the first use of the configuration (i.e., after the first pool for the given transport
target has been created) MAY be forbidden (fail-fast).

### 1.9.2 Inicializace

TLS konfigurace je inicializována při prvním vytvoření connection poolu pro daný transport target. Chyby v konfiguraci (
neplatný certifikát, špatné heslo) jsou detekovány v tomto okamžiku (fail-fast).

### 1.9.3 mTLS

mTLS (klientský certifikát) je aktivován definováním `clientCert` v `TlsConfig`.

Podporované formáty: `PKCS12`, `JKS`.

### 1.9.4 Known limitations

**Certificate rotation** is not supported at runtime. Replacing a `TlsConfig` requires application restart and full
re-creation of `HttpClientRegistry`. All connection pools are re-established on the next request. Operators MUST
provision certificates with sufficient lead time before expiry to avoid service interruption.

---

## 1.10 Exception hierarchy

All exceptions emitted by `execute()` or thrown by `HttpClientRegistry` MUST be subtypes of `BedrockWireException`. This
isolates callers from underlying JDK / Netty implementation details.

```
BedrockWireException (base, unchecked)
├── TransportException              // connection refused, network unreachable, IO error
├── RequestTimeoutException         // responseTimeout exceeded (no first byte received)
├── ReadTimeoutException            // readTimeout exceeded during body transfer
├── PoolAcquisitionTimeoutException // no pool slot available within poolAcquisitionTimeout,
│                                   // or maxPendingRequests exceeded
├── ResponseSizeExceededException   // response body exceeded maxResponseBodySize
├── RedirectNotSupportedException   // server returned a 3xx response (except 304)
├── RegistryClosedException         // execute() called after registry was closed
├── RegistryCapacityException       // maxClients limit exceeded in HttpClientRegistry
├── InsecureConfigurationException  // hostnameVerification=false without allowInsecureInProduction
└── TlsConfigurationException       // TLS initialization error (bad cert, wrong password,
                                    // missing alias, trust-all detected)
```

All `Mono.error(...)` emissions from `execute()` MUST be one of these types. The implementation wraps underlying
JDK/Netty exceptions internally.

---

## 1.11 Observability

### 1.11.1 Metrics

The library defines a `WireMetricsCollector` interface. The default implementation is a no-op. A Micrometer adapter may
be provided separately.

```java
public interface WireMetricsCollector {

    void recordRequest(
            String clientId,
            HttpMethod method,
            int statusCode,
            Duration duration,
            RequestOutcome outcome
    );

    void recordPoolState(
            TransportTarget target,
            int activeConnections,
            int pendingRequests
    );

}

public enum RequestOutcome {
    SUCCESS,
    TIMEOUT,
    READ_TIMEOUT,
    POOL_EXHAUSTED,
    TRANSPORT_ERROR,
    REDIRECT_REJECTED,
    SIZE_EXCEEDED,
    REGISTRY_CLOSED
}
```

`WireMetricsCollector` is injected into `HttpClientRegistry` at construction time. If not provided, no metrics are
emitted.

Recommended metric names (when using Micrometer):

| metric                                 | type      | tags                                          |
|----------------------------------------|-----------|-----------------------------------------------|
| `bedrock.wire.request.duration`        | histogram | `clientId`, `method`, `statusCode`, `outcome` |
| `bedrock.wire.request.count`           | counter   | `clientId`, `method`, `statusCode`, `outcome` |
| `bedrock.wire.pool.active_connections` | gauge     | `transportTarget`                             |
| `bedrock.wire.pool.pending_requests`   | gauge     | `transportTarget`                             |

### 1.11.2 Distributed tracing

The library defines a `TraceHeaderPropagator` interface for injecting trace context (W3C `traceparent`, B3, etc.) into
outbound requests. The default implementation is a no-op.

```java
public interface TraceHeaderPropagator {
    // Called once per execute(); returned headers are merged with request headers.
    // Request headers take priority over propagated trace headers.
    Map<String, String> headersForRequest(HttpRequest request);
}
```

`TraceHeaderPropagator` is configured per `HttpClientConfig`. An optional OpenTelemetry adapter may be provided
separately.

**Important:** Do NOT inject trace headers via `defaultHeaders` in `HttpClientConfig` — `defaultHeaders` is static and
will attach the same trace context to every request, breaking distributed tracing.

---

## 1.12 Logging

All log output MUST use logger name `bedrock.wire.client` for consistent filtering.

| event                                                                         | level   | required MDC / log fields            |
|-------------------------------------------------------------------------------|---------|--------------------------------------|
| Pool created                                                                  | `INFO`  | `transportTarget`, `clientId`        |
| Pool closed                                                                   | `INFO`  | `transportTarget`                    |
| Request timeout (`responseTimeout`)                                           | `WARN`  | `clientId`, `url`, `duration`        |
| Read timeout (`readTimeout`)                                                  | `WARN`  | `clientId`, `url`, `duration`        |
| Pool exhausted                                                                | `WARN`  | `transportTarget`, `pendingRequests` |
| TLS warning (`hostnameVerification=false` + `allowInsecureInProduction=true`) | `WARN`  | `configName`                         |
| `maxClients` threshold exceeded                                               | `WARN`  | `clientCount`, `maxClients`          |
| `close()` called while requests are in-flight                                 | `ERROR` | `inFlightCount`                      |
| Registry already closed on `get()`                                            | `ERROR` | `clientId`                           |

---

## 1.13 Chybové stavy

| situace                                                           | chování                                                           |
|-------------------------------------------------------------------|-------------------------------------------------------------------|
| `tlsConfigName` není registrován                                  | `get()` vyhodí `IllegalArgumentException`                         |
| chybný certifikát nebo heslo                                      | fail-fast při inicializaci poolu → `TlsConfigurationException`    |
| chybějící alias v keystoru                                        | fail-fast při inicializaci poolu → `TlsConfigurationException`    |
| trust-all TrustManager detekován                                  | fail-fast při registraci → `TlsConfigurationException`            |
| `hostnameVerification=false` bez `allowInsecureInProduction=true` | `registerTlsConfig()` vyhodí `InsecureConfigurationException`     |
| `responseTimeout` překročen                                       | `execute()` emituje `Mono.error(RequestTimeoutException)`         |
| `readTimeout` překročen                                           | `execute()` emituje `Mono.error(ReadTimeoutException)`            |
| pool exhaustion + `poolAcquisitionTimeout` překročen              | `execute()` emituje `Mono.error(PoolAcquisitionTimeoutException)` |
| `maxPendingRequests` překročen                                    | `execute()` emituje `Mono.error(PoolAcquisitionTimeoutException)` |
| connection error                                                  | `execute()` emituje `Mono.error(TransportException)`              |
| IO error                                                          | `execute()` emituje `Mono.error(TransportException)`              |
| HTTP 3xx response (kromě 304)                                     | `execute()` emituje `Mono.error(RedirectNotSupportedException)`   |
| response body překročí limit                                      | `execute()` emituje `Mono.error(ResponseSizeExceededException)`   |
| absolutní URL v `HttpRequest.url`                                 | `execute()` vyhodí `IllegalArgumentException` (fail-fast)         |
| `url` nezačíná `/`                                                | `execute()` vyhodí `IllegalArgumentException` (fail-fast)         |
| `execute()` po `close()`                                          | `execute()` emituje `Mono.error(RegistryClosedException)`         |
| `get()` po `close()`                                              | `get()` vyhodí `IllegalStateException`                            |
| `maxClients` překročen                                            | `get()` vyhodí `RegistryCapacityException`                        |

---

## 1.14 Konfiguracni reference

### 1.14.1 HttpClientConfig parametry

| parametr                 | typ      | povinný | výchozí | popis                                             |
|--------------------------|----------|---------|---------|---------------------------------------------------|
| `clientId`               | String   | ano     | —       | unikátní identifikátor                            |
| `baseUrl`                | URI      | ano     | —       | scheme + host + port bez trailing slash           |
| `connectTimeout`         | Duration | ne      | `5s`    | TCP + TLS + DNS                                   |
| `poolAcquisitionTimeout` | Duration | ne      | `5s`    | čekání na volný slot v poolu                      |
| `responseTimeout`        | Duration | ne      | `30s`   | čas do prvního byte odpovědi                      |
| `readTimeout`            | Duration | ne      | `10s`   | max čas mezi po sobě jdoucími byte při čtení body |
| `keepAliveTimeout`       | Duration | ne      | `60s`   | max idle čas poolovaného spojení                  |
| `maxConnections`         | int      | ne      | `50`    | max souběžných TCP spojení per TransportTarget    |
| `maxPendingRequests`     | int      | ne      | `100`   | max čekajících requestů na slot v poolu           |
| `defaultHeaders`         | Map      | ne      | —       | hlavičky přidávané ke každému požadavku           |
| `tlsConfigName`          | String   | ne      | —       | odkaz na TlsConfig; null = JVM default            |

### 1.14.2 TlsConfig parametry

| parametr                    | typ     | povinný | výchozí     | popis                                                                |
|-----------------------------|---------|---------|-------------|----------------------------------------------------------------------|
| `configName`                | String  | ano     | —           | unikátní identifikátor                                               |
| `clientCert`                | Path    | ne      | —           | cesta k souboru klientského certifikátu                              |
| `clientCertPassword`        | String  | ne      | —           | heslo keystoru                                                       |
| `clientCertType`            | String  | ne      | —           | `PKCS12` nebo `JKS`                                                  |
| `clientCertAlias`           | String  | ne      | —           | alias v keystoru; neexistující alias → fail-fast                     |
| `trustStore`                | Path    | ne      | —           | vlastní truststore; null = JVM default                               |
| `trustStorePassword`        | String  | ne      | —           | heslo truststore                                                     |
| `trustStoreType`            | String  | ne      | —           | `PKCS12` nebo `JKS`                                                  |
| `hostnameVerification`      | boolean | ne      | `true`      | `false` vyžaduje `allowInsecureInProduction=true`                    |
| `allowInsecureInProduction` | boolean | ne      | `false`     | povolí `hostnameVerification=false`; MUST NOT být použito v produkci |
| `enabledProtocols`          | List    | ne      | TLS 1.2+    | seznam povolených TLS protokolů; TLS 1.0 a 1.1 jsou vždy zakázány    |
| `enabledCipherSuites`       | List    | ne      | JVM default | seznam povolených šifer                                              |

### 1.14.3 HttpClientRegistryConfig parametry

| parametr     | typ | povinný | výchozí | popis                                                                              |
|--------------|-----|---------|---------|------------------------------------------------------------------------------------|
| `maxClients` | int | ne      | `100`   | max počet HttpClient instancí v registry; překročení → `RegistryCapacityException` |

---

## 1.15 Příklady použití

### 1.15.1 Základní setup

```java
// 1. TLS profile — mTLS with client certificate and custom trust store
TlsConfig tls = TlsConfig.builder()
                .configName("payments-tls")
                .clientCert(Path.of("/certs/payments-client.p12"))
                .clientCertPassword("secret")
                .clientCertType("PKCS12")
                .trustStore(Path.of("/certs/payments-truststore.p12"))
                .trustStorePassword("trustsecret")
                .trustStoreType("PKCS12")
                .build();

// 2. Client configuration
HttpClientConfig config = HttpClientConfig.builder()
        .clientId("payments")
        .baseUrl(URI.create("https://payments.example.com"))
        .connectTimeout(Duration.ofSeconds(3))
        .poolAcquisitionTimeout(Duration.ofSeconds(3))
        .responseTimeout(Duration.ofSeconds(10))
        .readTimeout(Duration.ofSeconds(10))
        .tlsConfigName("payments-tls")
        .defaultHeader("Content-Type", List.of("application/xml"))
        .build();

// 3. Registry — singleton, initialized at application startup
HttpClientRegistry registry = new HttpClientRegistryImpl();
registry.

registerTlsConfig(tls);          // must be called before get()

// 4. Obtain client (created or returned from cache)
HttpClient client = registry.get(config);

// 5. Execute request
HttpRequest request = HttpRequest.builder()
        .method(HttpMethod.POST)
        .url(URI.create("/api/health"))        // relative to baseUrl; MUST start with '/'
        .header("X-Request-Id", List.of("abc-123"))
        .body("<request><check>health</check></request>")
        .build();

HttpResponse response = client.execute(request).block();
// response.getStatusCode() == 200
// response.getDuration()    == time to last byte

// 6. Shutdown — in Spring, use @PreDestroy instead
registry.

close();
```

### 1.15.2 Sdílení connection poolu

```java
// Client A — service "payments-health"
HttpClientConfig configA = HttpClientConfig.builder()
    .clientId("payments-health")
    .baseUrl(URI.create("https://payments.example.com"))
    .responseTimeout(Duration.ofSeconds(5))
    .tlsConfigName("payments-tls")
    .build();

// Client B — service "payments-process" (different clientId, different timeout)
HttpClientConfig configB = HttpClientConfig.builder()
    .clientId("payments-process")
    .baseUrl(URI.create("https://payments.example.com"))   // same host
    .responseTimeout(Duration.ofSeconds(30))
    .tlsConfigName("payments-tls")                         // same TLS profile
    .build();

HttpClient clientA = registry.get(configA);
HttpClient clientB = registry.get(configB);

// clientA and clientB are distinct HttpClient instances (different clientId, different timeout)
// but share ONE connection pool:
//   TransportTarget("https", "payments.example.com", 443, "payments-tls") → 1 pool

// Client C — same host, different TLS profile → separate connection pool
HttpClientConfig configC = HttpClientConfig.builder()
    .clientId("payments-internal")
    .baseUrl(URI.create("https://payments.example.com"))   // same host
    .tlsConfigName("internal-tls")                         // different TLS profile!
    .build();

HttpClient clientC = registry.get(configC);
// clientC uses a SECOND pool:
//   TransportTarget("https", "payments.example.com", 443, "internal-tls") → 2nd pool
```
