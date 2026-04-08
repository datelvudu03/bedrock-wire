# bedrock-wire

Modular project for HTTP communication and HTTP endpoint monitoring.
Two Spring Boot 3.x libraries, usable independently or together.

## Modules

### bedrock-wire-client

Reactive HTTP client built on Reactor Netty / WebFlux. Provides shared
connection pools per transport target, TLS profile management (including
mTLS), and a central `HttpClientRegistry`. Usable standalone as a building
block for any HTTP transport.

See [`bedrock-wire-client/Architecture-client.md`](bedrock-wire-client/Architecture-client.md)
and [`bedrock-wire-client/README.md`](bedrock-wire-client/README.md).

### bedrock-wire-monitor

Transport-agnostic HTTP monitoring engine. Runs periodic HTTP checks,
validates responses (status, body, regex, XPath, duration), and reports
results via `MonitorResultListener`. HTTP transport is pluggable via the
`MonitorTransport` SPI; the default `WireClientTransport` is built on
`bedrock-wire-client`.

See [`bedrock-wire-monitor/Architecture-monitor.md`](bedrock-wire-monitor/Architecture-monitor.md),
[`bedrock-wire-monitor/README.md`](bedrock-wire-monitor/README.md), and
the SPI specification [`bedrock-wire-monitor-v3-spi.md`](bedrock-wire-monitor-v3-spi.md).

## Requirements

- Java 21+
- Spring Boot 3.x
- Configuration via `.param` files (`--app.configFile=<path>`)