# bedrock-wire-monitor

Periodic HTTP monitoring library for Java 21+. Runs health checks against configured services at fixed intervals,
validates responses, and reports results through a listener API.

Built as a **Spring Boot 3.x Starter** with transport-agnostic design — the HTTP layer is pluggable via the
`MonitorTransport` SPI. The default transport uses `bedrock-wire-client` (Reactor Netty) with shared connection pools.

---

## Table of contents

1. [Requirements](#requirements)
2. [Getting started](#getting-started)
3. [Configuration](#configuration)
4. [Architecture overview](#architecture-overview)
5. [Validators](#validators)
6. [Templates](#templates)
7. [Listeners](#listeners)
8. [Custom transport](#custom-transport)
9. [Custom validators](#custom-validators)
10. [Disabling the monitor](#disabling-the-monitor)
11. [Graceful shutdown](#graceful-shutdown)
12. [Testing](#testing)
13. [Example application](#example-application)

---

## Requirements

- Java 21+ (virtual threads for execution model)
- Spring Boot 3.2+
- `bedrock-wire-client` 1.0+ (only for the default `WireClientTransport`; optional if you provide a custom transport)

---

## Getting started

### 1. Add Maven dependency

```xml

<dependency>
    <groupId>cz.syntea.bedrock</groupId>
    <artifactId>syntea-bedrock-wire-monitor</artifactId>
    <version>1.0.0</version>
</dependency>
```

The default transport pulls in `bedrock-wire-client` transitively. If you provide your own `MonitorTransport`,
declare the dependency with `<exclusions>` to drop the wire-client.

### 2. Configure Spring properties

In your `application.properties` (or `application.yml`):

```properties
# Path to the .param file containing monitor.* configuration.
# Resolved from --app.configFile=<path> on the command line.
bedrock.wire.monitor.config-file=${app.configFile}
# Optional: shared registry capacity (from bedrock-wire-client)
bedrock.wire.client.max-clients=100
```

### 3. Create the .param configuration file

The monitor reads its runtime configuration from a standalone `.properties` file (the `.param` file). This file uses the
`monitor.*` namespace.

**Minimal example** (`src/cfg/monitor.param`):

```properties
# === Service ===
monitor.service.payments.url=https://payments.example.com
# === Check ===
monitor.check.paymentsHealth.service=payments
monitor.check.paymentsHealth.interval=30s
monitor.check.paymentsHealth.method=GET
monitor.check.paymentsHealth.path=/api/health
monitor.check.paymentsHealth.validation.validators=httpStatus
monitor.check.paymentsHealth.validation.httpStatus=200
```

### 4. Run with the config file path

```bash
java -jar myapp.jar --app.configFile=src/cfg/monitor.param
```

The monitor auto-starts on Spring context refresh. No additional code required.

### 5. (Optional) Register a listener

```java

@Component
public class LoggingMonitorListener implements MonitorResultListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingMonitorListener.class);

    @Override
    public void onResult(MonitorExecutionResult result) {
        log.info("Check '{}' → {} ({}ms, {} attempts)",
                result.getCheckName(),
                result.getStatus(),
                result.getExecutionDuration().toMillis(),
                result.getAttempts());
    }
}
```

---

## Configuration

### Two-file architecture

The monitor uses two configuration sources:

```
application.properties          src/cfg/xxxx.param
(Spring Boot)                   (standalone .properties)
┌─────────────────────┐         ┌──────────────────────────────┐
│ bedrock.wire.monitor │         │ monitor.default.*            │
│   .config-file ──────┼────────▶│ monitor.tls.<profile>.*     │
│   .enabled           │         │ monitor.service.<name>.*     │
│                      │         │ monitor.check.<name>.*       │
│ bedrock.wire.client  │         │ monitor.executor.*           │
│   .max-clients       │         └──────────────────────────────┘
└─────────────────────┘
```

**Why two files?** Spring-level settings (starter enablement, file path, registry capacity) belong in
`application.properties` where Spring can resolve placeholders and profiles.
Monitor runtime config (services, checks, TLS) is typically managed by operations teams via a separate file
that can be updated independently of the application deployment.

### .param file reference

#### Global defaults (`monitor.default.*`)

Fallback values used when a parameter is not set at the check or service level.

| Parameter                               | Type     | Description                           |
|-----------------------------------------|----------|---------------------------------------|
| `monitor.default.interval`              | Duration | Default polling interval              |
| `monitor.default.retry.count`           | int      | Default retry attempts (default: `0`) |
| `monitor.default.retry.delay`           | Duration | Default retry delay (default: `1s`)   |
| `monitor.default.validation.validators` | String   | Comma-separated validator aliases     |
| `monitor.default.method`                | String   | Default HTTP method (default: `POST`) |
| `monitor.default.header.<name>`         | String   | Default HTTP header                   |

#### Service (`monitor.service.<name>.*`)

| Parameter         | Type     | Required | Description                                                |
|-------------------|----------|----------|------------------------------------------------------------|
| `url`             | URI      | yes      | Base URL (scheme + host + port only for default transport) |
| `interval`        | Duration | no       | Overrides default interval for all checks on this service  |
| `header.<name>`   | String   | no       | Service-level HTTP header                                  |
| `transport.<key>` | String   | no       | Passed to the transport implementation (see below)         |

**Default transport properties** (`monitor.service.<name>.transport.*`):

| Key                   | Type     | Default   | Description             |
|-----------------------|----------|-----------|-------------------------|
| `responseTimeout`     | Duration | `5s`      | Timeout for first byte  |
| `connectionTimeout`   | Duration | `5s`      | TCP + TLS + DNS         |
| `readTimeout`         | Duration | `10s`     | Inter-byte idle timeout |
| `maxResponseBodySize` | int      | `1048576` | Max body in bytes       |
| `maxConnections`      | int      | `50`      | Max TCP per target      |
| `tlsProfile`          | String   | `null`    | TLS profile name        |

#### Check (`monitor.check.<name>.*`)

| Parameter               | Type     | Required | Description                                            |
|-------------------------|----------|----------|--------------------------------------------------------|
| `service`               | String   | yes      | References a service name                              |
| `interval`              | Duration | yes*     | Polling interval (resolved: check → service → default) |
| `method`                | String   | no       | HTTP method (default: `POST`, case-insensitive)        |
| `path`                  | String   | no       | URL path appended to service base URL                  |
| `query`                 | String   | no       | Query string (without `?`)                             |
| `templateFile`          | String   | no       | Path to request body template                          |
| `param.<name>`          | String   | no       | Template substitution parameter                        |
| `retry.count`           | int      | no       | Retry attempts (default: `0`)                          |
| `retry.delay`           | Duration | no       | Retry delay (default: `1s`)                            |
| `header.<name>`         | String   | no       | Check-level HTTP header                                |
| `validation.validators` | String   | no       | Comma-separated validator aliases                      |
| `validation.<alias>`    | String   | no       | Validator parameter                                    |

#### TLS profiles (`monitor.tls.<profileName>.*`)

| Parameter                   | Type    | Description                                                |
|-----------------------------|---------|------------------------------------------------------------|
| `clientCert`                | Path    | Client certificate keystore (mTLS)                         |
| `clientCertPassword`        | String  | Keystore password                                          |
| `clientCertType`            | String  | `PKCS12` or `JKS`                                          |
| `clientCertAlias`           | String  | Alias in keystore                                          |
| `trustStore`                | Path    | Custom trust store (null = JVM default)                    |
| `trustStorePassword`        | String  | Trust store password                                       |
| `trustStoreType`            | String  | `PKCS12` or `JKS`                                          |
| `hostnameVerification`      | boolean | Default: `true`                                            |
| `allowInsecureInProduction` | boolean | Default: `false`; required if `hostnameVerification=false` |

#### Shutdown

| Parameter                          | Type     | Default | Description                       |
|------------------------------------|----------|---------|-----------------------------------|
| `monitor.executor.shutdownTimeout` | Duration | `30s`   | Grace period for in-flight checks |

### Duration format

All Duration values require an explicit time unit. Bare numbers are rejected.

```
500ms    → 500 milliseconds
5s       → 5 seconds
2m       → 2 minutes
1h       → 1 hour
30       → ERROR: missing unit
```

### Three-level lookup

For monitor-owned parameters (interval, retry, method, headers, validators), values are resolved in this order:

1. `monitor.check.<checkName>.<param>` — check level
2. `monitor.service.<serviceName>.<param>` — service level (only for `interval` and `header.*`)
3. `monitor.default.<param>` — global fallback

First value found wins. Headers are **merged** across all three levels (default → service → check),
with later levels overriding earlier ones (case-insensitive key comparison).

### Reserved names

The name `default` cannot be used as a service or check name. It is reserved for the global fallback namespace.

---

## Architecture overview

```
┌────────────────────────────────────────────────────────┐
│                     Monitor layer                       │
│                                                         │
│  MonitorEngine          CheckRunner        Validators   │
│  (SmartLifecycle)       (retry, status     (httpStatus,  │
│  scheduler,              mapping, listener  contains,    │
│  virtual threads)        notification)      regex, ...)  │
│                                                         │
│  PropertiesFile         TemplateProcessor               │
│  ConfigProvider         ($(param), $(uuid))             │
└──────────────────────────┬──────────────────────────────┘
                           │
             ══════════════╪══════════════
                MonitorTransport SPI
             ══════════════╪══════════════
                           │
            ┌──────────────┴──────────────┐
     WireClientTransport          Custom Transport
     (default, bedrock-           (user-provided)
      wire-client, shared
      HttpClientRegistry)
```

The monitor layer is fully transport-agnostic. It communicates with the HTTP layer exclusively through three methods:
`init()`, `execute()`, and `close()`.

---

## Validators

Validators run against the HTTP response (only when `transportStatus == RESPONSE_RECEIVED`) in the order specified by
`validation.validators`.

| Alias         | Parameter     | Behavior                                                                      | Verdict |
|---------------|---------------|-------------------------------------------------------------------------------|---------|
| `httpStatus`  | `httpStatus`  | Matches status code against spec (`200`, `200,204`, `200-299`, `200-204,301`) | FAIL    |
| `contains`    | `contains`    | Case-sensitive substring check in response body                               | FAIL    |
| `regex`       | `regex`       | `Pattern.find()` against response body (compiled patterns are cached)         | FAIL    |
| `maxDuration` | `maxDuration` | Checks transport duration against threshold                                   | WARN    |
| `xpath`       | `xpath`       | XPath 1.0 against XML response body (XXE-protected)                           | FAIL    |

**Aggregate verdict:** at least one FAIL → `FAIL`; at least one WARN (no FAIL) → `WARN`; all pass → `PASS`.

**Status mapping:**

| TransportStatus     | Verdict | MonitorStatus |
|---------------------|---------|---------------|
| `RESPONSE_RECEIVED` | PASS    | `UP`          |
| `RESPONSE_RECEIVED` | WARN    | `WARN`        |
| `RESPONSE_RECEIVED` | FAIL    | `DOWN`        |
| `TIMEOUT`           | —       | `DOWN`        |
| `CONNECT_ERROR`     | —       | `DOWN`        |
| `IO_ERROR`          | —       | `DOWN`        |
| `POOL_EXHAUSTED`    | —       | `ERROR`       |

---

## Templates

If a check defines `templateFile`, the body is loaded from that file (UTF-8) and variables are substituted:

**Static parameters** — `$(name)` is replaced with the value of `monitor.check.<n>.param.name`.

**Dynamic variables:**

- `$(uuid)` — random UUID v4, unique per request
- `$(timestamp)` — current UTC time in ISO 8601 format

Example template (`health-check.xml`):

```xml

<healthCheck>
    <clientId>$(clientId)</clientId>
    <requestId>$(uuid)</requestId>
    <timestamp>$(timestamp)</timestamp>
</healthCheck>
```

With config:

```properties
monitor.check.health.templateFile=/templates/health-check.xml
monitor.check.health.param.clientId=monitor-prod
```

Invalid UTF-8 in the template file causes the check run to fail with `MonitorStatus.ERROR`.

---

## Listeners

Implement `MonitorResultListener` and register it as a Spring `@Bean` or `@Component`. All listeners are auto-collected
by the auto-configuration.

```java

@FunctionalInterface
public interface MonitorResultListener {
    void onResult(MonitorExecutionResult result);
}
```

**`MonitorExecutionResult` fields:**

| Field               | Type          | Description                                                 |
|---------------------|---------------|-------------------------------------------------------------|
| `checkName`         | String        | Name of the check                                           |
| `serviceName`       | String        | Target service name                                         |
| `startedAt`         | Instant       | Check run start                                             |
| `finishedAt`        | Instant       | Check run end                                               |
| `executionDuration` | Duration      | Wall-clock time (includes retry delays)                     |
| `attempts`          | int           | Total HTTP attempts (≥ 1)                                   |
| `requestId`         | String        | UUID v4 for this run                                        |
| `status`            | MonitorStatus | `UP`, `DOWN`, `WARN`, `ERROR`                               |
| `message`           | String        | First FAIL message, else first WARN, else null              |
| `transport`         | MonitorResult | Raw transport result (HTTP status, body, headers, duration) |

Listener exceptions are logged but never affect check run status or other listeners.

---

## Custom transport

To replace the default `WireClientTransport`, implement `MonitorTransport` and register it as a `@Bean`:

```java

@Bean
public MonitorTransport myTransport() {
    return new MyOkHttpTransport();
}
```

The auto-configuration uses `@ConditionalOnMissingBean(MonitorTransport.class)`, so your bean takes priority.
The `bedrock-wire-client` dependency becomes unnecessary and can be excluded.

**Contract:**

- `init(List<ServiceConfig>)` — called once at startup; fail fast on bad config
- `execute(MonitorRequest)` — called from virtual threads; **must never throw**; always returns `MonitorResult`
- `close(Duration)` — called once at shutdown; idempotent

---

## Custom validators

Implement `Validator` and register as a Spring `@Bean`:

```java

@Component
public class JsonPathValidator implements Validator {

    @Override
    public String alias() {
        return "jsonPath";
    }

    @Override
    public ValidationResult validate(MonitorResult result, Map<String, String> params) {
        String expression = params.get("jsonPath");
        // ... evaluate expression against result.getResponseBody() ...
        return ValidationResult.pass();
    }
}
```

Then use in config:

```properties
monitor.check.myCheck.validation.validators=httpStatus,jsonPath
monitor.check.myCheck.validation.jsonPath=$.status == 'ok'
```

Custom validators are merged with built-ins automatically. If the alias collides with a built-in, the custom one wins.

---

## Disabling the monitor

```properties
bedrock.wire.monitor.enabled=false
```

No beans are registered, no config file is loaded, no scheduler starts.

---

## Graceful shutdown

When the Spring context closes:

1. `MonitorEngine.stop()` fires (SmartLifecycle phase `Integer.MAX_VALUE - 100` — stops early)
2. Scheduler stops — no new check triggers
3. In-flight checks get `shutdownTimeout` to complete
4. After timeout, remaining threads are interrupted
5. `transport.close()` is called
6. Wire-client `HttpClientRegistry.close()` fires later via `@PreDestroy` (separate lifecycle)

---

## Testing

### Unit testing with StubTransport

For testing monitor behavior without network calls:

```java
StubTransport transport = new StubTransport();
transport.

stub("myService",StubTransport.response(200, "<status>OK</status>"));

CheckRunner runner = new CheckRunner(transport, new ValidatorRegistry(),
        new TemplateProcessor(), List.of(result -> { /* assert */ }));

MonitorExecutionResult result = runner.execute(checkConfig, serviceConfig);

assertEquals(MonitorStatus.UP, result.getStatus());
```

### Spring Boot test

```java

@SpringBootTest
class MonitorIntegrationTest {

    @Autowired
    private MonitorEngine engine;

    @Test
    void shouldStartAndScheduleChecks() {
        assertTrue(engine.isRunning());
    }
}
```

### ApplicationContextRunner (auto-config tests)

```java
new ApplicationContextRunner()
    .

withConfiguration(AutoConfigurations.of(
                          WireClientTransportAutoConfiguration.class,
                  BedrockWireMonitorAutoConfiguration .class))
        .

withPropertyValues("bedrock.wire.monitor.config-file=test.properties")
    .

run(context ->{

assertThat(context).

hasSingleBean(ValidatorRegistry .class);
    });
```

---

## Example application

See `src/example/` for a complete working example, or refer to
the [example section in ARCHITECTURE.md](ARCHITECTURE.md#example-wiring).

---

## Full configuration example

```properties
# ── Global defaults ──
monitor.default.interval=30s
monitor.default.retry.count=1
monitor.default.retry.delay=2s
monitor.default.validation.validators=httpStatus
monitor.default.header.Accept=application/xml
monitor.default.header.X-Client-Id=bedrock-monitor
# ── TLS ──
monitor.tls.payments-tls.clientCert=/certs/client.p12
monitor.tls.payments-tls.clientCertPassword=secret
monitor.tls.payments-tls.clientCertType=PKCS12
monitor.tls.payments-tls.trustStore=/certs/truststore.p12
monitor.tls.payments-tls.trustStorePassword=trustsecret
monitor.tls.payments-tls.trustStoreType=PKCS12
# ── Services ──
monitor.service.payments.url=https://payments.example.com
monitor.service.payments.header.Content-Type=application/xml
monitor.service.payments.transport.responseTimeout=10s
monitor.service.payments.transport.connectionTimeout=3s
monitor.service.payments.transport.tlsProfile=payments-tls
monitor.service.internal.url=https://internal.example.com
monitor.service.internal.transport.responseTimeout=3s
monitor.service.internal.transport.connectionTimeout=3s
# ── Checks ──
monitor.check.paymentsHealth.service=payments
monitor.check.paymentsHealth.method=POST
monitor.check.paymentsHealth.path=/api/health
monitor.check.paymentsHealth.templateFile=/templates/health-check.xml
monitor.check.paymentsHealth.param.clientId=monitor-prod
monitor.check.paymentsHealth.validation.validators=httpStatus,contains
monitor.check.paymentsHealth.validation.httpStatus=200
monitor.check.paymentsHealth.validation.contains=<status>OK</status>
monitor.check.internalPing.service=internal
monitor.check.internalPing.method=GET
monitor.check.internalPing.path=/ping
monitor.check.internalPing.interval=10s
monitor.check.internalPing.retry.count=0
monitor.check.internalPing.validation.validators=httpStatus
monitor.check.internalPing.validation.httpStatus=200
# ── Shutdown ──
monitor.executor.shutdownTimeout=30s
```