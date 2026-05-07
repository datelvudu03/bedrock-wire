# Architecture guide — bedrock-wire-template

Deep technical reference for maintainers and contributors. For usage documentation, see [README.md](README.md).

---

## Table of contents

1. [Design principles](#design-principles)
2. [Package structure](#package-structure)
3. [Class dependency graph](#class-dependency-graph)
4. [Key concepts](#key-concepts)
5. [Render pipeline](#render-pipeline)
6. [FreeMarker integration](#freemarker-integration)
7. [Parameter source semantics](#parameter-source-semantics)
8. [Exception hierarchy](#exception-hierarchy)
9. [Spring auto-configuration](#spring-auto-configuration)
10. [Bean lifecycle](#bean-lifecycle)
11. [Design decisions and rationale](#design-decisions-and-rationale)

---

## Design principles

**Pure FreeMarker, no wrapper.**
Templates use native FreeMarker syntax exclusively — `${var}`, `${var!'default'}`, `${.now?iso_utc}`,
`${statics['java.util.UUID'].randomUUID()}`. The library does NOT introduce a curated function map,
custom expression language, or `$(...)`-style placeholders. Authors get the full FreeMarker manual
and unmodified semantics; the library only wires FreeMarker into a Spring-friendly facade.

**Sealed parameter sources.**
`Params` is a sealed interface with exactly four permitted implementations: `MapParams`, `JsonParams`,
`SpringParams`, `CombinedParams`. Callers cannot extend the type hierarchy. New source types require
a library change — this prevents ad-hoc subclasses with surprising semantics (e.g. lazy resolution that
fires on every render).

**Required prefix on Spring binding.**
`Params.fromSpring(env, prefix)` requires a non-blank prefix. There is no overload that exposes the
entire environment. This is a security choice: without a prefix, an unrelated property
(`spring.datasource.password`) would be reachable via `${spring.datasource.password}` in any template.

**Pluggable engine SPI, narrow surface.**
`TemplateEngine` exposes two methods: `alias()` and `render(String, Map)`. File reading, encoding,
and FreeMarker-specific configuration live inside `FreeMarkerEngine` and never leak into the SPI.
Custom engines need only handle string-in / string-out.

**Strict UTF-8 by default.**
The engine's `TemplateLoader` configures a `CharsetDecoder` with `CodingErrorAction.REPORT` for both
malformed and unmappable input. Invalid bytes throw `IOException` rather than being silently replaced
with `?` (the default `InputStreamReader` behavior).

**Single shared `Configuration` instance.**
FreeMarker's `Configuration` is thread-safe after the setup phase. `FreeMarkerEngine` constructs one
`Configuration` per instance and reuses it across all renders — including hot-reload via the file cache.

---

## Package structure

cz.syntea.bedrock.wire.template/
├── (root)             TemplateRenderer, Params (sealed) — public entry points
├── source/ MapParams, JsonParams, SpringParams, CombinedParams — sealed permits
├── engine/ TemplateEngine (SPI), FreeMarkerEngine, FreeMarkerEngineConfig,
│ AbsolutePathTemplateLoader (package-private)
├── exception/ BedrockWireTemplateException + 3 subtypes
└── autoconfigure/ TemplateAutoConfiguration, TemplateProperties

Dependency flow: `autoconfigure → (root) → {source, engine, exception}`. The `engine` package
depends on FreeMarker; the rest of the module is engine-agnostic. The `autoconfigure` package
depends on Spring Boot (optional at runtime via `<optional>true</optional>`).

---

## Class dependency graph

### Full module dependency map

┌─────────────────────────────────────────────────────────────────┐
│ autoconfigure  (Spring Boot, optional)                           │
│ │
│ TemplateAutoConfiguration │
│ │ creates │
│ ▼ │
│ TemplateRenderer ◀── reads ── TemplateProperties │
└────────┬─────────────────────────────────────────────────────────┘
│ uses
▼
┌─────────────────────────────────────────────────────────────────┐
│ (root)                                                            │
│ │
│ TemplateRenderer ──delegates──▶ TemplateEngine │
│ │ │
│ │ resolves │
│ ▼ │
│ «sealed» Params ◀─ permits ─ MapParams, JsonParams, │
│ SpringParams, CombinedParams │
└────────┬─────────────────────────────────────────────────────────┘
│ uses
▼
┌─────────────────────────────────────────────────────────────────┐
│ engine │
│ │
│ «interface» TemplateEngine │
│ ▲ │
│ │ implements │
│ FreeMarkerEngine ──configured by──▶ FreeMarkerEngineConfig │
│ │ │
│ │ uses │
│ ▼ │
│ AbsolutePathTemplateLoader  (FreeMarker TemplateLoader)         │
│ │ │
│ ▼ │
│ freemarker.template.Configuration  (external)                   │
└─────────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────────┐
│ exception │
│ │
│ «abstract» BedrockWireTemplateException │
│ ▲ │
│ │ extends │
│ TemplateNotFoundException │
│ TemplateParamException │
│ TemplateRenderException │
└─────────────────────────────────────────────────────────────────┘
┌─────────────────────────────────────────────────────────────────┐
│ source │
│ │
│ MapParams ──holds──▶ Map<String, Object>                 │
│ JsonParams ──reads──▶ Path  (lazy, cached)                │
│ SpringParams ──reads──▶ Environment  (prefix required)      │
│ CombinedParams ──merges──▶ Params[]  (deep merge, last wins)   │
└─────────────────────────────────────────────────────────────────┘

### Layered view (simplified)

┌─────────────────────────────────────────────────────────────┐
│ autoconfigure/ │
│ TemplateAutoConfiguration ─── TemplateProperties │
│ ↓ creates bean │
├─────────────────────────────────────────────────────────────┤
│  (root)                                                      │
│ TemplateRenderer ─── Params (sealed)                        │
│ ↓ delegates ↓ provides Map<String, Object>        │
├─────────────────────────────────────────────────────────────┤
│ engine/ source/ │
│ TemplateEngine (SPI)     MapParams │
│ FreeMarkerEngine JsonParams │
│ FreeMarkerEngineConfig SpringParams │
│ AbsolutePathTemplateLoader CombinedParams │
├─────────────────────────────────────────────────────────────┤
│ exception/ │
│ BedrockWireTemplateException + 3 subtypes │
└─────────────────────────────────────────────────────────────┘

---

## Key concepts

### TemplateRenderer — the entry point

Immutable, thread-safe, intended to be a singleton. Two render methods:

- `render(Path, Params)` — file-based. The path is normalized to absolute, existence-checked, then
  passed to the engine. When the engine is the default `FreeMarkerEngine`, the file is loaded through
  FreeMarker's caching pipeline (`templateUpdateDelay` honored). For custom engines, the renderer reads
  the file as a String (with strict charset decoding) and delegates to `engine.render(String, Map)`.
- `render(String, Params)` — inline. The template string is passed straight to the engine; no file IO,
  no caching.

The renderer always calls `params.asMap()` exactly once per render and forwards the resolved map to
the engine. Sources that do their own caching (`JsonParams`) optimize subsequent calls.

### Params — sealed parameter source

```java
public sealed interface Params permits MapParams, JsonParams, SpringParams, CombinedParams {
    Map<String, Object> asMap();
}
```

Static factories on `Params` are the only sanctioned construction path:

| Factory                          | Permits subtype  | Resolution                     |
|----------------------------------|------------------|--------------------------------|
| `Params.of(map)`                 | `MapParams`      | Defensive copy at construction |
| `Params.fromJson(path)`          | `JsonParams`     | Lazy file read, cached         |
| `Params.fromSpring(env, prefix)` | `SpringParams`   | Eager iteration on `asMap()`   |
| `Params.combined(sources...)`    | `CombinedParams` | Deep merge on `asMap()`        |

`asMap()` returns top-level keys directly addressable in templates. Nested `Map` values are
traversed via FreeMarker dot-notation (`${a.b.c}`).

### TemplateEngine — pluggable SPI

```java
public interface TemplateEngine {
    String alias();

    String render(String template, Map<String, Object> params);
}
```

The SPI is intentionally narrow — the engine receives a fully-resolved parameter map and a template
string. File loading, encoding, and engine-specific configuration are NOT part of the SPI. Custom
engines integrate into the renderer by replacing the default via
`TemplateRenderer.builder().engine(custom).build()`.

When a custom engine is used, the renderer's file-render path reads the file as String and calls
`engine.render(String, Map)`. Custom engines therefore never see file paths; they only see content.

---

## Render pipeline

TemplateRenderer.render(Path, Params)
│
├── normalize path → absolute
│
├── isRegularFile(absolute)?
│ no → throw TemplateNotFoundException
│ yes ↓
│
├── params.asMap() → Map<String, Object>
│
└── engine instanceof FreeMarkerEngine?
│
├── yes → FreeMarkerEngine.renderFromPath(absolute, map)
│ │
│ └── Configuration.getTemplate(absolute)         (FreeMarker cache)
│ │ - cache hit → reuse parsed Template
│ │ - cache miss → AbsolutePathTemplateLoader
│ │ .findTemplateSource(absolute)
│ │ .getReader(strict UTF-8 decoder)
│ ▼
│ Template.process(map, StringWriter)
│ │
│ ├── ParseException → TemplateRenderException
│ ├── TemplateException → TemplateRenderException
│ ├── CharCodingException → TemplateNotFoundException
│ └── IOException → TemplateRenderException
│
└── no → readFileStrict(absolute, encoding)  (CharsetDecoder, REPORT mode)
│
├── CharCodingException → TemplateNotFoundException
├── IOException → TemplateNotFoundException
▼
engine.render(content, map)
│
└── (custom engine handles failures; expected to throw
TemplateRenderException on errors)

Inline rendering (`render(String, Params)`) skips the file branch entirely. For `FreeMarkerEngine`,
the inline path constructs a fresh `Template` from a `StringReader` per call — bypassing the cache.
Cache misses for inline templates would pollute the cache with hash-keyed entries; a fresh `Template`
is cheaper than maintaining cache discipline.

---

## FreeMarker integration

### Configuration settings

```java
Configuration.VERSION_2_3_34
setIncompatibleImprovements(VERSION_2_3_34)   // opt into latest behavior

setDefaultEncoding(encoding.name())

setOutputEncoding(encoding.name())

setLogTemplateExceptions(false)               // we wrap & rethrow, don't double-log

setWrapUncheckedExceptions(true)              // wrap RuntimeException as TemplateException

setFallbackOnNullLoopVariable(false)          // strict null handling in <#list>

setTemplateExceptionHandler(RETHROW_HANDLER)  // never write partial output on failure

setTemplateLoader(new AbsolutePathTemplateLoader())

setTemplateUpdateDelayMilliseconds(updateDelay.toMillis())
```

`exposeStaticMethods = true` (default) installs a shared `statics` variable backed by
`BeansWrapperBuilder(VERSION).build().getStaticModels()`. Templates can then call
`${statics['java.util.UUID'].randomUUID()}`.

### AbsolutePathTemplateLoader

A custom `TemplateLoader` that treats each template name as an absolute filesystem path. The
alternative — `FileTemplateLoader(new File("/"), allowAccessOutsideBaseDirectory=true)` — has known
issues on Windows (drive letter resolution). The custom loader sidesteps that by going through
`java.nio.file.Path` and `Files.isRegularFile()`.
findTemplateSource(name)        → File or null         (existence check)
getLastModified(File)           → long                 (drives hot-reload comparison)
getReader(File, encodingName)   → InputStreamReader    (with strict CharsetDecoder)
closeTemplateSource(File)       → no-op                (FreeMarker closes the Reader)

The strict decoder is the critical piece: FreeMarker's default would silently mangle non-UTF-8
content. Strict decoding propagates `MalformedInputException` (an `IOException`) up through
`Configuration.getTemplate()`, which `FreeMarkerEngine.renderFromPath()` recognizes and translates
to `TemplateNotFoundException`.

### Hot reload

`templateUpdateDelay = 0` (default) means FreeMarker checks `getLastModified()` on every cache hit.
With the absolute-path loader, this is one stat() syscall per render — acceptable for typical loads.
For high-throughput scenarios, set a positive duration (e.g. `5s`) so FreeMarker only re-stats after
the configured interval has elapsed since the last check. The library does NOT install a file watcher;
hot reload is purely poll-based.

### Inline rendering and the cache

Inline renders construct `new Template("inline", new StringReader(content), configuration)` per call.
This template does NOT enter the cache — its name is the literal string `"inline"`, identical across
all inline renders, but FreeMarker's cache only stores templates loaded via `getTemplate(name)`.

Constructing a `Template` parses the template text on each inline render. For workloads that re-render
the same inline string many times, callers should write the string to a temp file and use
`render(Path, Params)` instead.

---

## Parameter source semantics

### MapParams

Defensive copy of the source map at construction (`new LinkedHashMap<>(source)`). `asMap()` returns
a fresh copy on each call — callers may mutate the returned map without affecting subsequent renders.

### JsonParams

construction → store Path, no IO
first asMap()  → Files.newInputStream(path)
ObjectMapper.readValue(in, Map<String, Object>)
cache result (volatile field, double-checked locking)
return defensive copy
later asMap()  → return defensive copy of cached map

The Jackson `ObjectMapper` is a static singleton — Jackson's `readValue` is thread-safe after
configuration. No JsonParser pooling, no streaming — JSON parameter files are expected to be small
(< 1MB). Root JSON value MUST be an object (`{...}`); arrays or scalars throw `TemplateParamException`.

Nested JSON objects become nested `Map<String, Object>` (Jackson's default `Map` binding).
FreeMarker's dot-notation traverses these maps natively. Flat dotted keys (`{"a.b": "x"}`) are NOT
expanded — they remain literal keys and are unreachable via standard template syntax.

### SpringParams

The prefix is required and validated at construction — null or blank throws
`TemplateParamException` at `new SpringParams(env, prefix)` time, not at first render.

Resolution at `asMap()`:
for each EnumerablePropertySource in environment:
for each propertyName:
if propertyName starts with prefix:
tail = propertyName - prefix
value = environment.getProperty(propertyName)   ← honors placeholder resolution
insertNested(result, tail, value)

Non-enumerable property sources (e.g. `RandomValuePropertySource`) are skipped — there is no way to
list their keys. This matches Spring's own `@ConfigurationProperties` behavior.

`insertNested()` splits the tail on `.` and walks/creates nested maps. If a path collides with an
existing scalar (`a = "1"` then `a.b = "2"`), the scalar is replaced by a map containing `b = "2"`.
This matches the order-of-iteration of `EnumerablePropertySource.getPropertyNames()`, which is not
guaranteed — pathological key sets where order matters will produce non-deterministic results.
In practice this collision pattern doesn't occur in typical Spring configurations.

### CombinedParams

Resolves each source via `asMap()` in argument order, then deep-merges:
deepMerge(target, overlay):
for each (key, overlayValue) in overlay:
existing = target[key]
if existing instanceof Map and overlayValue instanceof Map:
mergedNested = new LinkedHashMap(existing)
deepMerge(mergedNested, overlayValue)
target[key] = mergedNested
else:
target[key] = overlayValue ← scalar wins, replaces map if any

Collision rules:

| Existing | Overlay | Result                         |
|----------|---------|--------------------------------|
| scalar   | scalar  | overlay scalar                 |
| map      | map     | recursively merged map         |
| scalar   | map     | overlay map                    |
| map      | scalar  | overlay scalar (map discarded) |

---

## Exception hierarchy

RuntimeException
└── BedrockWireTemplateException                (abstract)
├── TemplateNotFoundException file missing, unreadable, or wrong charset
├── TemplateParamException param source malformed or null
└── TemplateRenderException engine reported a render failure

All exceptions are unchecked — callers don't have to declare them. Selected throw sites:

| Class                     | Throws                      | When                                                                  |
|---------------------------|-----------------------------|-----------------------------------------------------------------------|
| `TemplateRenderer`        | `TemplateNotFoundException` | path is null, file missing, file not regular, or charset decode fails |
| `TemplateRenderer`        | `TemplateParamException`    | `params` argument is null                                             |
| `TemplateRenderer`        | `TemplateRenderException`   | inline content null, or engine fails                                  |
| `FreeMarkerEngine`        | `TemplateRenderException`   | parse error, render error, or IO during processing                    |
| `FreeMarkerEngine`        | `TemplateNotFoundException` | charset decode error during file load                                 |
| `MapParams`, `JsonParams` | `TemplateParamException`    | null source / null path                                               |
| `JsonParams.asMap()`      | `TemplateParamException`    | file missing, IO error, invalid JSON, non-object root                 |
| `SpringParams`            | `TemplateParamException`    | null env, null/blank prefix                                           |
| `CombinedParams`          | `TemplateParamException`    | null/empty sources, null source element                               |

`TemplateRenderException` messages preserve the underlying engine's diagnostic verbatim, plus
`[template=<path>, line=N, column=N, availableParams=[a, b, c]]`. The available-params block helps
authors diagnose typos without re-running with logging enabled.

---

## Spring auto-configuration

```java

@AutoConfiguration
@ConditionalOnClass(TemplateRenderer.class)
@EnableConfigurationProperties(TemplateProperties.class)
public class TemplateAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(TemplateRenderer.class)
    public TemplateRenderer templateRenderer(TemplateProperties properties) { ...}
}
```

Registration source:
src/main/resources/META-INF/spring/
└── org.springframework.boot.autoconfigure.AutoConfiguration.imports
↳ cz.syntea.bedrock.wire.template.autoconfigure.TemplateAutoConfiguration

Properties bound to `bedrock.wire.template.*`:

| Property                | Type     | Default |
|-------------------------|----------|---------|
| `expose-static-methods` | boolean  | `true`  |
| `encoding`              | Charset  | `UTF-8` |
| `template-update-delay` | Duration | `0s`    |

Back-off: `@ConditionalOnMissingBean(TemplateRenderer.class)` is applied at the **bean** level
(not on a nested `@Configuration`) to avoid the known evaluation-order issue where
`@ConditionalOnMissingBean` on a nested configuration class evaluates before user-config beans
are visible to the `BeanFactory`. At the bean level, the condition is evaluated during bean creation
when all definitions are visible.

---

## Bean lifecycle

Context refresh
│
├── Phase: ConfigurationProperties binding
│ └── TemplateProperties populated from environment
│
├── Phase: Bean creation
│ └── TemplateRenderer (skipped if user @Bean exists)
│ │
│ └── builds FreeMarkerEngine
│ │
│ ├── Configuration created
│ ├── AbsolutePathTemplateLoader installed
│ └── statics shared variable installed (if exposeStaticMethods)
│
├── ... application runs ...
│ └── renderer.render(path, params)  — stateless, thread-safe
│
└── Phase: Context close
└── (no resources to release; FreeMarker Configuration has no close())

The renderer holds no resources requiring explicit cleanup. The FreeMarker `Configuration` keeps a
template cache that is GC'd with the bean. There is no `@PreDestroy` hook.

---

## Design decisions and rationale

### Why pure FreeMarker, no wrapper?

A wrapper layer would have to either curate a function map (incomplete coverage of FreeMarker's
built-ins) or proxy the whole API (dead weight). Pure FreeMarker means template authors get the
full manual and one Google search resolves any "how do I X" question. The library's job is wiring,
not reinvention.

### Why a custom AbsolutePathTemplateLoader?

`FileTemplateLoader(new File("/"), allowAccessOutsideBaseDirectory=true)` works on POSIX but
mishandles Windows drive letters — paths like `C:\templates\x.xml` get prefixed by FreeMarker's
base directory, producing invalid resolved paths. Going through `java.nio.file.Path` is the only
portable approach that also supports the strict-UTF-8 decoder requirement.

### Why is `renderFromPath` not on the SPI?

`TemplateEngine.render(String, Map)` is the minimum surface a custom engine must implement.
File loading is a renderer-level concern — when a custom engine is configured, the renderer reads
the file itself with strict charset decoding and passes the content to the engine. Adding
`render(Path, Map)` to the SPI would force every custom engine to reimplement file IO and charset
handling. `FreeMarkerEngine.renderFromPath()` is a public method on the concrete class (called via
`instanceof` from the renderer) so that file renders for the default engine bypass the
content-rehash overhead and go through FreeMarker's native cache instead.

### Why required prefix in fromSpring?

Without a prefix, every Spring property is reachable from any template — including
`spring.datasource.password`, `server.ssl.key-store-password`, and any custom secret bound to a
`@ConfigurationProperties` class. Templates produced by code review may be safe today, but a
template generated from user input or pulled from a remote source would leak the entire
configuration. Requiring a prefix at the type level makes accidental leaks structurally impossible.

### Why sealed Params interface?

A non-sealed interface invites `MyLazyParams` subclasses that defer resolution until inside the
template — which then runs on the FreeMarker thread, holds locks during slow IO, and surfaces
errors as render failures rather than at param-construction time. The four built-in sources cover
every real-world use case (programmatic, JSON, Spring, composition); custom sources should be
discussed at the library level before being added.

### Why not strip prefix-stripped properties to a flat map?

Spring's binding model uses dotted keys to express nested structure (`monitor.check.x.foo.bar`).
FreeMarker's dot-notation in templates means nested object traversal (`${foo.bar}`). To make the
two work together, `SpringParams` rebuilds the dotted-tail keys into nested maps. A flat
`{foo.bar: "value"}` map would be unreachable from templates because FreeMarker would interpret
`${foo.bar}` as "look up `foo`, then look up `bar` on the result".

### Why CombinedParams resolves sources lazily?

Each source's `asMap()` is called inside `CombinedParams.asMap()` — so a `JsonParams` source inside
a combination is read on every render unless it caches internally (which it does). This lazy
resolution lets callers compose sources at startup and trust the renderer to fetch fresh values when
they change (e.g. a `SpringParams` source picks up env changes automatically). Callers who want
snapshot semantics can wrap a `CombinedParams` in `Params.of(combined.asMap())`.

### Why `@ConditionalOnMissingBean` at the bean level, not on a nested @Configuration?

In this codebase, `@ConditionalOnMissingBean` on a nested `@Configuration` class evaluates during
configuration class parsing, which can happen before user-config beans are visible to the
`BeanFactory`. The auto-config would not back off correctly when a user defined their own
`TemplateRenderer` bean. Applying `@ConditionalOnMissingBean(TemplateRenderer.class)` directly on
the `@Bean` method evaluates during bean creation, when all definitions — including user beans —
are visible. This pattern is used consistently across the bedrock-wire suite.