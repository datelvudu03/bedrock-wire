# bedrock-wire – Technická specifikace v1.0

---

## Obsah

**0. Úvod**

**1. bedrock-wire-client**
- 1.1 Úvod a architektura
- 1.2 Základní pojmy
- 1.3 Architektura
- 1.4 Konfigurační model
- 1.5 Transport target a connection pooling
- 1.6 HttpClientRegistry
- 1.7 Rozhraní HttpClient
- 1.8 Požadavek a odpověď
- 1.9 TLS konfigurace
- 1.10 Chybové stavy
- 1.11 Konfigurační reference
- 1.12 Příklad použití

**2. bedrock-wire-monitor**
- 2.1 Úvod a architektura
- 2.2 Architektura monitoru
- 2.3 Základní pojmy a normativní jazyk
- 2.4 Konfigurační model (3-level lookup)
- 2.5 Execution model
- 2.6 Transport layer
- 2.7 Validation
- 2.8 Output model
- 2.9 Templates a dynamické proměnné
- 2.10 Runtime komponenty
- 2.11 Konfigurační reference
- 2.12 Příklad konfigurace
- 2.13 TLS profily monitoru

## 0. Úvod

`bedrock-wire` je Java knihovna pro spolehlivé HTTP volání a monitorování HTTP endpointů. Skládá se ze dvou samostatných modulů:

| modul | popis |
|---|---|
| `bedrock-wire-client` | reaktivní HTTP klient se sdílenými connection pooly a správou TLS; použitelný samostatně mimo monitor modul |
| `bedrock-wire-monitor` | konfigurovatelný HTTP monitor postavený na `bedrock-wire-client`; spouští checky, validuje odpovědi, reportuje výsledky |

**Předpoklady**

- Java 17+
- Project Reactor (`reactor-core`, `reactor-netty`)
- Spring WebClient (volitelné; `bedrock-wire-client` abstrahuje implementaci přes `HttpClient` interface)

**Vztah modulů**

`bedrock-wire-monitor` závisí na `bedrock-wire-client`. `bedrock-wire-client` nemá závislost na monitor modulu.


---

# 1. bedrock-wire-client

## 1.1 Úvod a architektura

`bedrock-wire-client` je Java knihovna pro reaktivní HTTP komunikaci se sdíleným connection poolingem.

Knihovna poskytuje:

- jednotné rozhraní pro provádění HTTP požadavků
- sdílené connection pooly podle transport target
- správu TLS konfigurace
- nezávislost na vyšší aplikační logice (monitor, scheduler apod.)

**Omezení:** `bedrock-wire-client` pracuje výhradně s **textovými payloady** (request body i response body jsou `String`). Binární payloady (gzip, protobuf, multipart) nejsou podporovány.

`bedrock-wire-client` je použitelný samostatně jako HTTP klientská komponenta v aplikacích pracujících s textovými protokoly (REST/JSON, SOAP/XML apod.), kde jsou cílové endpointy předem známy a konfigurovány přes `baseUrl`.

---

## 1.2 Základní pojmy

**Globální pravidlo kódování:** Veškeré textové payloady (request body, response body, template soubory) MUST být interpretovány jako UTF-8.

| pojem | definice |
|---|---|
| `HttpClient` | rozhraní pro provedení jednoho HTTP požadavku |
| `HttpClientRegistry` | správce instancí `HttpClient` a jejich connection poolů |
| `TransportTarget` | identita connection poolu: `scheme + host + port + tlsConfigName` |
| `HttpClientConfig` | konfigurační objekt pro vytvoření `HttpClient` |
| `TlsConfig` | konfigurační objekt pro TLS parametry |
| `HttpRequest` | popis HTTP požadavku |
| `HttpResponse` | výsledek HTTP požadavku |

---

## 1.3 Architektura

### 1.3.1 Architektonický diagram

```
klient kódu
    ↓
HttpClientRegistry.get(HttpClientConfig)
    ↓
HttpClient (per config, cacheovaný)
    ↓
ConnectionPool (per TransportTarget, sdílený)
    ↓
Spring WebClient / Reactor Netty
```

### 1.3.2 Vrstvení odpovědností

| vrstva | odpovědnost |
|---|---|
| `HttpClientRegistry` | cache HttpClient instancí, správa connection poolů |
| `HttpClient` | provedení HTTP požadavku, aplikace timeout |
| `ConnectionPool` | sdílení TCP spojení per TransportTarget |
| Spring WebClient | reaktivní HTTP implementace |

### 1.3.3 Oddělení lifecycle

**Architektonické pravidlo:** `HttpClient` instance ≠ connection pool.

`HttpClientRegistry` spravuje connection pooly nezávisle na `HttpClient` instancích. `HttpClient` instance jsou lehké wrappery nad transport klientem a mohou být vytvářeny nezávisle na connection poolech.

Lifecycle connection poolů je řízen výhradně `HttpClientRegistry`. Vytvoření nového `HttpClient` nevytváří nový pool – pool je sdílen dle transport target.

Toto oddělení umožňuje:
- sdílení poolů mezi různými volajícími
- nezávislé nastavení timeoutů per klient
- centrální správu TLS konfigurace
- znovupoužitelnost mimo monitor modul

---

## 1.4 Konfigurační model

### 1.4.1 HttpClientConfig

```java
public class HttpClientConfig {

    String clientId;               // unikátní identifikátor (pro caching v registry)

    URI baseUrl;                   // scheme + host + port; MUST NOT obsahovat path ani query

    Duration connectionTimeout;    // default: 5s
    Duration responseTimeout;      // čas od odeslání HTTP requestu do přijetí **prvního byte** HTTP response; nezahrnuje DNS lookup ani TLS handshake (součást connectionTimeout); viz HttpResponse.duration pro celkový čas; default: 30s
    // POZOR: timeout se uplatní pouze na první byte – dlouhé stahování response body není tímto timeoutem omezeno (viz maxResponseBodySize jako doplňková ochrana)

    Map<String, String> defaultHeaders; // hlavičky přidávané ke každému požadavku

    String tlsConfigName;          // název TlsConfig v registry; null = JVM default TLS

}
```

Pravidla:

- `clientId` je identita **transportního klienta** (ne monitorované služby); monitor modul MAY použít `serviceName` jako `clientId`, ale není to požadavek knihovny – jsou to nezávislé identity různých vrstev; SHOULD odpovídat názvu service – tím se eliminuje třída konfiguračních chyb kde stejný `clientId` odkazuje na různé `baseUrl`
- `clientId` SHOULD být stabilní, čitelný identifikátor (ne generované UUID); automaticky generovaný `clientId` MAY vést ke vzniku nadbytečných klientů při každém restartu
- `HttpClientConfig` MUST být immutable; změna konfigurace vyžaduje vytvoření nového `clientId`
- `clientId` MUST být unikátní v rámci jedné `HttpClientRegistry`
- `baseUrl` MUST obsahovat pouze `scheme + host + port` (bez path, bez trailing slash); implementace MUST normalizovat `baseUrl` dle pravidel kanonické formy URL (viz kap. 1.4.3)
- `tlsConfigName` odkazuje na `TlsConfig` registrovaný v `HttpClientRegistry`; pokud není definován, použije se implicitní JVM TLS profil

