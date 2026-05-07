# 2. bedrock-wire-monitor

## 2.1 Introduction and architecture

`bedrock-wire-monitor` is a Java library for periodic HTTP service monitoring. The library is **transport-agnostic** —
it is not tied to any specific HTTP client implementation. HTTP communication is delegated to a pluggable
`MonitorTransport` SPI.

The default transport implementation (`WireClientTransport`) uses `bedrock-wire-client` and is described in **Appendix B
**. The user may replace the transport with a custom implementation provided the SPI contract is honored (see §2.6 and
Appendix A).

The library provides:

- periodic execution of HTTP checks per configuration
- HTTP response validation
- result mapping to `MonitorStatus`
- result notification via `MonitorResultListener`

### 2.1.1 Requirements

- Java 21+ (the library uses virtual threads as its execution model)
- A `MonitorTransport` SPI implementation (default: `WireClientTransport`, which requires `bedrock-wire-client` 1.0+)

---

## 2.2 Monitor architecture

### 2.2.1 Architecture diagram

```
┌──────────────────────────────────────────────────────┐
│                    Monitor layer                      │
│                                                       │
│  ┌───────────┐  ┌──────────────┐  ┌──────────────┐  │
│  │ Scheduler  │→│  Check       │→│  Validators   │  │
│  │ (virtual   │  │  orchestrator│  │  httpStatus,  │  │
│  │  threads)  │  │  retry,      │  │  regex, ...   │  │
│  │            │  │  status map  │  │               │  │
│  └───────────┘  └──────┬───────┘  └──────────────┘  │
│                         │                             │
│  ┌──────────────┐       │         ┌──────────────┐   │
│  │ Config       │       │         │ Listeners    │   │
│  │ provider     │       │         │              │   │
│  └──────────────┘       │         └──────────────┘   │
└─────────────────────────┼────────────────────────────┘
                          │
            ══════════════╪══════════════
               MonitorTransport SPI
            ══════════════╪══════════════
                          │
           ┌──────────────┴──────────────┐
           │                              │
    ┌──────┴───────┐            ┌────────┴────────┐
    │ WireClient   │            │ Custom          │
    │ Transport    │            │ Transport       │
    │ (default)    │            │ (user-provided) │
    │              │            │                 │
    │ bedrock-     │            │ WebClient /     │
    │ wire-client  │            │ OkHttp /        │
    │              │            │ java.net.http / │
    │ HttpClient   │            │ Apache HC / ... │
    │ Registry,    │            │                 │
    │ TLS, pools   │            │ Own TLS, pools, │
    │              │            │ timeouts        │
    └──────────────┘            └─────────────────┘
```

### 2.2.2 Separation of responsibilities

| layer                    | responsibility                                                                                           |
|--------------------------|----------------------------------------------------------------------------------------------------------|
| scheduler                | periodic dispatch of check runs; each check run executes on its own virtual thread                       |
| monitor layer            | orchestration: configuration, retry, status mapping, validation; **transport-agnostic**                  |
| MonitorTransport SPI     | contract for HTTP communication; the boundary between the monitor layer and the transport implementation |
| transport implementation | HTTP communication, connection pooling, TLS, timeouts; **monitor-agnostic**                              |
| validators               | HTTP response evaluation                                                                                 |

### 2.2.3 SPI boundary

The monitor layer MUST NOT contain any logic specific to a concrete HTTP client library. All transport-specific logic (
connection pooling, TLS configuration, exception handling, timeout management) is the sole responsibility of the
`MonitorTransport` implementation.

The monitor layer communicates with the transport exclusively through:

- `MonitorTransport.init()` — transport initialization
- `MonitorTransport.execute()` — HTTP request execution
- `MonitorTransport.close()` — transport teardown

---

## 2.3 Core concepts and normative language

### 2.3.1 Terminology

| term          | definition                                                                        |
|---------------|-----------------------------------------------------------------------------------|
| **check**     | the definition of a single periodic HTTP request and its validation               |
| **service**   | the target HTTP service (baseUrl + shared parameters)                             |
| **check run** | one execution of a check (from start to result)                                   |
| **transport** | the pluggable HTTP communication layer that implements the `MonitorTransport` SPI |
| **validator** | a component that evaluates an HTTP response                                       |
| **attempt**   | one HTTP request within a check run (including retries)                           |
| **SPI**       | Service Provider Interface — the interface implemented by a transport provider    |

### 2.3.2 Normative language

The keywords `MUST`, `MUST NOT`, `SHOULD`, `SHOULD NOT`, and `MAY` are used as defined in RFC 2119.

---

## 2.4 Configuration model

### 2.4.1 Configuration namespace

All monitor configuration lives in the namespace:

```
monitor.*
```

### 2.4.2 Configuration layers

Configuration is split into two layers:

