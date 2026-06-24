# Logboek Dataverwerkingen JVM implementatie

![Project Pre-Alpha Status](https://img.shields.io/badge/life_cycle-pre_alpha-red)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/MinBZK/moza-logboekdataverwerking/badge)](https://scorecard.dev/viewer/?uri=github.com/MinBZK/moza-logboekdataverwerking)

Dit is een Kotlin implementatie van de - in ontwikkeling zijnde - standaard Logboek Dataverwerkingen (LDV) van Logius. De library is bruikbaar vanuit zowel Kotlin als Java projecten.

## Inleiding

Vanuit het programma MijnOverheid Zakelijk sluiten we zoveel mogelijk aan op de standaarden uit het stelsel Generieke Digitale Infrastructuur: https://www.digitaleoverheid.nl/mido/generieke-digitale-infrastructuur-gdi/. Een van de onderdelen daarvan is de standaard Logboek Dataverwerkingen van Logius: Voor meer informatie over de LDV standaard, zie: https://github.com/Logius-standaarden/logboek-dataverwerkingen.

## Doel

Dit Open Source project is opgezet om de LDV standaard eenvoudig aan nieuwe of bestaande Java/Kotlin oplossingen toe te voegen.

## Afhankelijkheden

- **Clickhouse of PostgreSQL database** - Voor het opslaan van de logging wordt standaard een Clickhouse database gebruikt: https://clickhouse.com/. Clickhouse is een vrij zware tool; voor ontwikkeldoeleinden kan ook PostgreSQL worden gebruikt via `logboekdataverwerking.dbms=postgresql` (zie hieronder).
- **Verwerkingsactiviteiten register** - Bij het loggen van de activiteit wordt verwezen naar een ID van een verwerkingsactiviteit in een activiteiten register. Meer informatie hierover is te vinden in de documentatie van de standaard. Hierbij wordt geen richtlijn opgegeven voor de technische implementatie en deze is daarom niet inbegrepen bij deze implementatie.

## Hoe te gebruiken

Om deze package te gebruiken moet je in je (maven) project de volgende variablen in je `application.properties` file toevoegen:

```properties
logboekdataverwerking.enabled=true
logboekdataverwerking.service-name=service-name

# Database backend: 'clickhouse' (standaard) of 'postgresql'.
logboekdataverwerking.dbms=clickhouse

# ClickHouse (gebruikt wanneer dbms=clickhouse)
logboekdataverwerking.clickhouse.endpoint=http://localhost:8123
logboekdataverwerking.clickhouse.username=user
logboekdataverwerking.clickhouse.password=password
logboekdataverwerking.clickhouse.database=db_name
logboekdataverwerking.clickhouse.table=table_name

# PostgreSQL (gebruikt wanneer dbms=postgresql) - lichter alternatief voor ontwikkeldoeleinden
logboekdataverwerking.postgresql.url=jdbc:postgresql://localhost:5432/ldv_logging
logboekdataverwerking.postgresql.username=user
logboekdataverwerking.postgresql.password=password
logboekdataverwerking.postgresql.table=spans
# Optioneel: timeout (seconden) voor connection-liveness checks. Standaard 5.
logboekdataverwerking.postgresql.connection-validation-timeout-seconds=5

# Optionele OpenTelemetry resource-attributen
# Worden alleen toegevoegd aan de standalone OpenTelemetry resource;
# in een Quarkus-container met quarkus-opentelemetry komen deze uit de Quarkus-config.
logboekdataverwerking.service-version=1.0.0
logboekdataverwerking.deployment-environment=production

# Span processor: 'batch' (standaard) of 'simple'.
# Zie 'Span processor en acknowledgement' hieronder voor de trade-off.
logboekdataverwerking.span-processor=batch
```

of `application.yml`:

```yaml
logboekdataverwerking:
    enabled: true
    service-name: service-name
    service-version: 1.0.0
    deployment-environment: production
    span-processor: batch
    dbms: clickhouse
    clickhouse:
        endpoint: http://localhost:8123
        username: user
        password: password
        database: db_name
        table: table_name
    postgresql:
        url: jdbc:postgresql://localhost:5432/ldv_logging
        username: user
        password: password
        table: spans
        connection-validation-timeout-seconds: 5
```

Als `enabled=true` is, valideert de library bij applicatiestart dat alle properties van de gekozen backend aanwezig en niet-leeg zijn (`clickhouse.*` bij `dbms=clickhouse`, `postgresql.*` bij `dbms=postgresql`). Ontbrekende of lege waarden geven een `IllegalStateException` met een lijst van de missende keys, in plaats van pas bij de eerste export te falen.

PostgreSQL is een lichter alternatief voor ClickHouse, bedoeld voor ontwikkeldoeleinden. De `attributes`- en `resource`-velden worden opgeslagen als `jsonb`-kolommen. Start een lokale PostgreSQL met `docker-compose -f compose.yml up -d postgresql`.

> **Let op:** De PostgreSQL-backend is bedoeld voor ontwikkeling, niet voor productie. Bij een mislukte export worden de betreffende spans niet opnieuw aangeboden — geen enkele OpenTelemetry-spanprocessor (`batch` of `simple`) herhaalt een mislukte export. Met de standaard `batch`-processor weet de applicatie bovendien niet óf de opslag is gelukt. Voor een verwerkingenlogboek conform de LDV-acknowledgement-eis: gebruik `span-processor=simple`, zodat de applicatie synchroon ziet of de logregel is opgeslagen. Dit garandeert geen opslag bij een DB-storing, alleen dat falen direct zichtbaar is. Gebruik in productie ClickHouse.

Hierna kun je endpoints voorzien van de `@Logboek()` annotatie:

`@Logboek(name = "behandelen-aanvraag", processingActivityId = "1234")`

Hierbij is `name` de beschrijving van je eigen trace log en `processingActivityId` is de verwijzing naar een Register met meer informatie over de Verwerkingsactiviteit.

Daarnaast kan er in de betreffende functie extra informatie aan de Span worden toegevoegd:

**Kotlin:**
```kotlin
@Inject
lateinit var handler: ProcessingHandler

@Inject
lateinit var logboekContext: LogboekContext

@GET
@Path("/{identificatieType}/{identificatieNummer}")
@Logboek(name = "test", processingActivityId = "1")
fun test(): Response {
    val innerSpan = handler.startSpan("span-2", null)
    val innerContext = LogboekContext().apply {
        status = StatusCode.ERROR
        dataSubjectId = "123"
        dataSubjectType = "BSN"
        processingActivityId = "4321"
    }
    handler.addLogboekContextToSpan(innerSpan, innerContext)
    innerSpan.end()

    logboekContext.dataSubjectId = "000000000"
    logboekContext.dataSubjectType = "KVK"
    logboekContext.status = StatusCode.OK

    return Response.ok("Hello world").build()
}
```

**Java:**
```java
@Inject
ProcessingHandler handler;

@Inject
LogboekContext logboekContext;

@GET
@Path("/{identificatieType}/{identificatieNummer}")
@Logboek(name = "test", processingActivityId = "1")
public Response test() {
    var innerSpan = handler.startSpan("span-2", null);
    LogboekContext innerContext = new LogboekContext();
    innerContext.setStatus(StatusCode.ERROR);
    innerContext.setDataSubjectId("123");
    innerContext.setDataSubjectType("BSN");
    innerContext.setProcessingActivityId("4321");
    handler.addLogboekContextToSpan(innerSpan, innerContext);
    innerSpan.end();

    logboekContext.setDataSubjectId("000000000");
    logboekContext.setDataSubjectType("KVK");
    logboekContext.setStatus(StatusCode.OK);

    return Response.ok("Hello world").build();
}
```
### Uitschakelen tijdens testen

Om de database en OpenTelemetry functionaliteit uit te schakelen tijdens testen, stel je `logboekdataverwerking.enabled=false` in je test configuratie bestand:

**test/resources/application.properties:**
```
logboekdataverwerking.enabled=false
```

Wanneer uitgeschakeld, worden er geen verbindingen met de database gemaakt.

### Cross-organisatie trace context (W3C Trace Context)

De `LogboekInterceptor` extraheert automatisch inkomende `traceparent`/`tracestate` headers, zodat een verwerking die door een andere organisatie is gestart in het eigen Logboek wordt voortgezet onder hetzelfde `trace_id`.

Voor de andere richting (uitgaande calls vanuit deze service naar een andere organisatie) registreer je `LogboekClientRequestFilter` op je JAX-RS / MicroProfile REST clients. De filter injecteert `traceparent` op elke uitgaande request op basis van de actieve OpenTelemetry-context:

**Kotlin:**
```kotlin
import nl.mijnoverheidzakelijk.ldv.client.LogboekClientRequestFilter
import org.eclipse.microprofile.rest.client.annotation.RegisterProvider
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient

@Path("/api")
@RegisterRestClient(configKey = "andere-organisatie")
@RegisterProvider(LogboekClientRequestFilter::class)
interface AndereOrganisatieClient { /* ... */ }
```

**Java:**
```java
@Path("/api")
@RegisterRestClient(configKey = "andere-organisatie")
@RegisterProvider(LogboekClientRequestFilter.class)
public interface AndereOrganisatieClient { /* ... */ }
```

Of programmatisch:

```kotlin
val client = ClientBuilder.newClient().register(LogboekClientRequestFilter::class.java)
```

#### dpl.core.foreign_operation.processor

Het LDV-attribuut `dpl.core.foreign_operation.processor` identificeert de andere partij in een cross-organisatie verwerking en hoort gezet te worden op de **uitgaande** kant: door applicatiecode op de actieve span, met de URL of identifier van de externe service. De interceptor zet dit attribuut niet automatisch; alleen de tracecontext wordt voor je gepropageerd.

### Span processor en acknowledgement

De LDV-standaard stelt dat de applicatie moet kunnen weten dat een logregel daadwerkelijk is opgeslagen. Met OpenTelemetry zijn er twee gangbare processors:

- **`batch`** (standaard): `BatchSpanProcessor`. Spans worden asynchroon in batches geëxporteerd. Hoogste throughput, laagste request-latency, maar de applicatie keert terug naar de aanroeper voordat de export bevestigd is. Bij een JVM-crash tussen response en flush kan een logregel verloren gaan.
- **`simple`**: `SimpleSpanProcessor`. Elke span wordt synchroon geëxporteerd; de applicatie wacht op de bevestiging van ClickHouse voordat de request afrondt. Strikter conform de spec, maar verhoogt p99-latency en koppelt request-doorlooptijd aan de beschikbaarheid van het Logboek.

Kies bewust per omgeving. Voor een initiële implementatie volstaat `batch`; overweeg `simple` zodra het Logboek productiekritisch en hoog-beschikbaar is.