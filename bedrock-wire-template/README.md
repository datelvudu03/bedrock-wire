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