### 1.4.2 TlsConfig

```java
public class TlsConfig {

    String configName;             // unikátní identifikátor TLS konfigurace

    // klientský certifikát (mTLS)
    Path   clientCert;             // cesta k souboru; null = bez klientského certifikátu
    String clientCertPassword;     // heslo; ignorováno pokud clientCert == null
    String clientCertType;         // "PKCS12", "JKS"; ignorováno pokud clientCert == null
    String clientCertAlias;        // alias v keystoru; ignorováno pokud clientCert == null

    // server truststore
    Path   trustStore;             // null = JVM default truststore
    String trustStorePassword;     // ignorováno pokud trustStore == null
    String trustStoreType;         // "PKCS12", "JKS"; ignorováno pokud trustStore == null

    boolean hostnameVerification;  // default: true

    // volitelné omezení TLS parametrů
    List<String> enabledProtocols;    // null = JVM default (např. ["TLSv1.2", "TLSv1.3"])
    List<String> enabledCipherSuites; // null = JVM default

}
```

Pravidla:

- `clientCertPassword`, `clientCertType`, `clientCertAlias` jsou ignorovány, pokud `clientCert` není definován
- pokud `clientCert` je definován a certifikát vyžaduje heslo, ale `clientCertPassword` chybí → inicializace MUST selhat (fail-fast)
- `hostnameVerification = false` MUST NOT být použito v produkci; implementace SHOULD zalogovat WARNING při detekci
- pokud `trustStore` není definován, použije se výchozí truststore JVM

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

Identita je odvozena z `baseUrl` (`HttpClientConfig`) a `tlsConfigName`. Více `HttpClient` instancí se stejným transport target sdílí jeden connection pool.

**Normativní pravidlo:** `TransportTarget` identifikuje výhradně connection pool. `HttpClientConfig` identifikuje transportního klienta. Více `HttpClient` instancí (různé `clientId`, různé timeouty) může sdílet jeden pool, pokud mají stejný `TransportTarget`.

Transport target identita je založena na **názvu** `tlsConfigName`, nikoli na jeho obsahu. Dva `TlsConfig` objekty se stejnou konfigurací, ale různými jmény, tvoří dva oddělené transport targety.

Absence `tlsConfigName` tvoří vlastní transport target identitu (JVM default TLS).

**Důležité:** `tlsConfigName` je plnohodnotná součást identity `TransportTarget`. Dva requesty na stejný `host:port` s různými `tlsConfigName` MUST použít různé connection pooly – i kdyby TLS konfigurace byla fakticky identická. Identita je určena názvem, nikoliv obsahem konfigurace.

Normalizace `TransportTarget` (canonical form) MUST být provedena v `HttpClientRegistry` při volání `get()` (nebo v builderu `HttpClientConfig`) – nikoli až při vytváření požadavku. Runtime pak pracuje výhradně s canonical form.

- `scheme` MUST být normalizován na lowercase
- `host` MUST být normalizován na lowercase
- pokud port není explicitně uveden, implementace MUST použít default (`80` pro `http`, `443` pro `https`)

Bez normalizace mohou vzniknout duplicitní connection pooly pro stejný endpoint.

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

    // registrace TLS konfigurace
    void registerTlsConfig(TlsConfig config);

    // získání HttpClient; vytvoří nebo vrátí cacheovaný
    HttpClient get(HttpClientConfig config);

    // uzavření všech poolů
    void close();

}
```

### 1.6.2 Lifecycle

`HttpClientRegistry` SHOULD být singleton v rámci aplikace. Vytváření více instancí může vést ke vzniku duplicitních connection poolů pro stejný `TransportTarget` a zbytečnému plýtvání zdroji.

### 1.6.3 Chování

- `HttpClientRegistry` MUST být thread-safe
- `get()` MUST být bezpečné pro paralelní volání
- `close()` SHOULD být idempotentní (opakované volání SHOULD být bezpečné)
- `get(config)` MUST vracet stejnou instanci pro stejný `clientId`
- `clientId` MUST jednoznačně identifikovat celou konfiguraci klienta (včetně `baseUrl`, `tlsConfigName`, timeoutů, `defaultHeaders`); pokud je `get()` volán se stejným `clientId` ale jakýmkoliv odlišným parametrem, implementace MUST vyhodit výjimku
- pokud `tlsConfigName` odkazuje na neregistrovaný `TlsConfig` → `get()` MUST vyhodit výjimku
- `close()` MUST uzavřít všechny connection pooly a uvolnit zdroje; po zavolání `close()` MUST každé další volání `get()` vyhodit `IllegalStateException`. `close()` SHOULD být voláno pouze při shutdown aplikace – jde o globální destruktivní operaci. Registry implementuje `AutoCloseable` pro integraci s try-with-resources a IoC kontejnery.

### 1.6.4 Caching

`HttpClient` instance jsou cacheovány podle `clientId`. Cache není omezena velikostí. `HttpClientRegistry` SHOULD být inicializován statickou konfigurací při startu aplikace. Dynamické vytváření `HttpClientConfig` za běhu (např. s UUID jako `clientId`) není doporučeno – vede k neomezenému počtu connection poolů a potenciálnímu úniku TCP spojení. Implementace SHOULD poskytovat diagnostické logování při překročení konfigurovatelného limitu klientů.

Změna konfigurace se neprojeví automaticky – je nutné zavolat `close()` a vytvořit nový `HttpClientRegistry`.

---

## 1.7 Rozhraní HttpClient

```java
public interface HttpClient {

    Mono<HttpResponse> execute(HttpRequest request);

}
```

- `execute()` provede HTTP požadavek asynchronně (reaktivní)
- timeout z `HttpClientConfig.responseTimeout` se aplikuje na každé volání
- `execute()` MUST NOT blokovat volající vlákno
#### Příklad použití

```java
// 1. Registrace TLS konfigurace
TlsConfig tls = new TlsConfig();
tls.setConfigName("payments-tls");
tls.setClientCert(Path.of("/certs/client.p12"));
tls.setClientCertPassword("secret");
tls.setClientCertType("PKCS12");
tls.setTrustStore(Path.of("/certs/truststore.p12"));
tls.setTrustStorePassword("trustsecret");
tls.setTrustStoreType("PKCS12");

// 2. Konfigurace klienta
HttpClientConfig config = HttpClientConfig.builder()
    .clientId("payments")
    .baseUrl(URI.create("https://payments.example.com"))
    .connectionTimeout(Duration.ofSeconds(3))
    .responseTimeout(Duration.ofSeconds(10))
    .tlsConfigName("payments-tls")
    .build();

// 3. Vytvoření registry a klienta
HttpClientRegistry registry = new HttpClientRegistryImpl();
registry.registerTlsConfig(tls);
HttpClient client = registry.get(config);