```
┌─────────────────────────────────────────────────┐
│           Monitor-owned configuration            │
│                                                   │
│  interval, retry.*, validation.*, method,         │
│  path, query, templateFile, header.*,             │
│  shutdownTimeout                                  │
│                                                   │
│  → Processed directly by the monitor layer        │
│  → Applies regardless of the chosen transport     │
└─────────────────────────────────────────────────┘
                      │
┌─────────────────────┴───────────────────────────┐
│       Transport-specific configuration           │
│                                                   │
│  Passed to the transport as Map<String, String>   │
│  via ServiceConfig.transportProperties            │
│                                                   │
│  WireClientTransport:                             │
│    responseTimeout, connectionTimeout,            │
│    readTimeout, maxResponseBodySize,              │
│    maxConnections, tlsProfile                     │
│                                                   │
│  Custom transport:                                │
│    own keys per implementation                    │
│                                                   │
│  → Not parsed or validated by the monitor layer   │
│  → Transport implementation is responsible for    │
│    interpretation and validation                  │
└─────────────────────────────────────────────────┘
```

### 2.4.3 Parameter lookup rules (monitor-owned)

A parameter `<param>` for a check `<checkName>` and its service `<serviceName>` is always resolved in the following
order:

1. `monitor.check.<checkName>.<param>`
2. `monitor.service.<serviceName>.<param>`
3. `monitor.default.<param>`

Lookup stops at the first match. The three-level lookup applies only to monitor-owned parameters defined in the
corresponding configuration model (`CheckConfig`, `ServiceConfig`).

`monitor.default.<param>` is the global fallback. It applies only to parameters for which a global default makes sense (
see table below).

| parameter               | monitor.default allowed | lookup levels                     |
|-------------------------|-------------------------|-----------------------------------|
| `interval`              | yes                     | check → service → default         |
| `retry.count`           | yes                     | check → default                   |
| `retry.delay`           | yes                     | check → default                   |
| `header.*`              | yes                     | check → service → default (merge) |
| `validation.validators` | yes                     | check → default                   |
| `method`                | yes                     | check → default                   |
| `url`                   | no                      | service only                      |

Transport-specific parameters (in the `monitor.service.<n>.transport.*` or `monitor.tls.*` namespaces) are NOT subject
to monitor lookup rules. They are passed to the transport in `ServiceConfig.transportProperties` without further
processing.

### 2.4.4 Default (fallback) configuration

The name `default` MUST NOT be used as a service or check name. It is reserved for the global fallback namespace
`monitor.default.*`.

### 2.4.5 HTTP headers

Headers are merged in the following order:

1. `monitor.default.header.*` (global default)
2. `monitor.service.<service>.header.*` (overrides default)
3. `monitor.check.<check>.header.*` (overrides service)

The configuration accepts a single value (String) per header. The monitor layer MUST convert each single-value header to
a singleton list (`value` → `List.of(value)`) when assembling `MonitorRequest.headers`. The resulting headers are passed
as `Map<String, List<String>>` to `MonitorRequest`.

Header names MUST be compared case-insensitively (per RFC 7230).

Limitation: the configuration format does not support multi-value headers. A single header name in a `.properties` file
may have only one value.

### 2.4.6 Configuration objects (runtime model)

```java
public class CheckConfig {

    String checkName;
    String serviceName;

    HttpMethod method;
    String path;
    String query;
    String templateFile;

    int retryCount;
    Duration retryDelay;

    Map<String, String> headers;
    List<String> validators;
    Map<String, String> validationParams; // keys without the "validation." prefix

}
```

```java
public class ServiceConfig {

    String serviceName;

    URI url;                    // service base URL; format depends on transport implementation

    Duration interval;

    Map<String, String> headers;

    Map<String, String> transportProperties;  // opaque bag passed to the transport implementation

}
```

Note on `ServiceConfig.url`: the monitor layer passes `url` to the transport in `init()` for HTTP client initialization.
The format and validation rules for `url` are defined by the transport implementation (see Appendix A, §A.3). The
monitor layer MUST NOT validate `url` beyond basic URI syntax.

Note on `transportProperties`: the monitor layer does not parse, validate, or interpret this map. All keys in the
`monitor.service.<n>.transport.*` namespace are loaded into this map (without the `transport.` prefix). The transport
implementation is responsible for validation in `init()`. Example:
`monitor.service.payments.transport.responseTimeout = 10s` → `transportProperties = { "responseTimeout": "10s" }`.

### 2.4.7 Configuration providers

```java
public interface MonitorConfigProvider {

    List<CheckConfig> getChecks();

    List<ServiceConfig> getServices();

}
```

The default implementation loads configuration from a `.properties` file. `Duration` values MUST include a time unit (
e.g. `5s`, `500ms`, `2m`). A bare number without a unit is invalid.

Name uniqueness: `serviceName` and `checkName` MUST be unique within a single configuration. On collision,
initialization MUST fail.

