# bedrock-wire-template

Lightweight template rendering library for Java 21+. Loads templates from the filesystem,
merges parameters from multiple sources, and renders output via [Apache FreeMarker](https://freemarker.apache.org/).

Designed as a standalone utility module — usable independently or as the template layer for
`bedrock-wire-monitor`.

---

## What it does

- Renders FreeMarker templates (`.ftl`) from the filesystem
- Merges parameters from multiple sources with explicit priority order
- Exposes static Java methods inside templates (`statics["pkg.Class"].method()`)
- Provides built-in dynamic variables (`uuid`, `timestamp`)

## What it does NOT do

- No HTTP, no scheduling, no Spring auto-configuration beyond a single optional `@Bean`
- No custom expression language — standard FreeMarker syntax only

---

## Requirements

- Java 21+
- Spring Boot 3.x (optional — usable standalone)

---

## Maven dependency

```xml
<dependency>
    <groupId>cz.syntea.bedrock</groupId>
    <artifactId>syntea-bedrock-wire-template</artifactId>
    <version>${bedrock.wire.version}</version>
</dependency>
```

---

## Quick start

### 1. Template file (`/templates/health-check.ftl`)

```xml
<healthCheck>
    <clientId>${clientId}</clientId>
    <env>${deployment.env}</env>
    <region>${deployment.region!"eu-west"}</region>
    <requestId>${statics["java.util.UUID"].randomUUID()}</requestId>
    <timestamp>${statics["java.time.Instant"].now()}</timestamp>
</healthCheck>
```

- `${deployment.env}` — dot-notation traversal of a nested JSON object
- `${deployment.region!"eu-west"}` — fallback value if the variable is missing
- `statics[...]` — static Java method call

### 2. JSON parameter file (`health-params.json`)

```json
{
  "clientId": "monitor-prod",
  "deployment": {
    "env": "production",
    "region": "eu-central"
  }
}
```

The nested `deployment` object is accessible in the template via dot-notation (`${deployment.env}`).

> **Note:** JSON keys containing a dot (e.g. `"deployment.env": "production"`) carry the same
> information as a nested object, but are **not supported**. FreeMarker always interprets
> `${deployment.env}` as a nested object traversal, so a flat key with a dot cannot be accessed
> through standard template syntax. Use nested objects instead.

### 3. Render

Parameters are supplied as one or more `TemplateParamSource` instances. Sources are merged
in the order provided — **later sources override earlier ones**.

```java
TemplateRenderer renderer = TemplateRenderer.create();

String output = renderer.render(
    Path.of("/templates/health-check.ftl"),
    TemplateParams.of(
        TemplateParamSource.fromJson(Path.of("./health-params.json")),           // (1) lowest priority
        TemplateParamSource.fromSpring(environment, "monitor.check.health."),    // (2) optional prefix
        TemplateParamSource.fromMap(Map.of("clientId", "monitor-prod-override")) // (3) highest priority
    )
);
```

### 4. Rendered output

```xml
<healthCheck>
    <clientId>monitor-prod-override</clientId>  <!-- overridden by fromMap (priority 3) -->
    <env>production</env>                        <!-- from JSON nested object -->
    <region>eu-central</region>                  <!-- from JSON, fallback not used -->
    <requestId>550e8400-e29b-41d4-a716-446655440000</requestId>
    <timestamp>2026-05-06T10:23:41.123Z</timestamp>
</healthCheck>
```

---

## Parameter sources

| Source | Factory method | Description |
|---|---|---|
| Inline map | `TemplateParamSource.fromMap(Map)` | Explicit key-value pairs |
| JSON file | `TemplateParamSource.fromJson(Path)` | Nested JSON object; dot-notation accessible in templates |
| Spring Environment | `TemplateParamSource.fromSpring(Environment)` | All resolvable Spring properties |
| Spring Environment (prefix) | `TemplateParamSource.fromSpring(Environment, String prefix)` | Spring properties under the given prefix; prefix is stripped from keys |

Sources are always merged in the order passed to `TemplateParams.of(...)`. The last source wins on key collision.

**Prefix example:** `fromSpring(env, "monitor.check.health.")` passes only properties under that prefix to the template, with the prefix stripped — `monitor.check.health.clientId` → `${clientId}`.

---

## Built-in template variables

Available in every template without configuration.

| Variable | Type | Description |
|---|---|---|
| `statics["pkg.ClassName"]` | class reference | Access to static methods of any public class |

Dynamic values (new per render call):

| Expression | Description |
|---|---|
| `${statics["java.util.UUID"].randomUUID()}` | Random UUID v4 |
| `${statics["java.time.Instant"].now()}` | Current UTC instant (ISO 8601) |

Caller-supplied parameters override nothing — `statics` is always available.

---

## FreeMarker syntax reference

| Syntax | Description |
|---|---|
| `${variable}` | Variable substitution |
| `${variable!"default"}` | Substitution with fallback if missing |
| `${statics["pkg.Cls"].method()}` | Static method call |
| `<#if condition>...</#if>` | Conditional block |
| `<#list items as item>...</#list>` | Iteration |

Full reference: [FreeMarker Manual](https://freemarker.apache.org/docs/index.html)

---

## Spring integration

When Spring Boot is on the classpath, a `TemplateRenderer` bean is registered automatically.
No additional configuration required.

```java
@Service
@RequiredArgsConstructor
public class MyService {

    private final TemplateRenderer templateRenderer;

    public String buildBody(Path templateFile, Path paramFile) {
        return templateRenderer.render(
            templateFile,
            TemplateParams.of(TemplateParamSource.fromJson(paramFile))
        );
    }
}
```

To override the default bean:

```java
@Bean
public TemplateRenderer templateRenderer() {
    return TemplateRenderer.builder()
        .exposeStaticMethods(true)   // default: true
        .encoding(StandardCharsets.UTF_8)
        .build();
}
```

---

## Security note

`exposeStaticMethods(true)` allows templates to call **any** public static method on the JVM classpath.
This is intentional for internal use where templates are authored by the development/operations team.

**Do not enable this if templates are supplied by untrusted external parties.**

---

## Error handling

| Situation | Exception |
|---|---|
| Template file not found | `TemplateNotFoundException` |
| JSON parameter file not found or invalid | `TemplateParamException` |
| FreeMarker render error (undefined variable, syntax) | `TemplateRenderException` |

All exceptions are unchecked and extend `BedrockWireTemplateException`.

---

## Known limitations

| # | Description |
|---|---|
| 1 | JSON keys containing a dot (e.g. `"deployment.env"`) are not supported. They carry the same information as a nested object, but FreeMarker's dot-notation always means nested object traversal — a flat dotted key is unreachable via standard template syntax. Use nested objects instead. |
| 2 | `fromSpring(env)` without a prefix exposes all Spring properties, including internal ones (datasource passwords, etc.). Always specify a prefix in practice. |
| 3 | Template hot-reload is polling-based — FreeMarker checks for file changes at a configurable interval (default: 5s). |