// 4. Provedení požadavku
HttpRequest request = HttpRequest.builder()
    .method(HttpMethod.POST)
    .url(URI.create("/api/health"))
    .header("Content-Type", "application/xml")
    .body("<request><check>health</check></request>")
    .build();

Mono<HttpResponse> response = client.execute(request);

// 5. Shutdown
registry.close();
```

- `execute()` MAY vracet `Mono.error` pro transportní chyby (timeout, IO error); vyšší vrstvy jsou odpovědné za mapování výjimek na `TransportStatus` (viz viz kap. 2.6.3)

---

## 1.8 Požadavek a odpověď

### 1.8.1 HttpRequest

```java
public class HttpRequest {

    HttpMethod method;                    // GET, POST, PUT, DELETE, HEAD; case-insensitive, implementace SHOULD normalizovat na uppercase při vytvoření
    URI url;                              // MUST být relativní vůči baseUrl; absolutní URL není povolena
    Map<String, List<String>> headers;    // MUST NOT být null; prázdná mapa je povolena; mergedovány s defaultHeaders z config
    String body;                          // null MUST být interpretováno jako prázdné tělo požadavku

}
```

Pravidla:

- hodnota `method` je case-insensitive; implementace SHOULD normalizovat na uppercase
- `headers` z požadavku mají vyšší prioritu než `defaultHeaders` z `HttpClientConfig`
- pokud stejná hlavička existuje v `defaultHeaders` i v `HttpRequest.headers`, hodnota z `HttpRequest.headers` MUST nahradit hodnotu z `defaultHeaders` (nikoliv být přidána jako další hodnota); toto pravidlo zabrání duplicitním hodnotám hlaviček jako `Content-Type` nebo `Authorization`
- názvy hlaviček MUST být porovnávány case-insensitive (dle RFC 7230)

### 1.8.2 HttpResponse

```java
public class HttpResponse {

    int statusCode;                       // HTTP status; 0 pokud odpověď nebyla přijata
    Map<String, List<String>> headers;
    String responseBody;                  // SHOULD být prázdný string pokud HTTP odpověď neobsahuje body; implementace SHOULD nepoužívat null
    Duration duration;                    // čas od odeslání requestu do přijetí posledního byte response

}
```

### 1.8.3 Maximální velikost response body

Implementace SHOULD omezit maximální velikost načteného `responseBody`. Doporučená výchozí hodnota je **1 MB**. Překročení limitu SHOULD způsobit chybu ekvivalentní `IO_ERROR`.

Hodnota může být konfigurovatelná přes `HttpClientConfig` (implementační rozhodnutí).


## 1.9 TLS konfigurace

### 1.9.1 Registrace

`TlsConfig` musí být registrován v `HttpClientRegistry` před prvním voláním `get()` s odkazem na daný `tlsConfigName`.

`TlsConfig` MUST být immutable. Registrace stejného `configName` dvakrát MUST vyhodit výjimku. Registrace po prvním použití konfigurace (tj. po vytvoření prvního pool pro daný transport target) MAY být zakázána (fail-fast).

### 1.9.2 Inicializace

TLS konfigurace je inicializována při prvním vytvoření connection poolu pro daný transport target. Chyby v konfiguraci (neplatný certifikát, špatné heslo) jsou detekovány v tomto okamžiku (fail-fast).

### 1.9.3 mTLS

mTLS (klientský certifikát) je aktivován definováním `clientCert` v `TlsConfig`.

Podporované formáty: `PKCS12`, `JKS`.

---

## 1.10 Chybové stavy

| situace | chování |
|---|---|
| `tlsConfigName` není registrován | `get()` vyhodí `IllegalArgumentException` |
| chybný certifikát nebo heslo | fail-fast při inicializaci poolu |
| timeout | `execute()` vrátí `Mono.error(TimeoutException)` |
| connection error | `execute()` vrátí `Mono.error(ConnectException)` |
| IO error | `execute()` vrátí `Mono.error(IOException)` |

---

## 1.11 Konfigurační reference

### 1.11.1 HttpClientConfig parametry

| parametr | typ | povinný | popis |
|---|---|---|---|
| `clientId` | String | ano | unikátní identifikátor |
| `baseUrl` | URI | ano | scheme + host + port bez trailing slash |
| `connectionTimeout` | Duration | ne | default `5s` |
| `responseTimeout` | Duration | ne | default `30s` |
| `defaultHeaders` | Map | ne | hlavičky přidávané ke každému požadavku |
| `tlsConfigName` | String | ne | odkaz na TlsConfig; null = JVM default |

### 1.11.2 TlsConfig parametry

| parametr | typ | povinný | popis |
|---|---|---|---|
| `configName` | String | ano | unikátní identifikátor |
| `clientCert` | Path | ne | cesta k souboru klientského certifikátu |
| `clientCertPassword` | String | ne | heslo keystoru; vyžadováno pokud certifikát je chráněn heslem |
| `clientCertType` | String | ne | `PKCS12` nebo `JKS` |
| `clientCertAlias` | String | ne | alias v keystoru |
| `trustStore` | Path | ne | vlastní truststore; null = JVM default |
| `trustStorePassword` | String | ne | heslo truststore |
| `trustStoreType` | String | ne | `PKCS12` nebo `JKS` |
| `hostnameVerification` | boolean | ne | default `true`; `false` pouze pro testování |
| `enabledProtocols` | List | ne | seznam povolených TLS protokolů; null = JVM default |
| `enabledCipherSuites` | List | ne | seznam povolených šifer; null = JVM default |

---

## 1.12 Příklad použití

### 1.12.1 Základní setup

Kompletní příklad: registrace TLS, vytvoření klienta, provedení požadavku, shutdown.

```java
// 1. TLS profil – mTLS s klientským certifikátem a vlastním trust store
TlsConfig tls = new TlsConfig();
tls.setConfigName("payments-tls");
tls.setClientCert(Path.of("/certs/payments-client.p12"));
tls.setClientCertPassword("secret");
tls.setClientCertType("PKCS12");
tls.setTrustStore(Path.of("/certs/payments-truststore.p12"));
tls.setTrustStorePassword("trustsecret");
tls.setTrustStoreType("PKCS12");

// 2. Konfigurace klienta
HttpClientConfig config = HttpClientConfig.builder()
    .clientId("payments")
    .baseUrl(URI.create("https://payments.example.com"))   // scheme + host + port only
    .connectionTimeout(Duration.ofSeconds(3))
    .responseTimeout(Duration.ofSeconds(10))
    .tlsConfigName("payments-tls")
    .defaultHeader("Content-Type", "application/xml")
    .build();

// 3. Registry – singleton, inicializovat při startu aplikace
HttpClientRegistry registry = new HttpClientRegistryImpl();
registry.registerTlsConfig(tls);                           // před prvním get()

// 4. Získání klienta (vytvoří nebo vrátí cacheovaný)
HttpClient client = registry.get(config);