Implementations MUST NOT return `null` from any interface method; empty collections are permitted.

Validation at initialization: `MonitorConfigProvider` MUST verify that every alias in `validation.validators` matches a
registered `Validator` instance. If an alias is not found, initialization MUST fail with a descriptive error message.

---

## 2.5 Execution model

### 2.5.1 Single check run lifecycle

```
  ┌──────────┐
  │ Scheduler│
  │ trigger  │
  └────┬─────┘
       │
       ▼
  ┌──────────────┐    yes    ┌──────────┐
  │ Already      ├──────────→│ Skip     │
  │ running?     │           │ (log)    │
  └──────┬───────┘           └──────────┘
         │ no
         ▼
  ┌──────────────┐
  │ Start virtual│
  │ thread       │
  └──────┬───────┘
         │
         ▼
  ┌──────────────┐
  │ Load config  │
  │ (lookup)     │
  └──────┬───────┘
         │
         ▼
  ┌──────────────┐
  │ Build        │
  │ MonitorReq   │
  │ (path+query) │
  └──────┬───────┘
         │
         ▼
  ┌──────────────┐    templateFile?     ┌──────────────┐
  │ Render       ├─────────────────────→│ TemplateRen- │
  │ body         │                      │ derer        │
  │ (optional)   │                      │              │
  └──────┬───────┘                      └──────┬───────┘
         │◄────────────────────────────────────┘
         ▼
  ┌──────────────┐                      ┌──────────────┐
  │ transport    │  error + retry?      │ Retry delay  │
  │ .execute()   │◄─────────────────────│ (sleep on    │
  └──────┬───────┘                      │  v-thread)   │
         │                              └──────────────┘
         ▼
  ┌──────────────┐
  │ Validate     │  (only if RESPONSE_RECEIVED)
  │ response     │
  └──────┬───────┘
         │
         ▼
  ┌──────────────┐
  │ Map to       │
  │ MonitorStatus│
  └──────┬───────┘
         │
         ▼
  ┌──────────────┐
  │ Notify       │
  │ listeners    │
  └──────────────┘
```

Step-by-step:

1. The scheduler activates the check at its `interval`.

2. The monitor layer verifies that the check is not already running → **skip-if-running** (see §2.5.3).

3. The monitor layer starts the check run on a new virtual thread.

4. The monitor layer loads the check and service configuration via lookup rules (§2.4.3).

5. The monitor layer builds the relative URL for `MonitorRequest`: normalizes `path` (ensuring a leading `/`) and
   appends the query (`path + ?query` if `query` is set). The absolute URL for logging is derived from
   `service.url + request.url`.

6. If `templateFile` is set, the body is rendered by `TemplateRenderer` from `bedrock-wire-template` (see §2.9). When
   `method = GET` and `templateFile` is set, the transport SHOULD ignore the body.

7. The monitor layer calls `transport.execute(request)` and obtains a `MonitorResult`.

8. If the transport returns a retryable status and retries are configured, the retry policy is applied (see §2.5.4).

9. Validators run against the `MonitorResult` (see §2.7).

10. Results are mapped to `MonitorStatus` (see §2.5.5).

11. The `MonitorExecutionResult` is delivered to all registered listeners.

### 2.5.2 Scheduler — dispatch semantics

The scheduler uses **fixed-rate** semantics: checks are dispatched at fixed intervals measured from the **start** of the
previous run, regardless of its duration.

Recommended implementation: a simple single-thread `ScheduledExecutorService` for scheduling triggers; the actual check
run is dispatched onto a virtual thread. The scheduler thread only fires the trigger; the entire check-run logic runs on
a virtual thread.

### 2.5.3 Scheduling policy: skip-if-running

Rule: only one running instance is permitted per check. If the scheduler fires a check again while the previous run is
still in flight, the new run is **skipped**. Skip events SHOULD be logged; the log SHOULD include `checkName`, the
scheduled trigger time, and the `requestId` of the currently running run.

Implementation: an `AtomicBoolean` per check is sufficient. Implementations SHOULD expose a per-check skip event counter
as a metric. Implementations SHOULD log the first skip at WARN level; subsequent skips SHOULD be aggregated.

### 2.5.4 Retry policy

Configuration: `retry.count` (default: 0), `retry.delay` (default: 1s).

The total number of attempts is always `retry.count + 1`. Sequence: attempt → delay → attempt → delay → attempt. The
retry delay is implemented as `Thread.sleep()` on the virtual thread — the carrier thread is released.

Retry behavior per `TransportStatus`:

| TransportStatus     | Retry                                                 |
|---------------------|-------------------------------------------------------|
| `TIMEOUT`           | SHOULD                                                |
| `CONNECT_ERROR`     | SHOULD                                                |
| `IO_ERROR`          | SHOULD NOT (default); MAY be enabled by configuration |
| `RESPONSE_RECEIVED` | never                                                 |
| `POOL_EXHAUSTED`    | MUST NOT                                              |

