# Architecture guide — bedrock-wire-client

Deep technical reference for maintainers and contributors. For usage documentation, see [README.md](README.md).

---

## Table of contents

1. [Design principles](#design-principles)
2. [Package structure](#package-structure)
3. [Class dependency graph](#class-dependency-graph)
4. [Key concepts](#key-concepts)
5. [Connection pool model](#connection-pool-model)
6. [Request execution pipeline](#request-execution-pipeline)
7. [Timeout model](#timeout-model)
8. [TLS architecture](#tls-architecture)
9. [Exception hierarchy](#exception-hierarchy)
10. [Observability](#observability)
11. [Spring auto-configuration](#spring-auto-configuration)
12. [Auto-registration and monitor back-off](#auto-registration-and-monitor-back-off)
13. [Bean lifecycle](#bean-lifecycle)
14. [Design decisions and rationale](#design-decisions-and-rationale)

---

## Design principles

**Registry-centric.**
All `HttpClient` instances are created and cached by `HttpClientRegistry`. Application code never
constructs clients directly. The registry enforces capacity limits, config consistency, and orderly shutdown.

**Pool sharing by transport target.**
Clients that hit the same `scheme + host + port + tlsConfigName` automatically share one TCP connection pool.
This prevents pool proliferation when multiple business clients target the same service.

**Immutable configuration.**
`HttpClientConfig`, `TlsConfig`, `HttpRequest`, `HttpResponse` are all `@Value` (Lombok immutable).
Once built, configuration cannot be modified. Changing a client's config requires a new `clientId`.

**Clean exception boundary.**
Every error emitted by `HttpClient.execute()` is a `BedrockWireException` subtype. Callers never see
raw Netty, JDK, or Reactor exceptions — they are caught and wrapped inside `HttpClientImpl`.

**Reactive core, blocking support.**
`execute()` returns `Mono<HttpResponse>`. For synchronous use (e.g., monitor checks), callers call `.block()`.
The Mono is cold — no network activity occurs until subscription.

**No redirects.**
HTTP 3xx responses (except 304) emit `RedirectNotSupportedException`. The library does not follow redirects.
This is intentional — redirect handling is a policy decision that belongs to the caller.

---

## Package structure

```
cz.syntea.bedrock.wire.classic/
├── config/         HttpClientConfig, HttpClientRegistryConfig — immutable per-client and registry-level config
├── model/          HttpMethod, HttpRequest, HttpResponse, TlsConfig, TransportTarget, RequestOutcome
├── registry/       HttpClientRegistry (interface), HttpClientRegistryImpl, HttpClientImpl,
│                   TlsSslContextFactory, AliasPinningKeyManager, PoolMetricsReporter, WireLogger
├── observability/  WireMetricsCollector, TraceHeaderPropagator (SPIs) + NoOp defaults
├── exception/      BedrockWireException + 10 subtypes
├── spring/         BedrockWireClientAutoConfiguration, BedrockWireClientProperties,
│                   ParamFileClientRegistrar, ParamFileClientConfigAdapter,
│                   ClientProperties, TlsProfileProperties
└── json/           JsonFormat (toString utility)
```

Dependency flow: `spring → registry → {config, model, observability, exception} → json`.
The `registry` package contains all implementation logic. Everything else is either configuration,
model, or SPI.

---

## Class dependency graph

### Full module dependency map

```mermaid
graph TD
    subgraph spring["spring (auto-configuration)"]
        BWCAC[BedrockWireClientAutoConfiguration]
        BWCP[BedrockWireClientProperties]
        PFCR[ParamFileClientRegistrar]
        PFCA[ParamFileClientConfigAdapter]
        CP[ClientProperties]
        TPP[TlsProfileProperties]
    end

    subgraph registry["registry (core)"]
        HCR_IF[HttpClientRegistry<br/><i>interface</i>]
        HCR[HttpClientRegistryImpl]
        HC_IF[HttpClient<br/><i>interface</i>]
        HCI[HttpClientImpl]
        TSSF[TlsSslContextFactory]
        APKM[AliasPinningKeyManager]
        PMR[PoolMetricsReporter]
        WL[WireLogger]
    end

    subgraph config["config"]
        HCC[HttpClientConfig]
        HCRC[HttpClientRegistryConfig]
    end

    subgraph model["model"]
        TT[TransportTarget]
        TC[TlsConfig]
        HReq[HttpRequest]
        HRes[HttpResponse]
        HM[HttpMethod]
        RO[RequestOutcome]
    end

    subgraph observability["observability (SPI)"]
        WMC[WireMetricsCollector<br/><i>interface</i>]
        THP[TraceHeaderPropagator<br/><i>interface</i>]
        NWMC[NoOpWireMetricsCollector]
        NTHP[NoOpTraceHeaderPropagator]
    end

    subgraph exception["exception"]
        BWE[BedrockWireException]
        RTE[RequestTimeoutException]
        RDTE[ReadTimeoutException]
        TE[TransportException]
        PATE[PoolAcquisitionTimeoutException]
        RSEE[ResponseSizeExceededException]
        RNSE[RedirectNotSupportedException]
        TCE[TlsConfigurationException]
        RCE[RegistryCapacityException]
        RCLE[RegistryClosedException]
        ICE[InsecureConfigurationException]
    end

    subgraph external["external"]
        WC[Spring WebClient]
        RN[Reactor Netty]
        CPOOL[ConnectionProvider<br/><i>Reactor Netty</i>]
    end

    %% Spring wiring
    BWCAC -->|creates| HCR
    BWCAC -->|creates| NWMC
    BWCAC -->|creates| NTHP
    BWCAC -->|conditionally creates| PFCR
    PFCR -->|uses| PFCA
    PFCR -->|registers clients via| HCR_IF
    PFCA -->|produces| CP
    PFCA -->|produces| TPP
    CP -->|builds| HCC
    TPP -->|builds| TC

    %% Registry
    HCR -.->|implements| HCR_IF
    HCR -->|creates| HCI
    HCR -->|manages| CPOOL
    HCR -->|uses| TSSF
    HCR -->|uses| PMR
    HCI -.->|implements| HC_IF
    HCI -->|delegates to| WC
    HCI -->|logs via| WL

    %% TLS
    TSSF -->|uses| APKM
    TSSF -->|reads| TC

    %% Config
    HCC -->|derives| TT
    HCR -->|keyed by clientId from| HCC

    %% Observability
    HCI -->|reports to| WMC
    PMR -->|reports to| WMC
    HCC -->|contains| THP

    %% Exceptions
    HCI -->|emits| BWE
    RTE -.->|extends| BWE
    RDTE -.->|extends| BWE
    TE -.->|extends| BWE
    PATE -.->|extends| BWE
    RSEE -.->|extends| BWE
    RNSE -.->|extends| BWE
    RCE -.->|extends| BWE
    RCLE -.->|extends| BWE
```

### Layered view (simplified)

```
┌─────────────────────────────────────────────────────────────┐
│  spring/                                                     │
│  BedrockWireClientAutoConfiguration                          │
│  ParamFileClientRegistrar (conditional)                      │
│      ↓ creates beans                                         │
├─────────────────────────────────────────────────────────────┤
│  registry/                                                   │
│  HttpClientRegistry ──→ HttpClientImpl ──→ WireLogger        │
│       ↕ manages              ↕ uses                          │
│  ConnectionProvider      WebClient (Spring)                  │
│  TlsSslContextFactory    PoolMetricsReporter                 │
├─────────────────────────────────────────────────────────────┤
│  config/              model/              observability/     │
│  HttpClientConfig     TransportTarget     WireMetricsCollector│
│  RegistryConfig       TlsConfig           TraceHeaderPropagator│
│                       HttpRequest/Response                   │
├─────────────────────────────────────────────────────────────┤
│  exception/                                                  │
│  BedrockWireException + 10 subtypes                          │
└─────────────────────────────────────────────────────────────┘
```

---

## Key concepts

### HttpClientRegistry — the central singleton

The registry is the single owner of all connection pools and client instances. It provides:

- **Create-or-return:** `get(HttpClientConfig)` creates a client on first call, returns the cached instance on
  subsequent calls with the same `clientId`.
- **Lookup by ID:** `get(String clientId)` retrieves a previously registered client.
- **Atomic capacity enforcement:** the capacity check and client creation happen inside `ConcurrentHashMap.compute()`,
  preventing races.
- **Config conflict detection:** calling `get(config)` with the same `clientId` but different config throws
  `IllegalArgumentException`.
- **TLS profile management:** `registerTlsConfig()` must be called before any client that references the profile.
- **Orderly shutdown:** `close()` disposes all connection pools. Subsequent `get()` or `execute()` calls fail with
  `RegistryClosedException`.

### TransportTarget — pool identity

```
TransportTarget = scheme + host + port + tlsConfigName
```

Two clients with the same `TransportTarget` share one `ConnectionProvider` (Reactor Netty connection pool).
This means a `payments` client and a `payments-slow` client (same host, different timeouts) share
connections — only the per-request timeout settings differ.

A `null` `tlsConfigName` means "JVM-default TLS" and is its own distinct identity. It never matches
a named TLS profile, so it always gets a separate pool.

Normalization: scheme and host are lowercased, trailing dot on FQDN is stripped, missing port defaults
to 443 (https) or 80 (http).

### HttpClientConfig — immutable per-client settings

Built via Lombok `@Builder` with a custom `build()` that validates `baseUrl`:

- Must have scheme + host
- Must NOT have path, query, fragment, or trailing slash
- Examples: `https://payments.example.com`, `https://payments.example.com:8443`

The `baseUrl` restriction exists because the connection pool identity is derived from it. Path components
would create ambiguity about which pool a request should use.

---

## Connection pool model

```
HttpClientRegistry
│
├── ConnectionProvider("https://payments.example.com:443@payments-tls")
│   ├── HttpClient("payments")        connectTimeout=3s, responseTimeout=10s
│   └── HttpClient("payments-slow")   connectTimeout=3s, responseTimeout=30s
│
├── ConnectionProvider("https://internal.example.com:443@internal-tls")
│   └── HttpClient("internal")        connectTimeout=5s, responseTimeout=5s
│
└── ConnectionProvider("https://httpbin.org:443")     ← null TLS = JVM default
    └── HttpClient("httpbin")          connectTimeout=5s, responseTimeout=30s
```

Pool parameters are taken from the **first** client that creates the pool. Subsequent clients sharing
the same target inherit that pool — they cannot change `maxConnections` or `keepAliveTimeout` after
pool creation.

Pool-level settings from `HttpClientConfig`:

- `maxConnections` — max TCP connections per target (default: 50)
- `maxPendingRequests` — max requests waiting for a pool slot (default: 100)
- `poolAcquisitionTimeout` — max wait for a free slot (default: 5s)
- `keepAliveTimeout` — idle connection eviction (default: 60s)

---

## Request execution pipeline

```
execute(HttpRequest)
│
├── 1. Validate request (sync, fail-fast)
│   ├── null check
│   ├── url must be relative, must start with /
│   └── method must not be null
│
├── 2. Check registry not closed
│
├── 3. Build full URI: baseUrl + request.url
│
├── 4. Mono.defer (re-checks closed at subscription time)
│   │
│   ├── 5. Merge headers: defaultHeaders → traceHeaders → requestHeaders
│   │
│   ├── 6. Build WebClient call
│   │   ├── exchangeToMono (captures status + headers before body)
│   │   ├── Reject 3xx (except 304) → RedirectNotSupportedException
│   │   └── Stream body via bodyToFlux(DataBuffer)
│   │       ├── Check cumulative size per chunk → ResponseSizeExceededException
│   │       ├── Copy bytes + release DataBuffer immediately
│   │       └── Decode accumulated bytes to UTF-8 once (avoids split multi-byte chars)
│   │
│   ├── 7. onErrorMap: wrap JDK/Netty exceptions → BedrockWireException subtypes
│   │
│   └── 8. Metrics + logging hooks
│       ├── doOnSubscribe: start metrics clock, increment in-flight counter
│       ├── doOnSuccess/doOnError: record metrics
│       ├── doOnError: WireLogger.transportError
│       └── doFinally: decrement in-flight counter
│
└── Return cold Mono<HttpResponse>
```

### Header merge order (lowest → highest priority)

1. `HttpClientConfig.defaultHeaders` — static defaults
2. `TraceHeaderPropagator.headersForRequest()` — dynamic trace context
3. `HttpRequest.headers` — per-request overrides

Same-name headers are **replaced** (not appended). Comparison is case-insensitive per RFC 7230.

### Response body streaming

The body is NOT buffered into a single `String` via `bodyToMono(String.class)`.
Instead, it streams via `bodyToFlux(DataBuffer.class)` with inline size checking:

1. Each chunk's byte count is accumulated
2. If cumulative size exceeds `maxResponseBodySize`, the flux is cancelled immediately
3. Raw bytes are copied into a `ByteArrayOutputStream` and each `DataBuffer` is released
4. After the last chunk, the accumulated bytes are decoded to UTF-8 in a single pass

This approach prevents oversized bodies from being allocated on the heap and avoids
multi-byte character boundary issues (e.g., `ř` split across two DataBuffers).

---

## Timeout model

```
                    connectTimeout               responseTimeout                readTimeout
                    ──────────────               ───────────────                ───────────
Timeline:    DNS ──→ TCP ──→ TLS ──→ Send Req ──→ Wait ──→ First Byte ──→ Body Transfer ──→ Done
             │←─── connectTimeout ──→│            │←─ responseTimeout ─→│  │←idle─→│←idle─→│
                                                                          readTimeout fires
                                                                          if no bytes arrive
                                                                          within the window
```

| Timeout                  | What it measures                                   | Default | Exception                                              |
|--------------------------|----------------------------------------------------|---------|--------------------------------------------------------|
| `connectTimeout`         | TCP + TLS + DNS                                    | 5s      | `TransportException` (wraps `ConnectTimeoutException`) |
| `responseTimeout`        | Request sent → first byte received                 | 30s     | `RequestTimeoutException`                              |
| `readTimeout`            | Gap between consecutive bytes during body transfer | 10s     | `ReadTimeoutException`                                 |
| `poolAcquisitionTimeout` | Wait for a free connection pool slot               | 5s      | `PoolAcquisitionTimeoutException`                      |

`readTimeout` is implemented via Netty's `ReadTimeoutHandler`, injected per-request via `doOnRequest`
and removed via `doAfterResponseSuccess`. A safety net in `doOnDisconnected` removes the handler if
the connection is destroyed without completing normally.

---

## TLS architecture

```
HttpClientRegistry.registerTlsConfig(TlsConfig)
    │
    ▼
TlsSslContextFactory.build(TlsConfig)
    │
    ├── Load keystore (mTLS) → KeyManagerFactory
    │   └── AliasPinningKeyManager (if clientCertAlias specified)
    │
    ├── Load truststore → TrustManagerFactory
    │   └── Trust-all detection: reject empty getAcceptedIssuers()
    │
    ├── Set protocol versions (default: TLS 1.2 + 1.3 only)
    │
    └── Build Netty SslContext
        │
        ▼
    ConnectionProvider.secure(sslProvider)
        └── handlerConfigurator: disable hostname verification if configured
```

Security guards:

- `hostnameVerification=false` requires explicit `allowInsecureInProduction=true`, otherwise
  `InsecureConfigurationException` is thrown
- Trust-all `TrustManager` instances (empty `getAcceptedIssuers()`) are rejected with `TlsConfigurationException`
- TLS 1.0 and 1.1 are always disabled regardless of JVM defaults
- Missing keystore alias → fail-fast `TlsConfigurationException`

TLS profiles are registered once at startup. Certificate rotation requires application restart.

---

## Exception hierarchy

```
BedrockWireException (RuntimeException)
├── RequestTimeoutException         responseTimeout elapsed (first byte)
├── ReadTimeoutException            readTimeout elapsed (inter-byte idle)
├── PoolAcquisitionTimeoutException pool slot not available / pending queue full
├── ResponseSizeExceededException   body exceeds maxResponseBodySize
├── RedirectNotSupportedException   HTTP 3xx (except 304)
├── TransportException              connection refused, IO error, premature close
├── TlsConfigurationException       bad cert, wrong password, trust-all detected
├── RegistryCapacityException       maxClients exceeded
├── RegistryClosedException         operation after registry.close()
└── InsecureConfigurationException  hostnameVerification=false without opt-in
```

`execute()` only emits subtypes of `BedrockWireException`. The wrapping happens in
`HttpClientImpl.wrapException()`, which maps every JDK/Netty/Reactor exception to the
appropriate subtype.

`RedirectNotSupportedException` exposes `getStatusCode()` so callers (like `WireClientTransport`)
can propagate the 3xx status.

---

## Observability

### Logging — WireLogger

All logging goes through `WireLogger` using logger name `bedrock.wire.client`.
`@Slf4j` is NOT used on any other class in this module.

MDC fields are set in `try/finally` per method — caller's context is never disturbed.

| Event                              | Level | MDC fields                       |
|------------------------------------|-------|----------------------------------|
| Request sent                       | DEBUG | clientId, url                    |
| Request + headers/body             | TRACE | clientId, url                    |
| Response received                  | DEBUG | clientId, url, duration          |
| Response + headers/body            | TRACE | clientId, url, duration          |
| Transport error                    | DEBUG | clientId, url, duration          |
| Pool created                       | INFO  | transportTarget, clientId        |
| Pool closed                        | INFO  | transportTarget                  |
| Request timeout                    | WARN  | clientId, url, duration          |
| Read timeout                       | WARN  | clientId, url, duration          |
| Pool exhausted                     | WARN  | transportTarget, pendingRequests |
| TLS hostname verification disabled | WARN  | configName                       |
| maxClients exceeded                | WARN  | clientCount, maxClients          |
| close() while in-flight            | ERROR | inFlightCount                    |
| Registry closed on get()           | ERROR | clientId                         |

### Metrics — WireMetricsCollector SPI

Pluggable via constructor injection into `HttpClientRegistryImpl`. Default is `NoOpWireMetricsCollector`.

Two methods:

- `recordRequest(clientId, method, statusCode, duration, outcome)` — called per request
- `recordPoolState(target, activeConnections, pendingRequests)` — called by `PoolMetricsReporter` every 30s

`PoolMetricsReporter` runs a single daemon thread that samples Reactor Netty's `ConnectionPoolMetrics`
and forwards the state to the collector.

### Tracing — TraceHeaderPropagator SPI

Configured per `HttpClientConfig`. Called once per `execute()` invocation. Returned headers are merged
into the request between `defaultHeaders` (low priority) and per-request headers (high priority).

Default is `NoOpTraceHeaderPropagator`. Replace with an OpenTelemetry adapter for distributed tracing.

---

## Spring auto-configuration

Registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`.

### Always registered (core beans)

| Bean                    | Type                        | Condition                   |
|-------------------------|-----------------------------|-----------------------------|
| `httpClientRegistry`    | `HttpClientRegistryImpl`    | `@ConditionalOnMissingBean` |
| `wireMetricsCollector`  | `NoOpWireMetricsCollector`  | `@ConditionalOnMissingBean` |
| `traceHeaderPropagator` | `NoOpTraceHeaderPropagator` | `@ConditionalOnMissingBean` |

### Conditionally registered (auto-registration)

| Bean                       | Type                       | Condition                        |
|----------------------------|----------------------------|----------------------------------|
| `paramFileClientRegistrar` | `ParamFileClientRegistrar` | `ParamFileRegistrationCondition` |

`ParamFileRegistrationCondition` matches when:

1. No `MonitorTransport` bean exists (checked via `Class.forName()` + `getBeanNamesForType()`)
2. An application `Properties` bean exists (excluding Spring internals)

---

## Auto-registration and monitor back-off

### Two deployment modes

| Mode                        | Client creation                                 | Access                        |
|-----------------------------|-------------------------------------------------|-------------------------------|
| **Standalone** (no monitor) | `ParamFileClientRegistrar` from `.param` file   | `registry.get("clientId")`    |
| **With monitor**            | `WireClientTransport.init()` at monitor startup | `registry.get("serviceName")` |

### Back-off mechanism

When `bedrock-wire-monitor` is on the classpath and a `MonitorTransport` bean exists, auto-registration
is disabled. The monitor creates clients itself during `WireClientTransport.init()` — auto-registration
would cause duplicate registrations.

The check uses `Class.forName("cz.syntea.bedrock.wire.monitor.spi.MonitorTransport")` to avoid a
compile-time dependency from wire-client on wire-monitor. If the class is not on the classpath,
`ClassNotFoundException` is caught and auto-registration proceeds normally.

The check runs inside `ParamFileRegistrationCondition` (a custom Spring `Condition`) on the `@Bean`
method, not on the nested `@Configuration` class. This is important: `@ConditionalOnMissingBean`
on nested configuration classes can evaluate before user-config beans are visible, causing false
matches. A `Condition` using `BeanFactory.getBeanNamesForType()` directly always sees all registered
bean definitions.

### Test stub

For testing back-off without a compile-time dependency on wire-monitor, a stub interface exists at
`src/test/java/cz/syntea/bedrock/wire/monitor/spi/MonitorTransport.java`. The package path must
**exactly match** the FQN used in `Class.forName()`.

### ParamFileClientRegistrar flow

```
SmartInitializingSingleton.afterSingletonsInstantiated()
│
├── ParamFileClientConfigAdapter(properties)
│   ├── Parse wire.tls.* → Map<String, TlsProfileProperties>
│   └── Parse wire.client.* → Map<String, ClientProperties>
│
├── For each TLS profile:
│   └── registry.registerTlsConfig(tlsProps.toTlsConfig(name))
│
└── For each client:
    ├── Validate TLS profile reference (must exist in .param file)
    ├── ClientProperties.toHttpClientConfig(clientId, tracePropagator)
    └── registry.get(config)  ← creates and caches the client
```

---

## Bean lifecycle

```
Context refresh
│
├── Phase: Bean creation
│   ├── WireMetricsCollector (NoOp default)
│   ├── TraceHeaderPropagator (NoOp default)
│   └── HttpClientRegistry ← created with capacity config
│
├── Phase: SmartInitializingSingleton (standalone mode only)
│   └── ParamFileClientRegistrar.afterSingletonsInstantiated()
│       ├── Registers TLS profiles from wire.tls.*
│       └── Creates HttpClients from wire.client.*
│
├── ... application runs ...
│   └── registry.get("clientId") returns cached clients
│       HttpClient.execute(request) → Mono<HttpResponse>
│
└── Phase: Context close / @PreDestroy
    └── HttpClientRegistry.close()
        ├── PoolMetricsReporter.stop()
        ├── Log warning if in-flight requests exist
        ├── Dispose all ConnectionProviders
        └── Clear all caches
```

In **monitor mode**, the lifecycle is different — see the monitor's Architecture.md §13
"Bean interaction across modules".

---

## Design decisions and rationale

### Why ConcurrentHashMap.compute() for client creation?

The capacity check (`clients.size() >= maxClients`) and the actual insertion must be atomic.
Without `compute()`, two threads could both see size = 99 (limit = 100), both pass the check,
and create 101 clients. The segment lock inside `compute()` prevents this.

Config conflict detection (same `clientId`, different config) also lives inside `compute()` for
the same reason.

### Why no automatic retries?

The library is a low-level HTTP client. Retry policy depends on the caller's context:

- Idempotent GET → safe to retry
- Non-idempotent POST → dangerous to retry
- Monitor checks → retry on TIMEOUT/CONNECT_ERROR, not on RESPONSE_RECEIVED

Higher-level modules (like `bedrock-wire-monitor`'s `CheckRunner`) implement their own retry logic.

### Why DataBuffer streaming instead of bodyToMono(String)?

`bodyToMono(String.class)` buffers the entire response in memory before checking its size.
A 100MB response would allocate 100MB on the heap before being rejected. Chunk-by-chunk streaming
cancels the download as soon as the running total exceeds the limit — no oversized allocation.

The single-pass UTF-8 decode (after all chunks are collected) avoids the multi-byte character
boundary problem where a character like `ř` could be split across two DataBuffers.

### Why no path in baseUrl?

`baseUrl` defines the connection pool identity via `TransportTarget`. If paths were allowed,
`https://api.com/v1` and `https://api.com/v2` would either share a pool (ignoring the path)
or create separate pools (defeating the sharing model). Restricting to `scheme + host + port`
makes pool identity unambiguous.

### Why ReadTimeoutHandler per-request?

Reactor Netty's built-in `responseTimeout` measures time-to-first-byte. There is no built-in
inter-byte-idle timeout. `ReadTimeoutHandler` is added per-request (`doOnRequest`) and removed
after the body is consumed (`doAfterResponseSuccess`). A `doOnDisconnected` safety net handles
the case where the connection is destroyed without a success signal.

### Why registry.close() does NOT close individual HttpClients?

`HttpClient` instances are lightweight wrappers with no resources to release. The actual resources
(connection pools, SSL contexts) are owned by the registry. Closing the registry disposes all
`ConnectionProvider` instances, which closes all pooled connections.

### Why SmartInitializingSingleton for ParamFileClientRegistrar?

`SmartInitializingSingleton.afterSingletonsInstantiated()` runs after all beans are created but
before `SmartLifecycle.start()`. This means clients are available in the registry before the
monitor engine starts (important for the monitor mode where `WireClientTransport.init()` would
skip if clients were already registered).

It also runs after `@PostConstruct`, which means any custom `WireMetricsCollector` or
`TraceHeaderPropagator` beans are fully initialized before clients are created.

### Why String-based Class.forName for MonitorTransport back-off?

`bedrock-wire-client` has no dependency on `bedrock-wire-monitor`. Using
`@ConditionalOnMissingBean(MonitorTransport.class)`
would require the class on the compile classpath. `Class.forName()` with `ClassNotFoundException`
catch achieves the same effect without a compile-time dependency.

The check is inside a custom `Condition` (not `@ConditionalOnMissingBean` on a `@Configuration` class)
because `@ConditionalOnMissingBean` on nested configs evaluates during configuration class parsing,
which can happen before user-config beans are visible to the `BeanFactory`.