// 5. Provedení požadavku
HttpRequest request = HttpRequest.builder()
    .method(HttpMethod.POST)
    .url(URI.create("/api/health"))                        // relativní vůči baseUrl
    .header("X-Request-Id", "abc-123")                    // přebíjí defaultHeader
    .body("<request><check>health</check></request>")
    .build();

HttpResponse response = client.execute(request).block();  // .block() pouze na hranici scheduleru
// response.getStatusCode() == 200
// response.getDuration()    == čas do posledního byte

// 6. Shutdown – volat pouze při ukončení aplikace
registry.close();
```

### 1.12.2 Sdílení connection poolu

Dva klienti se stejným `TransportTarget` (`scheme + host + port + tlsConfigName`) sdílí jeden connection pool.

```java
// Klient A – service "payments-health"
HttpClientConfig configA = HttpClientConfig.builder()
    .clientId("payments-health")
    .baseUrl(URI.create("https://payments.example.com"))
    .responseTimeout(Duration.ofSeconds(5))
    .tlsConfigName("payments-tls")
    .build();

// Klient B – service "payments-process" (jiný clientId, jiný timeout)
HttpClientConfig configB = HttpClientConfig.builder()
    .clientId("payments-process")
    .baseUrl(URI.create("https://payments.example.com"))  // stejný host
    .responseTimeout(Duration.ofSeconds(30))
    .tlsConfigName("payments-tls")                        // stejný TLS profil
    .build();

HttpClient clientA = registry.get(configA);
HttpClient clientB = registry.get(configB);

// clientA a clientB jsou různé HttpClient instance (různý clientId, různý responseTimeout)
// ale sdílí JEDEN connection pool:
//   TransportTarget("https", "payments.example.com", 443, "payments-tls") → 1 pool

// Klient C – stejný host, ale jiný TLS profil → vlastní connection pool
HttpClientConfig configC = HttpClientConfig.builder()
    .clientId("payments-internal")
    .baseUrl(URI.create("https://payments.example.com"))  // stejný host
    .tlsConfigName("internal-tls")                        // jiný TLS profil!
    .build();

HttpClient clientC = registry.get(configC);
// clientC používá DRUHÝ pool:
//   TransportTarget("https", "payments.example.com", 443, "internal-tls") → 2. pool
```



---

# 2. bedrock-wire-monitor

## 2.1 Úvod a architektura

`bedrock-wire-monitor` je Java knihovna pro periodické HTTP monitorování služeb. Staví na `bedrock-wire-client`  pro transport vrstvu.

Knihovna zajišťuje:

- periodické spouštění HTTP checků dle konfigurace
- validaci HTTP odpovědí
- mapování výsledků na `MonitorStatus`
- notifikaci výsledků přes `MonitorResultListener`

---

## 2.2 Architektura monitoru

### 2.2.1 Architektonický diagram

```
scheduler
    ↓
monitor layer ──────────────────→ validators
    ↓                                  ↑
transport layer (bedrock-wire-client)  │
    ↓                                  │
HttpClientRegistry                 MonitorResult
    ↓
connection pool (per TransportTarget)
```

### 2.2.2 Oddělení odpovědností

| vrstva | odpovědnost |
|---|---|
| scheduler | periodické spouštění check runů |
| monitor layer | orchestrace: konfigurace, retry, status mapping, validace |
| transport layer | HTTP komunikace (delegováno na `bedrock-wire-client`) |
| validators | vyhodnocení HTTP odpovědi |

### 2.2.3 Modularita

Každá vrstva je volitelně nahraditelná vlastní implementací za předpokladu dodržení rozhraní. Výchozí implementace transportu MUST používat `bedrock-wire-client`.

---

## 2.3 Základní pojmy a normativní jazyk

### 2.3.1 Terminologie

| pojem | definice |
|---|---|
| **check** | definice jednoho periodického HTTP požadavku a jeho validace |
| **service** | cílová HTTP služba (baseUrl + společné parametry) |
| **check run** | jedno provedení checku (od startu po výsledek) |
| **transport** | HTTP komunikační vrstva (`bedrock-wire-client`) |
| **validator** | komponenta vyhodnocující HTTP odpověď |
| **attempt** | jeden HTTP požadavek v rámci check runu (včetně retry) |

### 2.3.2 Normativní jazyk

Klíčová slova `MUST`, `MUST NOT`, `SHOULD`, `SHOULD NOT`, `MAY` jsou použita dle RFC 2119.

---

## 2.4 Konfigurační model

### 2.4.1 Namespace konfigurace

Veškerá konfigurace monitoru je v namespace:

```
monitor.*
```

### 2.4.2 Lookup pravidla parametrů

Parametr `<param>` pro check `<checkName>` a jeho service `<serviceName>` se vyhledává vždy v tomto pořadí:

1. `monitor.check.<checkName>.<param>`
2. `monitor.service.<serviceName>.<param>`
3. `monitor.default.<param>`

Lookup končí při nalezení první hodnoty.

`monitor.default.<param>` je globální fallback pro všechny checky a services. Platí pouze pro parametry, pro které má globální default smysl (viz tabulka níže). Parametry vázané na konkrétní service (`baseUrl`, `tlsProfile`) v `monitor.default` nemají efekt.

| parametr | monitor.default povolen |
|---|---|
| `interval` | ano |
| `responseTimeout` | ano |
| `connectionTimeout` | ano |
| `retry.count` | ano |
| `retry.delay` | ano |
| `header.*` | ano |
| `validation.validators` | ano |
| `method` | ano |
| `baseUrl` | ne |
| `tlsProfile` | ne |

### 2.4.3 Default (fallback) konfigurace

Jméno `default` MUST NOT být použito jako název service ani check. Je vyhrazeno pro globální fallback namespace `monitor.default.*`.

### 2.4.4 Hlavičky (HTTP headers)

Hlavičky se slučují (merge) v tomto pořadí:

1. `monitor.default.header.*` (globální default)
2. `monitor.service.<service>.header.*` (přebíjí default)
3. `monitor.check.<check>.header.*` (přebíjí service)

Výsledné hlavičky jsou předány jako `Map<String, List<String>>` do `MonitorRequest`. Konfigurace přijímá single-value (String), runtime pracuje s multi-value.

Názvy hlaviček MUST být porovnávány case-insensitive (dle RFC 7230).

### 2.4.5 Konfigurační objekty (runtime model)

```java
public class CheckConfig {

    String checkName;
    String serviceName;

    HttpMethod method;
    String path;
    String query;
    String templateFile;

    Duration responseTimeout;

    int retryCount;
    Duration retryDelay;

    Map<String, String> headers;
    List<String> validators;
    Map<String, String> validationParams; // klíče bez prefixu "validation."

}
```

```java
public class ServiceConfig {

    String serviceName;

    URI url;                    // SHOULD být normalizováno bez trailing slash při načtení

    Duration connectionTimeout;
    Duration responseTimeout;

    Map<String, String> headers;