### 2.5.5 Status mapping

| TransportStatus     | ValidationVerdict | MonitorStatus |
|---------------------|-------------------|---------------|
| `RESPONSE_RECEIVED` | `PASS`            | `UP`          |
| `RESPONSE_RECEIVED` | `WARN`            | `WARN`        |
| `RESPONSE_RECEIVED` | `FAIL`            | `DOWN`        |
| `TIMEOUT`           | –                 | `DOWN`        |
| `CONNECT_ERROR`     | –                 | `DOWN`        |
| `IO_ERROR`          | –                 | `DOWN`        |
| `POOL_EXHAUSTED`    | –                 | `ERROR`       |

### 2.5.6 Status ERROR (internal monitor error)

`ERROR` is produced by: an unconfigured service/check, an exception in the monitor layer, an exception inside a
validator, a render error from `TemplateRenderer` (invalid UTF-8 in the template file, undefined variable, FreeMarker
syntax error, etc.), or `POOL_EXHAUSTED` from the transport. `ERROR` signals an implementation or internal-resource
failure, not the state of the monitored service.

---

## 2.6 Transport SPI

### 2.6.1 Transport SPI responsibility

The transport SPI defines the contract between the monitor layer and the HTTP transport implementation. The monitor
layer is unaware of any concrete HTTP client library — it communicates exclusively through this interface.

The transport implementation is responsible for: initializing and managing HTTP clients and connection pools, TLS
configuration, mapping internal exceptions to `TransportStatus`, timeout management, and graceful shutdown of its
resources.

### 2.6.2 MonitorTransport interface

```java
public interface MonitorTransport {

    /**
     * Initializes the transport with service configuration.
     * Called once at monitor startup, before the first execute().
     *
     * @param services list of service configurations
     * @throws IllegalStateException    if the transport is already initialized
     * @throws IllegalArgumentException if the configuration is invalid
     */
    void init(List<ServiceConfig> services);

    /**
     * Executes a single HTTP request synchronously.
     * Called on the check run's virtual thread.
     * MUST never throw — always returns a MonitorResult.
     *
     * @param request HTTP request descriptor
     * @return transport result; never null
     */
    MonitorResult execute(MonitorRequest request);

    /**
     * Releases the transport's resources.
     * MUST be idempotent.
     *
     * @param timeout maximum time for graceful shutdown
     */
    void close(Duration timeout);

}
```

### 2.6.3 MonitorRequest

```java
public class MonitorRequest {

    String serviceName;           // identifies the target service (lookup key for the transport)
    HttpMethod method;
    URI url;                      // relative URI (path + query); MUST start with '/'
    Map<String, List<String>> headers;
    String body;

}
```

### 2.6.4 TransportStatus

```java
public enum TransportStatus {
    RESPONSE_RECEIVED,   // HTTP response received (any status code)
    TIMEOUT,             // response timeout elapsed
    CONNECT_ERROR,       // could not establish a connection
    IO_ERROR,            // IO error during communication
    POOL_EXHAUSTED       // connection pool exhausted; internal transport problem
}
```

The semantics are normative for all transport implementations — see Appendix A, §A.2.

### 2.6.5 MonitorResult

```java
public class MonitorResult {

    TransportStatus transportStatus;

    int httpStatus;              // 0 if transportStatus != RESPONSE_RECEIVED
    String responseBody;         // empty string if transportStatus != RESPONSE_RECEIVED
    Map<String, List<String>> headers; // empty map if transportStatus != RESPONSE_RECEIVED

    String errorMessage;         // non-null when transportStatus != RESPONSE_RECEIVED;
    // MUST distinguish the specific error type

    Duration transportDuration;  // duration of the last HTTP attempt

}
```

Contract: `execute()` MUST always return a `MonitorResult` — it MUST never throw.

---

## 2.7 Validation

### 2.7.1 Validator interface

```java
public interface Validator {

    String alias();

    ValidationResult validate(MonitorResult result, Map<String, String> params);

}
```

### 2.7.2 ValidationResult

```java
public class ValidationResult {

    ValidationVerdict verdict;  // PASS, WARN, FAIL
    String message;             // diagnostic; MAY be null for PASS

}
```

### 2.7.3 Validator execution semantics

Validators run in the order defined by `validation.validators`, and only when `transportStatus == RESPONSE_RECEIVED`.
Aggregate result: at least one FAIL → FAIL; at least one WARN (no FAIL) → WARN; otherwise → PASS.

### 2.7.4 Built-in validators

