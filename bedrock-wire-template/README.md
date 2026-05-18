# bedrock-wire-template

HTTP body templating for `bedrock-wire-monitor` and standalone use.
Built on Apache FreeMarker — full native syntax, no wrapper layer.

## At a glance

```java
String body = renderer.render(
        Path.of("/templates/B1WS-request.xml"),
        Params.of(Map.of("userId", "hejny", "mode", "PROD"))
);
```

## Requirements
- Java 21+
- Spring Boot 3.x — optional

## Maven
```xml

cz.syntea.bedrock
        syntea-bedrock-wire-template
        ${bedrock.wire.version}

```

## Your first SOAP template

Template — `B1WS-2023-01-request.xml`:
```xml
```

Render:
```java
TemplateRenderer renderer = TemplateRenderer.create();
String body = renderer.render(
        Path.of("/templates/B1WS-2023-01-request.xml"),
        Params.of(Map.of("mode", "PROD", "userId", "hejny"))
);
```

## Parameter sources

| Source                           | Use case                                  |
|----------------------------------|-------------------------------------------|
| `Params.of(map)`                 | Inline, programmatic params               |
| `Params.fromJson(path)`          | Static config in a JSON file              |
| `Params.fromSpring(env, prefix)` | Spring properties under a required prefix |

Combine — last source wins on key collision:

```java
Params params = Params.combined(
        Params.fromJson(Path.of("./defaults.json")),
        Params.fromSpring(env, "monitor.check.b1wsPing."),
        Params.of(Map.of("mode", "DEV"))
);
```

## Scanning a `.param` graph — `TemplateVarScanner`

`TemplateVarScanner` turns a configuration graph (any `Properties` — typically a
`PropertiesCfg` with `${...}` chains already resolved) into a flat model map of
template-visible variables. Every non-blank key is exposed verbatim **except**
keys under `monitor.*` and `bedrock.wire.monitor.*`, which are framework
configuration and never reach the template.

Dotted keys (`RUN.MODE`, `USER.TYPE`) are exposed as **flat** entries — the map
is not nested. Read them from a template with an escaped dot
(FreeMarker ≥ 2.3.22):

```xml

<ws:Request Mode="${RUN\.MODE}" Type="${USER\.TYPE}" Caller="${_MODE}"/>
```

The bracket form `${.vars['RUN.MODE']}` is equivalent.

Scan once at startup, reuse across every render:

```java
PropertiesCfg cfg = PropertiesCfg.load(Path.of("app.param"));
Map<String, Object> vars = TemplateVarScanner.scan(cfg);     // scan ONCE
TemplateRenderer renderer = TemplateRenderer.create();

String reqA = renderer.render(Path.of("ckp-request.xml"), Params.of(vars));
String reqB = renderer.render(Path.of("ping.xml"), Params.of(vars));
```

Layer it under per-template params with `Params.combined` (scanned vars lowest
precedence):

```java
String req = renderer.render(
        Path.of("ckp-request.xml"),
        Params.combined(Params.of(vars), Params.of(Map.of("clientId", "monitor-prod"))));
```

`bedrock-wire-monitor` uses this internally — every key in your `.param` files
(outside the framework namespaces) is auto-exposed to monitor templates with no
declaration. See the monitor spec §2.9.4.

## Common SOAP/XML patterns

| Pattern            | FreeMarker                                                           |
|--------------------|----------------------------------------------------------------------|
| UUID               | `${statics['java.util.UUID'].randomUUID()}`                          |
| Timestamp ISO 8601 | `${.now?iso_utc_ms}`                                                 |
| Default value      | `${mode!'DEV'}`                                                      |
| Safe nested        | `${(a.b.c)!'x'}`                                                     |
| Conditional        | `<#if mode == 'PROD'>...</#if>`                                      |
| Iteration          | `<#list items as i>...</#list>`                                      |
| XML escape         | `${userInput?xml}`                                                   |
| Base64             | `${statics['java.util.Base64'].encoder.encodeToString(token?bytes)}` |
| Uppercase          | `${code?upper_case}`                                                 |
| Format number      | `${amount?string('0.00')}`                                           |

Full reference: [FreeMarker manual](https://freemarker.apache.org/docs/index.html).

## `statics` security

Enabled by default; templates are assumed authored by trusted developers.
Disable for untrusted templates:

```java
TemplateRenderer.builder().

exposeStaticMethods(false).

build();
```

## Spring integration

Auto-configured `TemplateRenderer` bean. Inject directly. Define your own
bean to override — auto-config backs off via `@ConditionalOnMissingBean`.

Properties:

| Property                                      | Type     | Default |
|-----------------------------------------------|----------|---------|
| `bedrock.wire.template.expose-static-methods` | boolean  | `true`  |
| `bedrock.wire.template.encoding`              | Charset  | `UTF-8` |
| `bedrock.wire.template.template-update-delay` | Duration | `0s`    |

## Monitor integration

```properties
monitor.check.b1wsPing.templateFile=/templates/B1WS-request.xml
monitor.check.b1wsPing.param.userId=hejny
monitor.check.b1wsPing.param.mode=PROD
```

All `monitor.check.<n>.param.*` keys + FreeMarker built-ins (`.now`, `statics`)
available at render time.

## Troubleshooting

- **Variable missing** — error names the symbol + lists available params
- **`statics` disabled** — re-enable or pass value as parameter
- **File not found** — paths resolve from JVM working dir; use absolute
- **Invalid JSON** — `jq . file.json` to validate
- **Nested dotted JSON keys** — use nested objects, not `"a.b": "x"`

## Exception hierarchy

BedrockWireTemplateException     (base, RuntimeException)
├── TemplateNotFoundException
├── TemplateParamException
└── TemplateRenderException      (preserves FreeMarker message + file/line + available params)

## Swapping the engine (advanced)

```java
TemplateRenderer.builder().

engine(new MyEngine()).

build();
```

`TemplateEngine` SPI: `String alias()`, `String render(String, Map<String, Object>)`.