    String tlsProfile;          // název TLS profilu monitoru (viz kap. 2.13)

}
```

### 2.4.6 Configuration providers

```java
public interface MonitorConfigProvider {

    List<CheckConfig>   getChecks();
    List<ServiceConfig> getServices();
    List<TlsProfileConfig> getTlsProfiles();  // viz kap. 2.13

}
```

Výchozí implementace načítá konfiguraci z `.properties` souboru. Hodnoty typu `Duration` MUST obsahovat časovou jednotku (např. `5s`, `500ms`, `2m`). Holé číslo bez jednotky není validní.

Unikátnost názvů: `serviceName`, `checkName` a název TLS profilu MUST být unikátní v rámci jedné konfigurace. Při detekci kolize inicializace MUST selhat.

Implementace MUST NOT vracet `null` z žádné metody rozhraní; prázdné kolekce jsou povoleny.

---

## 2.5 Execution model

### 2.5.1 Lifecycle jednoho check runu

1. Scheduler aktivuje check dle `interval`.

2. Monitor layer ověří, zda check již neběží → **skip-if-running** (viz 2.5.3).

3. Monitor layer načte konfiguraci checku a příslušné service pomocí lookup pravidel (kap. 2.4.2).

4. Monitor layer sestaví `finalUrl`:

   Algoritmus sestavení `finalUrl`:
   Poznámka: `service.url` MUST být normalizováno při načtení konfigurace (v `MonitorConfigProvider`) dle pravidel kanonické formy URL (viz kap. 1.4.3). Runtime již pracuje s canonical form.
   1. normalizace `path`: zajistit leading `/` (pokud `path` není prázdný)
   2. konkatenace: `service.url + path`
   3. připojení query: `+ ?query` (pouze pokud `query` je definován)

   `service.url` MUST NOT obsahovat query část URL. Query parametry musí být předány výhradně přes `query`.

5. Pokud je definován `templateFile`, načte se body ze souboru a aplikují se dynamické proměnné (viz kap. 20).

   Pokud je `method = GET` a `templateFile` je definován, transport SHOULD ignorovat body. Implementace SHOULD zalogovat varování při kombinaci `method=GET` a definovaného `templateFile`.

6. Monitor layer předá požadavek transport layer a získá `MonitorResult`.

7. Pokud transport selže a je nakonfigurován retry → aplikuje se retry politika (viz 2.5.4).

8. Validators jsou aplikovány na `MonitorResult` (viz kap. 2.6).

9. Výsledky jsou zmapovány na `MonitorStatus` (viz 16.5).

10. `MonitorExecutionResult` je předán všem registrovaným listenerům.

### 2.5.2 Scheduler – sémantika spouštění

Scheduler používá **fixed rate** sémantiku: checky jsou spouštěny v pevných časových intervalech měřených od **startu** předchozího runu, bez ohledu na jeho dobu trvání. Doporučená implementace používá `ScheduledExecutorService.scheduleAtFixedRate()`.

Chování při startu: první run může být spuštěn okamžitě po startu nebo až po uplynutí prvního intervalu – toto je implementační rozhodnutí.

Důsledek: pokud check run trvá déle než je jeho interval (např. kvůli retry), scheduler se nepozastaví – použije se politika skip-if-running.

### 2.5.3 Scheduling policy: skip-if-running

Pravidlo:

- pro jeden check je povolena pouze jedna běžící instance
- pokud scheduler spustí check znovu, zatímco stále běží předchozí run, nový run se **přeskočí**
- skip event SHOULD být logovaný a počítaný; log SHOULD obsahovat: `checkName`, plánovaný čas spuštění a `requestId` aktuálně běžícího runu

Časté skip události indikují, že doba běhu check runu je delší než jeho interval (viz doporučení v 2.5.4). Pokud `executionDuration > interval`, efektivní interval checku bude násobkem konfigurovaného intervalu (např. při `interval=30s` a `executionDuration=45s` bude efektivní interval 60s). Implementace SHOULD toto chování dokumentovat v logech. Implementace SHOULD exponovat počítadlo skip událostí per check (`skipCount`) jako metriku – bez toho je tento stav špatně diagnostikovatelný v produkci. Implementace SHOULD logovat první skip událost pro daný check na úrovni WARN; opakované skipy SHOULD být agregovány (např. počet skipů za interval) aby nedocházelo k zahlcení logu.

### 2.5.4 Retry politika

Konfigurace:

```
retry.count  = počet opakování (default: 0)
retry.delay  = prodleva mezi pokusy (default: 1s)
```

Celkový počet pokusů (attempts) je vždy `retry.count + 1` (první pokus + retry pokusy). Tato hodnota je zaznamenána v `MonitorExecutionResult.attempts`.

`retry.delay` je prodleva **před každým retry pokusem** (ne po posledním attemptu). Sekvence: attempt → delay → attempt → delay → attempt.

Doporučení: maximální doba běhu check runu (`responseTimeout × attempts + retry.delay × retry.count`) by měla být výrazně kratší než `interval` checku. Jinak budou opakovaně aplikovány pravidla skip-if-running (viz 2.5.3).

Retry pokusy SHOULD být logovány alespoň na úrovni DEBUG. Log SHOULD obsahovat: `checkName`, číslo pokusu (attempt) a důvod retry (`TransportStatus`).

### 2.5.5 Status mapping

| TransportStatus | ValidationVerdict | MonitorStatus |
|---|---|---|
| `RESPONSE_RECEIVED` | `PASS` | `UP` |
| `RESPONSE_RECEIVED` | `WARN` | `WARN` |
| `RESPONSE_RECEIVED` | `FAIL` | `DOWN` |
| `TIMEOUT` | – | `DOWN` |
| `CONNECT_ERROR` | – | `DOWN` |
| `IO_ERROR` | – | `DOWN` |

Retry chování per `TransportStatus`:

| TransportStatus | Retry |
|---|---|
| `TIMEOUT` | SHOULD |
| `CONNECT_ERROR` | SHOULD |
| `IO_ERROR` | SHOULD NOT (default); MAY být zapnut konfigurací |
| `RESPONSE_RECEIVED` | nikdy |

### 2.5.6 Status ERROR (interní chyba monitoru)

Status `ERROR` nastane při interní chybě mimo transport a validaci:

- nekonfigurovaná service nebo check
- výjimka v monitor layer (NullPointerException apod.)
- výjimka ve validatoru → check run MUST skončit `ERROR`; implementace SHOULD zalogovat alias validatoru, který výjimku vyvolal
- nevalidní UTF-8 v template souboru → check run MUST skončit `ERROR`

`ERROR` se neukládá jako `DOWN` – je to signál chyby implementace, ne stavu služby.

---

## 2.6 Transport layer

### 2.6.1 Odpovědnost transport layer

Transport layer je tenká vrstva překladu mezi monitor modelem a `bedrock-wire-client`:

- sestaví `HttpRequest` z `MonitorRequest`
- zavolá `HttpClient.execute()`
- převede `HttpResponse` na `MonitorResult`
- zachytí výjimky z `bedrock-wire-client` a převede na `TransportStatus`

### 2.6.2 Mapování ServiceConfig → HttpClientConfig

Monitor layer vytváří `HttpClientConfig` z `ServiceConfig` pro registraci v `HttpClientRegistry`:

```java
HttpClientConfig clientConfig = HttpClientConfig.builder()
    .clientId(serviceConfig.getServiceName())
    .baseUrl(serviceConfig.getUrl())
    .connectionTimeout(serviceConfig.getConnectionTimeout())
    .responseTimeout(serviceConfig.getResponseTimeout())
    .tlsConfigName(serviceConfig.getTlsProfile())
    .build();