| alias         | parameter   | behavior                                                                                                                                  |
|---------------|-------------|-------------------------------------------------------------------------------------------------------------------------------------------|
| `httpStatus`  | httpStatus  | FAIL if the status does not match; supports single (`200`), set (`200,204`), range (`200-299`), and combinations (`200-204,301`)          |
| `contains`    | contains    | FAIL if the substring is missing (case-sensitive)                                                                                         |
| `regex`       | regex       | FAIL if `Pattern.find()` does not match; SHOULD cache the compiled `Pattern` per config                                                   |
| `maxDuration` | maxDuration | WARN if `transportDuration` exceeds the limit                                                                                             |
| `xpath`       | xpath       | FAIL if the XPath 1.0 expression does not match; XML parse error → FAIL; evaluation: non-empty node-set / string / true / non-zero = PASS |

---

## 2.8 Output model

### 2.8.1 MonitorExecutionResult

```java
public class MonitorExecutionResult {

    String checkName;
    String serviceName;
    Instant startedAt;
    Instant finishedAt;
    Duration executionDuration;     // finishedAt - startedAt
    int attempts;
    String requestId;               // UUID v4
    MonitorStatus status;           // UP, DOWN, WARN, ERROR
    String message;                 // first FAIL message; else first WARN; else null
    MonitorResult transport;

}
```

### 2.8.2 Listener

```java
public interface MonitorResultListener {

    void onResult(MonitorExecutionResult result);

}
```

An exception thrown by a listener MUST be logged but MUST NOT alter the check run's status.

---

## 2.9 Templates

If `templateFile` is set, the body is loaded and rendered via `bedrock-wire-template` (Apache FreeMarker). All template
syntax is standard FreeMarker.

### 2.9.1 Parameters

Keys `monitor.check.<check>.param.<name>` are passed to the template engine as a `Map<String, Object>`. Inside the
template they are addressable as `${name}`.

### 2.9.2 Dynamic values (FreeMarker built-ins)

| purpose                                    | syntax                                      |
|--------------------------------------------|---------------------------------------------|
| UUID                                       | `${statics['java.util.UUID'].randomUUID()}` |
| Timestamp ISO 8601 (second precision)      | `${.now?iso_utc}`                           |
| Timestamp ISO 8601 (millisecond precision) | `${.now?iso_utc_ms}`                        |
| Default value                              | `${name!'fallback'}`                        |
| Conditional                                | `<#if cond>...</#if>`                       |