```

TLS profily monitoru (viz kap. 2.13) jsou mapovány na `TlsConfig` z Části 1.

### 2.6.3 Rozhraní transport layer

```java
public interface MonitorTransport {

    Mono<MonitorResult> execute(MonitorRequest request);

}
```

Transport implementace SHOULD zachytit transportní výjimky (timeout, connection error, IO error) a převést je na `MonitorResult` s odpovídajícím `TransportStatus`. `Mono.error` SHOULD být použit pouze pro interní chyby transport layer (např. chybná konfigurace).

`MonitorTransport` MUST být používán neblokujícím způsobem. Monitor layer SHOULD převést `Mono<MonitorResult>` na synchronní výsledek pouze na hranici scheduleru (např. `.block()` výhradně v executor thread, nikoli v reaktivním pipeline).

### 2.6.4 MonitorRequest

```java
public class MonitorRequest {

    String serviceName;           // pouze pro diagnostiku/logování
    HttpMethod method;
    URI url;
    Map<String, List<String>> headers;
    String body;

}
```

### 2.6.5 TransportStatus

```java
public enum TransportStatus {
    RESPONSE_RECEIVED,   // HTTP odpověď přijata (libovolný status kód)
    TIMEOUT,             // response timeout vypršel
    CONNECT_ERROR,       // nelze navázat spojení
    IO_ERROR             // IO chyba v průběhu komunikace (defaultně non-retryable)
}
```

### 2.6.6 MonitorResult

```java
public class MonitorResult {

    TransportStatus transportStatus;

    int httpStatus;              // 0 pokud transportStatus != RESPONSE_RECEIVED
    String responseBody;         // prázdný string pokud transportStatus != RESPONSE_RECEIVED
    Map<String, List<String>> headers; // prázdná mapa pokud transportStatus != RESPONSE_RECEIVED

    Throwable error;             // MAY být null; pouze pro diagnostiku v runtime; MUST NOT být serializováno
    String errorMessage;         // textová reprezentace chyby vhodná pro logování a serializaci

    Duration transportDuration;  // duration posledního HTTP attemptu, bez ohledu na výsledek

}
```

`transportDuration` reprezentuje dobu trvání posledního HTTP attemptu bez ohledu na to, zda skončil úspěšně nebo timeoutem. Pokud byl check run proveden s retry, dřívější attempty nejsou do `transportDuration` zahrnuty – hodnota vždy odpovídá pouze poslednímu attemptu.

Transport layer využívá transport client registry definovanou v kap. 1.5 . Transport target je definován v kap. 1.5.1  jako `scheme + host + port + tlsConfigName`. TLS konfigurace je aplikována při vytváření `SSLContext`.

---

## 2.7 Validation

### 2.7.1 Rozhraní Validator

```java
public interface Validator {

    String alias();

    ValidationResult validate(MonitorResult result, Map<String, String> params);

}
```

- `alias()` vrací identifikátor validatoru (shodný s hodnotou v `validation.validators`); alias MUST být porovnáván case-sensitive
- `params` obsahuje parametry z `validationParams` bez prefixu `validation.`

### 2.7.2 ValidationResult

```java
public class ValidationResult {

    ValidationVerdict verdict;
    String message;          // SHOULD obsahovat diagnostiku; MAY být null pro PASS

}
```

```java
public enum ValidationVerdict { PASS, WARN, FAIL }
```

Pravidla pro `ValidationResult.message`: hodnota SHOULD obsahovat diagnostiku validatoru (co selhalo a proč). Pokud více validátorů vrátí zprávu, implementace MAY agregovat zprávy nebo použít zprávu prvního `FAIL`, případně prvního `WARN`. Výsledná zpráva SHOULD být propagována do `MonitorExecutionResult.message`.

### 2.7.3 Sémantika běhu validátorů

Validátory jsou spouštěny v pořadí definovaném v `validation.validators`.

Pravidla výsledku:
- pokud alespoň jeden vrátí `FAIL` → celkový výsledek je `FAIL`
- pokud alespoň jeden vrátí `WARN` (a žádný `FAIL`) → celkový výsledek je `WARN`
- jinak → `PASS`

Validátory jsou spouštěny pouze pokud `transportStatus == RESPONSE_RECEIVED`.

Pravidla pro `ValidationResult.message`: hodnota SHOULD obsahovat diagnostiku validatoru. Pokud více validátorů vrátí zprávu, implementace MAY agregovat nebo použít zprávu prvního `FAIL`/`WARN`. Výsledná zpráva SHOULD být propagována do `MonitorExecutionResult.message`.

### 2.7.4 Built-in validators

| alias | parametr | chování |
|---|---|---|
| `httpStatus` | httpStatus | FAIL pokud status neodpovídá |
| `contains` | contains | FAIL pokud substring chybí (case-sensitive) |
| `regex` | regex | FAIL pokud regulární výraz neodpovídá responseBody |
| `maxDuration` | maxDuration | WARN pokud `MonitorResult.transportDuration` (duration posledního HTTP attemptu) překročí limit; vztahuje se pouze k jednomu attemptu a nezahrnuje celkovou `executionDuration` check runu včetně retry |
| `xpath` | xpath | FAIL pokud XPath výraz neodpovídá responseBody |

Parametry validátorů jsou předávány v mapě `validationParams` **bez prefixu `validation.`**.

Příklad:

```properties
monitor.check.test.validation.httpStatus = 200
monitor.check.test.validation.regex = <Result>OK</Result>
```

Provider převede na:

```
validationParams = { "httpStatus": "200", "regex": "<Result>OK</Result>" }
```

Prefix `validation.` existuje pouze v `.properties` formátu a není součástí runtime konfigurace.

Každý validátor čte pouze parametry odpovídající jeho aliasu. Custom validátory SHOULD používat vlastní prefix (např. `validation.myValidator.param`) aby nedocházelo ke kolizím. Hlavičky z `MonitorResult.headers` jsou dostupné custom validátorům.

Poznámky k `regex` validatoru:
- doporučená implementace používá `Pattern.matcher(responseBody).find()`
- výchozí Pattern flags nejsou nastaveny; pro DOTALL použít `(?s)`, pro MULTILINE `(?m)` inline ve výrazu
- implementace SHOULD cacheovat zkompilované `Pattern` instance per check konfiguraci (nikoliv per execution); kompilace regexu při každém check runu je zbytečně nákladná operace

---

## 2.8 Output model

### 2.8.1 MonitorExecutionResult

```java
public class MonitorExecutionResult {