Full reference: `bedrock-wire-template/README.md` and
the [FreeMarker manual](https://freemarker.apache.org/docs/index.html).

### 2.9.3 Encoding

Template files MUST be UTF-8 (strict). Invalid UTF-8 → the check run terminates with `MonitorStatus.ERROR` and
`TemplateNotFoundException` in the message.

### 2.9.4 Render errors

Render failures from `TemplateRenderer` (undefined variable, FreeMarker syntax error, type mismatch, etc.) → the check
run terminates with `MonitorStatus.ERROR` and `TemplateRenderException` in the message. The error message includes the
template path, line/column, and the list of available parameters (`availableParams=[...]`).

---

## 2.10 Runtime components

### 2.10.1 Transport lifecycle

```
  ┌────────────────┐
  │ Monitor start  │
  └───────┬────────┘
          ▼
  ┌────────────────┐     ┌────────────────┐
  │ Load config    │────→│ Validate       │
  │ (provider)     │     │ (fail-fast)    │
  └───────┬────────┘     └────────────────┘
          ▼
  ┌────────────────┐
  │ transport      │     Transport creates clients, pools, TLS
  │ .init(services)│
  └───────┬────────┘
          ▼
  ┌────────────────┐
  │ Start scheduler│     Virtual threads call transport.execute()
  └───────┬────────┘
          ▼  (shutdown)
  ┌────────────────┐
  │ Stop scheduler │     Grace period: shutdownTimeout
  │ wait in-flight │
  └───────┬────────┘
          ▼
  ┌────────────────┐
  │ transport      │     Transport closes pools, releases resources
  │ .close(timeout)│
  └────────────────┘
```

### 2.10.2 Validator lifecycle

Validators MUST be thread-safe and stateless. Instantiated once and shared across check runs.

### 2.10.3 Concurrency

Check runs execute on virtual threads (Java 21+). Maximum concurrency = the number of configured checks.

### 2.10.4 Graceful shutdown

1. The scheduler stops dispatching new check runs.
2. Running check runs complete within `shutdownTimeout` (default `30s`).
3. After the grace period: remaining runs are interrupted.
4. `transport.close(remainingTimeout)` is called once check runs finish or are interrupted.

---

## 2.11 Configuration reference

### 2.11.1 Service parameters (monitor-owned)

| parameter                        | type     | required | description           |
|----------------------------------|----------|----------|-----------------------|
| `monitor.service.<n>.url`        | URI      | yes      | service base URL      |
| `monitor.service.<n>.interval`   | Duration | no       | check dispatch period |
| `monitor.service.<n>.header.<n>` | String   | no       | HTTP header           |

### 2.11.2 Service parameters (transport-specific)

The namespace `monitor.service.<n>.transport.*` → `ServiceConfig.transportProperties` (without the `transport.` prefix).
See Appendix B for keys supported by the default `WireClientTransport`.

### 2.11.3 Check parameters

| parameter                                 | type     | required | description                                    |
|-------------------------------------------|----------|----------|------------------------------------------------|
| `monitor.check.<n>.service`               | String   | yes      | service name                                   |
| `monitor.check.<n>.interval`              | Duration | yes*     | dispatch period                                |
| `monitor.check.<n>.method`                | String   | no       | HTTP method, default `POST` (case-insensitive) |
| `monitor.check.<n>.path`                  | String   | no       | path appended to the service URL               |
| `monitor.check.<n>.query`                 | String   | no       | query string (without `?`)                     |
| `monitor.check.<n>.templateFile`          | String   | no       | path to the template file                      |
| `monitor.check.<n>.retry.count`           | int      | no       | number of retry attempts, default `0`          |
| `monitor.check.<n>.retry.delay`           | Duration | no       | delay before retry, default `1s`               |
| `monitor.check.<n>.header.<n>`            | String   | no       | HTTP header                                    |
| `monitor.check.<n>.validation.validators` | List     | no       | comma-separated list of validators             |
| `monitor.check.<n>.validation.<alias>`    | String   | no       | validator parameter                            |

### 2.11.4 Shutdown parameters

| parameter                          | type     | default | description              |
|------------------------------------|----------|---------|--------------------------|
| `monitor.executor.shutdownTimeout` | Duration | `30s`   | grace period at shutdown |

---

## 2.12 Complete configuration example

This example uses the default `WireClientTransport`. Transport-specific parameters live in the `transport.*` namespace.

```properties
# === Global defaults ===
monitor.default.interval=30s
monitor.default.retry.count=1
monitor.default.retry.delay=2s
monitor.default.validation.validators=httpStatus
monitor.default.header.Accept=application/xml
monitor.default.header.X-Client-Id=bedrock-monitor
# === TLS profiles (WireClientTransport-specific) ===
monitor.tls.payments-tls.clientCert=/certs/payments-client.p12
monitor.tls.payments-tls.clientCertPassword=secret
monitor.tls.payments-tls.clientCertType=PKCS12
monitor.tls.payments-tls.trustStore=/certs/payments-truststore.p12
monitor.tls.payments-tls.trustStorePassword=trustsecret
monitor.tls.payments-tls.trustStoreType=PKCS12
monitor.tls.internal-tls.trustStore=/certs/internal-truststore.p12
monitor.tls.internal-tls.trustStorePassword=internalpass
monitor.tls.internal-tls.trustStoreType=PKCS12
# === Services ===
monitor.service.payments.url=https://payments.example.com
monitor.service.payments.header.Content-Type=application/xml
monitor.service.payments.transport.responseTimeout=10s
monitor.service.payments.transport.connectionTimeout=3s
monitor.service.payments.transport.tlsProfile=payments-tls
monitor.service.payments-slow.url=https://payments.example.com
monitor.service.payments-slow.header.Content-Type=application/xml
monitor.service.payments-slow.transport.responseTimeout=15s
monitor.service.payments-slow.transport.connectionTimeout=3s
monitor.service.payments-slow.transport.tlsProfile=payments-tls
monitor.service.internal.url=https://internal.example.com
monitor.service.internal.transport.responseTimeout=3s
monitor.service.internal.transport.connectionTimeout=3s
monitor.service.internal.transport.tlsProfile=internal-tls
# === Checks ===
monitor.check.paymentsHealth.service=payments
monitor.check.paymentsHealth.method=POST
monitor.check.paymentsHealth.path=/api/health
monitor.check.paymentsHealth.templateFile=/templates/health-check.xml
monitor.check.paymentsHealth.param.clientId=monitor-prod
monitor.check.paymentsHealth.param.region=eu-west-1
monitor.check.paymentsHealth.validation.validators=httpStatus,contains
monitor.check.paymentsHealth.validation.httpStatus=200
monitor.check.paymentsHealth.validation.contains=<status>OK</status>
monitor.check.paymentsMsg.service=payments-slow
monitor.check.paymentsMsg.method=POST
monitor.check.paymentsMsg.path=/api/process
monitor.check.paymentsMsg.query=format=xml
monitor.check.paymentsMsg.templateFile=/templates/process.xml
monitor.check.paymentsMsg.header.X-Priority=high
monitor.check.paymentsMsg.validation.validators=httpStatus,regex,maxDuration
monitor.check.paymentsMsg.validation.httpStatus=200
monitor.check.paymentsMsg.validation.regex=<r>OK</r>
monitor.check.paymentsMsg.validation.maxDuration=8s
monitor.check.internalPing.service=internal
monitor.check.internalPing.method=GET
monitor.check.internalPing.path=/ping
monitor.check.internalPing.interval=10s
monitor.check.internalPing.retry.count=0
monitor.check.internalPing.validation.validators=httpStatus
monitor.check.internalPing.validation.httpStatus=200
# === Shutdown ===
monitor.executor.shutdownTimeout=30s
```

### 2.12.1 Example template file

`/templates/health-check.xml` (FreeMarker syntax):

```xml

<healthCheck>
    <clientId>${clientId}</clientId>
    <region>${region}</region>
    <requestId>${statics['java.util.UUID'].randomUUID()}</requestId>
    <timestamp>${.now?iso_utc}</timestamp>
</healthCheck>
```

The `clientId` and `region` parameters are supplied via `monitor.check.paymentsHealth.param.*`. UUID and timestamp are
resolved by FreeMarker built-ins on each render.

---

# Appendix A: MonitorTransport SPI reference

## A.1 Lifecycle contract

```
  init(services)  ──→  execute(request) × N  ──→  close(timeout)
       │                      │                         │
   fail-fast on           thread-safe,              idempotent,
   bad config             never throws              releases all
                          exceptions                resources
```

**init()**: Called once. The transport creates HTTP clients for all services. Invalid configuration → fail-fast with a
descriptive exception.

**execute()**: Called concurrently from virtual threads. MUST be thread-safe. MUST never throw — always returns a
`MonitorResult`.

**close()**: Called once at shutdown. Idempotent. After `close()`, `execute()` MAY return a `MonitorResult` with
`IO_ERROR` or `POOL_EXHAUSTED`.

## A.2 TransportStatus mapping rules

| situation                                                 | TransportStatus     |
|-----------------------------------------------------------|---------------------|
| HTTP response received (any status code)                  | `RESPONSE_RECEIVED` |
| response timeout (no bytes received)                      | `TIMEOUT`           |
| connection refused / DNS failure / TLS handshake error    | `CONNECT_ERROR`     |
| read timeout / response body too large / general IO error | `IO_ERROR`          |
| connection pool exhausted                                 | `POOL_EXHAUSTED`    |

**Critical rule**: `POOL_EXHAUSTED` MUST NOT be mapped to `TIMEOUT` or `CONNECT_ERROR`. Incorrect mapping causes retry
amplification.

## A.3 ServiceConfig contract

Transport implementations receive `List<ServiceConfig>` in `init()`. Relevant fields: `serviceName` (lookup key),
`url` (base URL), and `transportProperties` (opaque transport-specific configuration).

Transport implementations MUST document: the required format of `url`, the supported keys in `transportProperties` along
with their types and defaults, and the behavior on unknown keys.

## A.4 Thread safety

`execute()` is invoked from virtual threads with no synchronization on the monitor-layer side. The transport MUST be
fully thread-safe.

## A.5 Testing support

The transport SPI makes the monitor layer easy to test. A test implementation can return canned `MonitorResult` objects
without any network communication:

```java
public class StubTransport implements MonitorTransport {

    private final Map<String, MonitorResult> responses = new ConcurrentHashMap<>();

    public void stub(String serviceName, MonitorResult result) {
        responses.put(serviceName, result);
    }

    @Override
    public void init(List<ServiceConfig> services) {
    }

    @Override
    public MonitorResult execute(MonitorRequest request) {
        return responses.getOrDefault(request.getServiceName(), defaultOk());
    }

    @Override
    public void close(Duration timeout) {
    }

}
```

---

# Appendix B: WireClientTransport (default implementation)

The default transport implementation, built on `bedrock-wire-client`. Shipped as part of `bedrock-wire-monitor`.

## B.1 Architecture

```
┌──────────────────────────────────────────────────┐
│              WireClientTransport                  │
│                                                    │
│  ┌──────────────┐        ┌──────────────────┐    │
│  │ init()       │        │ execute()         │    │
│  │              │        │                    │    │
│  │ ServiceConfig│        │ MonitorRequest     │    │
│  │     ↓        │        │     ↓              │    │
│  │ HttpClient   │        │ HttpRequest        │    │
│  │ Config       │        │ (relative URL)     │    │
│  │     ↓        │        │     ↓              │    │
│  │ Registry     │        │ HttpClient         │    │
│  │ .get()       │        │ .execute().block() │    │
│  │              │        │     ↓              │    │
│  │ TLS profiles │        │ HttpResponse       │    │
│  │ registered   │        │     ↓              │    │
│  │              │        │ MonitorResult      │    │
│  └──────────────┘        └──────────────────┘    │
│                                                    │
│  ┌────────────────────────────────────────────┐  │
│  │           HttpClientRegistry                │  │
│  │   (connection pools per TransportTarget)    │  │
│  └────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────┘
```

## B.2 Dependencies

- `bedrock-wire-client` 1.0+ (Java 17+)
- Reactor Netty (transitive)

## B.3 Transport properties

| key                   | type     | default   | description                                                      |
|-----------------------|----------|-----------|------------------------------------------------------------------|
| `responseTimeout`     | Duration | `5s`      | timeout for the first byte; → `HttpClientConfig.responseTimeout` |
| `connectionTimeout`   | Duration | `5s`      | TCP + TLS + DNS; → `HttpClientConfig.connectTimeout`             |
| `readTimeout`         | Duration | `10s`     | inter-byte idle; → `HttpClientConfig.readTimeout`                |
| `maxResponseBodySize` | int      | `1048576` | max body in bytes; → `HttpClientConfig.maxResponseBodySize`      |
| `maxConnections`      | int      | `50`      | max TCP per target; → `HttpClientConfig.maxConnections`          |
| `tlsProfile`          | String   | `null`    | TLS profile (see B.5)                                            |

Unknown keys: ignored with a WARN log.

Hardcoded from `bedrock-wire-client`: `maxPendingRequests` (100), `poolAcquisitionTimeout` (5s), `keepAliveTimeout` (
60s).

## B.4 URL rules

`ServiceConfig.url` MUST contain only **scheme + host + port**. It MUST NOT contain a path, query, or fragment. This
follows from validation in `HttpClientConfig.builder()`.

## B.5 TLS profiles

Namespace `monitor.tls.<profileName>.*`. Specific to `WireClientTransport`.

| parameter                   | type    | required | description                                                   |
|-----------------------------|---------|----------|---------------------------------------------------------------|
| `clientCert`                | Path    | no       | client certificate                                            |
| `clientCertPassword`        | String  | no       | keystore password                                             |
| `clientCertType`            | String  | no       | `PKCS12` / `JKS`                                              |
| `clientCertAlias`           | String  | no       | alias inside the keystore                                     |
| `trustStore`                | Path    | no       | custom truststore; null = JVM default                         |
| `trustStorePassword`        | String  | no       | truststore password                                           |
| `trustStoreType`            | String  | no       | `PKCS12` / `JKS`                                              |
| `hostnameVerification`      | boolean | no       | default `true`                                                |
| `allowInsecureInProduction` | boolean | no       | default `false`; required when `hostnameVerification = false` |

Rules: `hostnameVerification = false` requires `allowInsecureInProduction = true`, otherwise `init()` fails with
`InsecureConfigurationException`. MUST NOT be used in production.

## B.6 ServiceConfig → HttpClientConfig mapping

```java
HttpClientConfig clientConfig = HttpClientConfig.builder()
        .clientId(serviceConfig.getServiceName())
        .baseUrl(serviceConfig.getUrl())
        .connectTimeout(parseDuration(props, "connectionTimeout", Duration.ofSeconds(5)))
        .responseTimeout(parseDuration(props, "responseTimeout", Duration.ofSeconds(5)))
        .readTimeout(parseDuration(props, "readTimeout", Duration.ofSeconds(10)))
        .maxResponseBodySize(parseInt(props, "maxResponseBodySize", 1_048_576))
        .maxConnections(parseInt(props, "maxConnections", 50))
        .tlsConfigName(props.get("tlsProfile"))
        .build();
```

One `HttpClient` per service, cached in `HttpClientRegistry`. Services with the same transport target (scheme + host +
port + tlsConfigName) share a connection pool.

## B.7 Exception → TransportStatus mapping

| Exception                                        | TransportStatus     | errorMessage                        |
|--------------------------------------------------|---------------------|-------------------------------------|
| `RequestTimeoutException`                        | `TIMEOUT`           | `"ResponseTimeout"`                 |
| `TransportException` / `ConnectException`        | `CONNECT_ERROR`     | `"ConnectionRefused"`               |
| `TransportException` / `ConnectTimeoutException` | `CONNECT_ERROR`     | `"ConnectTimeout"`                  |
| `TransportException` / `IOException`             | `IO_ERROR`          | `"IOError"`                         |
| `TransportException` / `PrematureCloseException` | `IO_ERROR`          | `"PrematureClose"`                  |
| `ReadTimeoutException`                           | `IO_ERROR`          | `"ReadTimeout"`                     |
| `ResponseSizeExceededException`                  | `IO_ERROR`          | `"ResponseSizeExceeded"`            |
| `PoolAcquisitionTimeoutException`                | `POOL_EXHAUSTED`    | `"PoolExhausted"`                   |
| `RedirectNotSupportedException`                  | `RESPONSE_RECEIVED` | 3xx status propagated as a response |

`RedirectNotSupportedException`: the transport catches the exception, extracts the HTTP status from
`exception.getStatusCode()`, and returns a `MonitorResult` with `RESPONSE_RECEIVED`.

## B.8 Response body charset

Always **UTF-8**, regardless of the `Content-Type` charset. This matches the behavior of `bedrock-wire-client`.

---