    String checkName;
    String serviceName;

    Instant startedAt;
    Instant finishedAt;                // = startedAt + executionDuration
    Duration executionDuration;        // finishedAt - startedAt

    int attempts;
    String requestId;                  // UUID v4 generované při startu check runu

    MonitorStatus status;
    String message;  // 1) message z prvního FAIL validatoru; 2) jinak z prvního WARN validatoru; 3) jinak null

    MonitorResult transport;           // transport.transportDuration = duration posledního HTTP attemptu

}
```

Normativní definice `executionDuration`:

```
executionDuration = finishedAt - startedAt
```

`executionDuration` zahrnuje: dobu všech HTTP attemptů, retry prodlevy, čas validátorů a overhead monitor layer. `transportDuration` v přiloženém `MonitorResult` vždy reprezentuje výhradně poslední HTTP attempt (viz kap. 2.6.6).
```

```java
public enum MonitorStatus { UP, DOWN, WARN, ERROR }
```

### 2.8.2 Listener

```java
public interface MonitorResultListener {

    void onResult(MonitorExecutionResult result);

}
```

Listener může výsledek logovat, exportovat do metrik nebo publikovat do jiného systému.

#### Příklad implementace a registrace

```java
// Implementace listeneru
public class MetricsListener implements MonitorResultListener {
    @Override
    public void onResult(MonitorExecutionResult result) {
        metrics.record(result.getCheckName(), result.getStatus(), result.getExecutionDuration());
    }
}

// Registrace při startu monitoru
MonitorRuntime runtime = MonitorRuntime.builder()
    .configProvider(configProvider)
    .transport(transport)
    .listener(new MetricsListener())
    .listener(new AuditLogListener())
    .build();
```

Pravidlo: výjimka v listeneru MUST být zalogována, ale MUST NOT měnit status check runu. Listener je side-effect komponenta – jeho selhání neovlivňuje výsledek monitoru.

---

## 2.9 Templates a dynamické proměnné

### 2.9.1 Template soubor

Konfigurace:

```
monitor.check.<check>.templateFile = /path/to/template.xml
```

Pravidlo:
- pokud je `templateFile` definován → body se načte ze souboru
- pokud není definován → body je prázdný string

Template soubory MUST být čteny v kódování **UTF-8**. Pokud soubor není validní UTF-8, implementace SHOULD vyhodit výjimku a check run MUST skončit stavem `ERROR` (viz 2.5.6).

### 2.9.2 Parametry pro template

Substituce proměnných MUST být provedena jednou při sestavení těla požadavku (před odesláním). Parametry pro substituci ve formátu `$(název)`:

```
monitor.check.<check>.param.<název> = hodnota
```

### 2.9.3 Dynamické proměnné

Vestavěné dynamické proměnné:

| proměnná | hodnota |
|---|---|
| `$(uuid)` | náhodný UUID vygenerovaný per request |
| `$(timestamp)` | aktuální čas v ISO 8601 formátu, MUST být generován v UTC |

Implementace MAY rozšířit seznam dynamických proměnných.

---

## 2.10 Runtime komponenty

### 2.10.1 HttpClient lifecycle

Pro každou service MUST existovat **jedna instance WebClient** (resp. `HttpClient` z `bedrock-wire-client`), cacheovaná podle `serviceName` a znovu používaná pro všechny requesty dané service.

`HttpClient` MUST být získán z `HttpClientRegistry` (viz kap. 1.6, kap. 1). Vytvoření vlastního `HttpClient` mimo registry by vedlo ke vzniku duplicitních connection poolů.

Více `HttpClient` instancí může sdílet stejný connection pool, pokud mají shodný transport target.

### 2.10.2 Validator lifecycle

Implementace validátorů MUST být thread-safe a MUST být stateless. Validátor je instancován jednou a znovu používán pro všechny check runy. Instance validátoru MAY být sdílena mezi více paralelně běžícími check runy – implementace MUST být thread-safe.

Validátor MUST NOT ukládat stav mezi voláními `validate()`. Stav specifický pro check run MUST být předán parametrem `params`, nikoli jako instance proměnná. Porušení tohoto pravidla způsobuje těžko reprodukovatelné chyby při paralelním běhu checků.

### 2.10.3 Paralelní běh

Checky jsou spouštěny paralelně v thread poolu:

```
monitor.executor.poolSize = N
```

Executor MUST být singleton.

Doporučení pro sizing: `poolSize` by měl odpovídat počtu paralelně spouštěných checků. Příliš malý pool způsobí zpoždění spouštění a může nepřímo vyvolávat skip-if-running události.

### 2.10.4 Runtime configuration reload

Dynamický reload konfigurace za běhu není v této verzi podporován. Změna konfigurace vyžaduje restart komponenty. Toto omezení může být odstraněno v budoucích verzích.

---

## 2.11 Konfigurační reference

### 2.11.1 Service parametry

| parametr | typ | povinný | popis |
|---|---|---|---|
| `monitor.service.<name>.url` | URI | ano | base URL služby |
| `monitor.service.<name>.connectionTimeout` | Duration | ne | timeout pro navázání spojení |
| `monitor.service.<name>.responseTimeout` | Duration | ne | timeout pro HTTP odpověď |
| `monitor.service.<name>.header.<name>` | String | ne | HTTP hlavička |
| `monitor.service.<name>.tlsProfile` | String | ne | název TLS profilu (viz kap. 2.13) |

### 2.11.2 Check parametry

| parametr | typ | povinný | popis |
|---|---|---|---|
| `monitor.check.<name>.service` | String | ano | název service |
| `monitor.check.<name>.interval` | Duration | ano* | perioda spouštění; MUST být definován na úrovni checku, service nebo přes `monitor.default` |
| `monitor.check.<name>.method` | String | ne | HTTP metoda (`GET`, `POST`, `PUT`, `DELETE`, `HEAD`), default `POST`; hodnota je case-insensitive, implementace SHOULD normalizovat na uppercase |
| `monitor.check.<name>.path` | String | ne | cesta připojená k `service.url` |
| `monitor.check.<name>.query` | String | ne | query string (bez `?`) |
| `monitor.check.<name>.templateFile` | String | ne | cesta k template souboru |
| `monitor.check.<name>.responseTimeout` | Duration | ne | response timeout; podléhá lookup pravidlům – může být definován na úrovni check, service nebo `monitor.default` |
| `monitor.check.<name>.retry.count` | int | ne | počet retry pokusů, default `0` |
| `monitor.check.<name>.retry.delay` | Duration | ne | prodleva před retry, default `1s` |
| `monitor.check.<name>.header.<name>` | String | ne | HTTP hlavička (přebíjí service) |
| `monitor.check.<name>.validation.validators` | List | ne | seznam validátorů (comma-separated); whitespace kolem aliasů MUST být ignorován; duplicitní alias SHOULD být ignorován |
| `monitor.check.<name>.validation.<alias>` | String | ne | parametr validátoru |

### 2.11.3 Validation parametry (built-in)

| parametr | typ | popis |
|---|---|---|
| `httpStatus` | int | očekávaný HTTP status kód |
| `maxDuration` | Duration | WARN threshold pro transport duration |
| `contains` | String | očekávaný substring v response body |
| `regex` | String | regulární výraz aplikovaný na responseBody |
| `xpath` | String | XPath výraz aplikovaný na responseBody |

### 2.11.4 Executor parametry

| parametr | typ | povinný | popis |
|---|---|---|---|
| `monitor.executor.poolSize` | int | ne | velikost thread poolu, default `10` |

---

## 2.12 Kompletní příklad konfigurace

```properties
# === Global defaults ===
# platí pro všechny checky a services pokud není přepsáno
monitor.default.interval                 = 30s
monitor.default.responseTimeout          = 5s
monitor.default.connectionTimeout        = 3s
monitor.default.retry.count              = 1
monitor.default.retry.delay              = 2s
monitor.default.validation.validators    = httpStatus
# globální hlavičky (platí pro všechny checky; přepsatelné na úrovni service/check)
monitor.default.header.Accept            = application/xml
monitor.default.header.X-Client-Id       = bedrock-monitor

# === TLS profily ===
# payments-tls: mTLS s klientským certifikátem a vlastním trust store
monitor.tls.payments-tls.clientCert         = /certs/payments-client.p12
monitor.tls.payments-tls.clientCertPassword = secret
monitor.tls.payments-tls.clientCertType     = PKCS12
monitor.tls.payments-tls.trustStore         = /certs/payments-truststore.p12
monitor.tls.payments-tls.trustStorePassword = trustsecret
monitor.tls.payments-tls.trustStoreType     = PKCS12

# internal-tls: pouze vlastní trust store, bez klientského certifikátu
monitor.tls.internal-tls.trustStore         = /certs/internal-truststore.p12
monitor.tls.internal-tls.trustStorePassword = internalpass
monitor.tls.internal-tls.trustStoreType     = PKCS12

# === Services ===
# payments: přetěžuje responseTimeout z default, mTLS, service-level hlavička
monitor.service.payments.url                 = https://payments.example.com
monitor.service.payments.responseTimeout     = 10s
monitor.service.payments.tlsProfile          = payments-tls
monitor.service.payments.header.Content-Type = application/xml

# internal: kratší timeout, vlastní trust store, bez klientského certifikátu
monitor.service.internal.url                 = https://internal.example.com
monitor.service.internal.responseTimeout     = 3s
monitor.service.internal.tlsProfile          = internal-tls

# === Checks ===
# paymentsHealth: POST s template souborem, template parametry, rozšířená validace
monitor.check.paymentsHealth.service              = payments
monitor.check.paymentsHealth.method               = POST
monitor.check.paymentsHealth.path                 = /api/health
monitor.check.paymentsHealth.templateFile         = /templates/health-check.xml
# template parametry – dosazeny do proměnných v template souboru
monitor.check.paymentsHealth.param.clientId       = monitor-prod
monitor.check.paymentsHealth.param.region         = eu-west-1
monitor.check.paymentsHealth.validation.validators = httpStatus,contains
monitor.check.paymentsHealth.validation.httpStatus = 200
monitor.check.paymentsHealth.validation.contains   = <status>OK</status>

# paymentsMsg: procesní check, delší timeout, přetěžuje hlavičku, regex + maxDuration
monitor.check.paymentsMsg.service                 = payments
monitor.check.paymentsMsg.method                  = POST
monitor.check.paymentsMsg.path                    = /api/process
monitor.check.paymentsMsg.query                   = format=xml
monitor.check.paymentsMsg.templateFile            = /templates/process.xml
monitor.check.paymentsMsg.responseTimeout         = 15s
monitor.check.paymentsMsg.header.X-Priority       = high
monitor.check.paymentsMsg.validation.validators   = httpStatus,regex,maxDuration
monitor.check.paymentsMsg.validation.httpStatus   = 200
monitor.check.paymentsMsg.validation.regex        = <r>OK</r>
monitor.check.paymentsMsg.validation.maxDuration  = 8s

# internalPing: jednoduchý GET check, bez template, bez retry, JVM TLS (tlsProfile není nutný)
monitor.check.internalPing.service                = internal
monitor.check.internalPing.method                 = GET
monitor.check.internalPing.path                   = /ping
monitor.check.internalPing.interval               = 10s
monitor.check.internalPing.retry.count            = 0
monitor.check.internalPing.validation.validators  = httpStatus
monitor.check.internalPing.validation.httpStatus  = 200

# === Executor ===
monitor.executor.poolSize                = 5
```

---

## 2.13 TLS profily monitoru

### 2.13.1 Namespace TLS konfigurace

```
monitor.tls.<profileName>.*
```

### 2.13.2 Parametry TLS profilu

| parametr | typ | povinný | popis |
|---|---|---|---|
| `monitor.tls.<name>.clientCert` | Path | ne | cesta k souboru klientského certifikátu |
| `monitor.tls.<name>.clientCertPassword` | String | ne | heslo keystoru; vyžadováno pokud certifikát je chráněn heslem |
| `monitor.tls.<name>.clientCertType` | String | ne | `PKCS12` nebo `JKS` |
| `monitor.tls.<name>.clientCertAlias` | String | ne | alias v keystoru |
| `monitor.tls.<name>.trustStore` | Path | ne | vlastní truststore; null = JVM default |
| `monitor.tls.<name>.trustStorePassword` | String | ne | heslo truststore |
| `monitor.tls.<name>.trustStoreType` | String | ne | `PKCS12` nebo `JKS` |
| `monitor.tls.<name>.hostnameVerification` | boolean | ne | default `true` |

Pravidla:
- `clientCertPassword`, `clientCertType`, `clientCertAlias` jsou ignorovány, pokud `clientCert` není definován
- pokud `clientCert` je definován a certifikát vyžaduje heslo, ale `clientCertPassword` chybí → inicializace TLS MUST selhat (fail-fast)
- pokud certifikát heslo nevyžaduje, `clientCertPassword` je ignorován
- pokud `trustStore` není definován, použije se výchozí truststore JVM
- `hostnameVerification = false` MUST NOT být použito v produkci; implementace SHOULD zalogovat WARNING

### 2.13.3 Příklad konfigurace

Viz kompletní příklad v kap. 2.12 (profily `payments-tls` a `internal-tls`).

### 2.13.4 Runtime pravidla

TLS profily jsou mapovány na `TlsConfig` objekty (kap. 1.4.2) a registrovány v `HttpClientRegistry` při startu.

Výsledný mapping: `monitor.tls.<name>` → `TlsConfig(configName = <name>, ...)`

Transport target identity pro monitor odpovídá definici v kap. 1.5.1 : `scheme + host + port + tlsConfigName`, kde `tlsConfigName` odpovídá názvu TLS profilu monitoru.

---
