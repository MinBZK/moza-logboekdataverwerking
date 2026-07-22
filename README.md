# Logboek Dataverwerkingen JVM implementatie

![Project Pre-Alpha Status](https://img.shields.io/badge/life_cycle-pre_alpha-red)
[![OpenSSF Scorecard](https://api.scorecard.dev/projects/github.com/MinBZK/moza-logboekdataverwerking/badge)](https://scorecard.dev/viewer/?uri=github.com/MinBZK/moza-logboekdataverwerking)

Dit is een Kotlin implementatie van de - in ontwikkeling zijnde - standaard Logboek Dataverwerkingen (LDV) van Logius. De library is bruikbaar vanuit zowel Kotlin als Java projecten.

## Inleiding

Vanuit het programma MijnOverheid Zakelijk sluiten we zoveel mogelijk aan op de standaarden uit het stelsel Generieke Digitale Infrastructuur: https://www.digitaleoverheid.nl/mido/generieke-digitale-infrastructuur-gdi/. Een van de onderdelen daarvan is de standaard Logboek Dataverwerkingen van Logius: Voor meer informatie over de LDV standaard, zie: https://github.com/Logius-standaarden/logboek-dataverwerkingen.

## Doel

Dit Open Source project is opgezet om de LDV standaard eenvoudig aan nieuwe of bestaande Java/Kotlin oplossingen toe te voegen.

## Afhankelijkheden

- **Clickhouse of PostgreSQL database** - Voor het opslaan van de logging wordt standaard ClickHouse gebruikt: https://clickhouse.com/. ClickHouse is geoptimaliseerd voor zeer grote volumes. Organisaties die liever PostgreSQL beheren, kunnen dat als backend kiezen via `logboekdataverwerking.dbms=postgresql` (zie hieronder).
- **Verwerkingsactiviteiten register** - Bij het loggen van de activiteit wordt verwezen naar een ID van een verwerkingsactiviteit in een activiteiten register. Meer informatie hierover is te vinden in de documentatie van de standaard. Hierbij wordt geen richtlijn opgegeven voor de technische implementatie en deze is daarom niet inbegrepen bij deze implementatie.

## Hoe te gebruiken

Om deze package te gebruiken moet je in je (maven) project de volgende variablen in je `application.properties` file toevoegen:

```properties
logboekdataverwerking.enabled=true
logboekdataverwerking.service-name=service-name

# Database backend: 'clickhouse' (standaard) of 'postgresql'.
logboekdataverwerking.dbms=clickhouse

# Optionele OpenTelemetry resource-attributen
# Worden alleen toegevoegd aan de standalone OpenTelemetry resource;
# in een Quarkus-container met quarkus-opentelemetry komen deze uit de Quarkus-config.
logboekdataverwerking.service-version=1.0.0
logboekdataverwerking.deployment-environment=production

# Span processor: 'simple' (standaard, aanbevolen) of 'batch' (afgeraden, niet conform de acknowledgement-MUST).
# Zie 'Span processor en acknowledgement' hieronder voor de trade-off.
logboekdataverwerking.span-processor=simple

# Wat te doen als het Logboek een schrijfactie weigert: 'fail-closed' (standaard) of 'fail-open'.
# Zie 'Span processor en acknowledgement' hieronder.
logboekdataverwerking.write-failure-policy=fail-closed
```

Configureer daarnaast **alleen de backend die je bij `dbms` koos** — niet beide. Bij `dbms=clickhouse`:

```properties
logboekdataverwerking.clickhouse.endpoint=http://localhost:8123
logboekdataverwerking.clickhouse.username=user
logboekdataverwerking.clickhouse.password=password
logboekdataverwerking.clickhouse.database=db_name
logboekdataverwerking.clickhouse.table=table_name
# Optioneel: time-out (seconden) voor ClickHouse-queries en -inserts. Standaard 30.
logboekdataverwerking.clickhouse.query-timeout-seconds=30
```

Of, bij `dbms=postgresql`:

```properties
logboekdataverwerking.postgresql.url=jdbc:postgresql://localhost:5432/ldv_logging
logboekdataverwerking.postgresql.username=user
logboekdataverwerking.postgresql.password=password
logboekdataverwerking.postgresql.table=spans
# Optioneel: time-out (seconden) voor het controleren of de verbinding nog actief is. Standaard 5.
logboekdataverwerking.postgresql.connection-validation-timeout-seconds=5
```

of `application.yml` (hier met `dbms: clickhouse`; vervang het `clickhouse`-blok door een `postgresql`-blok bij `dbms: postgresql`):

```yaml
logboekdataverwerking:
    enabled: true
    service-name: service-name
    service-version: 1.0.0
    deployment-environment: production
    span-processor: simple
    write-failure-policy: fail-closed
    dbms: clickhouse
    clickhouse:
        endpoint: http://localhost:8123
        username: user
        password: password
        database: db_name
        table: table_name
        query-timeout-seconds: 30
```

Als `enabled=true` is, valideert de library bij applicatiestart dat alle properties van de gekozen backend aanwezig en niet-leeg zijn (`clickhouse.*` bij `dbms=clickhouse`, `postgresql.*` bij `dbms=postgresql`). Ontbrekende of lege waarden geven een `IllegalStateException` met een lijst van de missende keys, in plaats van pas bij de eerste export te falen.

PostgreSQL is een alternatieve backend voor ClickHouse, bruikbaar waar PostgreSQL operationeel beter past. De `attributes`- en `resource`-velden worden opgeslagen als `jsonb`-kolommen.

De JDBC-drivers van beide backends zijn in deze library als `optional` gemarkeerd: ze komen niet transitief mee, zodat je applicatie alléén de driver van de gekozen backend hoeft te declareren (`com.clickhouse:client-v2` óf `org.postgresql:postgresql`). Kies je een backend zonder de bijbehorende driver, dan faalt de applicatie luid bij start (zie de config-validatie hierboven).

De lokale databases draaien achter een Compose-profiel, zodat je alleen de gekozen backend start:

```bash
docker compose --profile clickhouse up -d    # standaard backend
docker compose --profile postgresql up -d    # alternatieve backend
```

> **Let op (geldt voor beide backends):** Bij een mislukte export worden de betreffende spans niet opnieuw aangeboden — geen enkele OpenTelemetry-spanprocessor (`batch` of `simple`) probeert een mislukte export opnieuw. De standaardcombinatie `span-processor=simple` + `write-failure-policy=fail-closed` voldoet aan de LDV-acknowledgement-eis: de applicatie ziet synchroon of de logregel is opgeslagen en laat de verwerking falen als dat niet zo is. Dat garandeert geen opslag bij een databasestoring, maar maakt een mislukking wél direct zichtbaar. Zet je `span-processor=batch`, dan weet de applicatie niet óf de opslag is geslaagd en degradeert `fail-closed` tot log-only.

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

De `LogboekInterceptor` extraheert automatisch inkomende `traceparent`/`tracestate` headers, zodat een verwerking die door een andere organisatie is gestart in het eigen Logboek wordt voortgezet onder hetzelfde `trace_id`. Geneste `@Logboek`-acties krijgen de omsluitende actie als parent; alleen de buitenste actie neemt de inkomende `traceparent` over.

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

De LDV-standaard stelt dat de applicatie moet kunnen weten dat een logregel daadwerkelijk is opgeslagen. Dit wordt bepaald door twee instellingen die samenwerken.

**`span-processor`** kiest de OpenTelemetry-processor:

- **`simple`** (standaard, en de aanbevolen keuze): `SimpleSpanProcessor`. Elke span wordt synchroon geëxporteerd; de applicatie wacht op de bevestiging van de database voordat de request afrondt. Conform de acknowledgement-MUST, maar verhoogt p99-latency en koppelt request-doorlooptijd aan de beschikbaarheid van het Logboek.
- **`batch`** (afgeraden): `BatchSpanProcessor`. Spans worden asynchroon in batches geëxporteerd. De applicatie keert terug naar de aanroeper vóórdat de export bevestigd is, dus **`batch` voldoet niet aan de acknowledgement-MUST**: bij een JVM-crash tussen response en flush gaat de logregel stil verloren, en `fail-closed` degradeert tot log-only.

Kies je tóch `batch`, doe dat dan als bewuste, gedocumenteerde afweging. De situaties die dat rechtvaardigen zijn een hoog verwerkingsvolume of acties met veel betrokkenen: de standaard vereist een aparte logregel per betrokkene, dus onder `simple` doet een verwerking met N betrokkenen N+1 synchrone inserts binnen de request. Bij ClickHouse telt daarbij dat de engine op grote, gebundelde inserts is ontworpen; veel kleine losse inserts leggen merge-druk op de MergeTree-tabel.

**`write-failure-policy`** bepaalt wat er gebeurt als de database een schrijfactie weigert:

- **`fail-closed`** (standaard): bij een schrijffout gooit de interceptor een `LogboekWriteException`, zodat een verwerking niet als afgerond-en-gelogd geldt terwijl de logregel niet is opgeslagen. Dit is de strikte lezing van de acknowledgement-MUST en koppelt het slagen van een verwerking aan de beschikbaarheid van het Logboek.
- **`fail-open`**: de schrijffout wordt gelogd (SEVERE) en de verwerking gaat door.

Afdwingen van `fail-closed` werkt alleen op de synchrone `simple`-processor: daar draait de export op dezelfde thread als de request, vlak voor het einde van de verwerking. Onder `batch` gebeurt de export op een achtergrond-thread en degradeert het beleid tot log-only. Alleen de standaardcombinatie `simple` + `fail-closed` voldoet aan de acknowledgement-MUST.

Gaat een export mis, dan wordt zo veel mogelijk gered: het mappen van een span naar een databaserij gebeurt per span, dus één onverwerkbare span laat de rest van de batch niet sneuvelen. De insert zelf is wél alles-of-niets — een half weggeschreven batch is een niet te interpreteren logregel. In beide gevallen levert verlies een mislukte export op (dus `fail-closed` slaat aan) en worden de `trace_id:span_id` van de verloren logregels op SEVERE gelogd.

### Foutdetails en dataminimalisatie

Error-logregels krijgen altijd `exception.type` en `exception.message`. De volledige `exception.stacktrace` wordt alleen opgeslagen als `logboekdataverwerking.log-exception-stacktrace=true`; standaard staat dit uit, omdat stacktraces groot zijn en persoonsgegevens kunnen bevatten (dataminimalisatie, AVG art. 5(1)(c)).

### Sampling

LDV-spans gebruiken altijd een eigen, toegewijde OpenTelemetry-SDK met een `AlwaysOn`-sampler, ook wanneer de host-applicatie zelf een OpenTelemetry-SDK levert (bijv. `quarkus-opentelemetry`). Dit voorkomt dat logregels worden weggesampled door de sampler van de host of door een inkomende `traceparent` met sampled-flag `0`, wat in strijd zou zijn met de LDV-eis dat Log Sampling niet is toegestaan. De toegewijde SDK wordt niet globaal geregistreerd en bestaat naast een eventuele host-SDK; tracecontext blijft propageren omdat de W3C-propagator en OTel-`Context` SDK-onafhankelijk zijn.

### Meerdere betrokkenen

De standaard vereist een aparte logregel per betrokkene. Voor het enkelvoudige geval zet je `dataSubjectId`/`dataSubjectType` op de `LogboekContext`. Verwerk je meerdere betrokkenen in één actie (bijv. een batch), gebruik dan `logboekContext.addSubject(id, type)` per betrokkene: de interceptor maakt dan één child-logregel per betrokkene onder de actie-span, met hetzelfde `trace_id`.

### Contextvalidatie en het exception-pad

Validatie breekt de verwerking nooit (LDV-standaard, foutafhandeling): ontbreekt `processing_activity_id`, ontbreekt een betrokkene of is de activity-id geen absolute URI, dan wordt dit als WARNING gelogd (met `trace_id:span_id`) en wordt de logregel geëxporteerd met de attributen die wél aanwezig zijn. Een lege `@Logboek`-naam valt terug op de methodenaam. Een logregel zonder betrokkene is toegestaan (de standaard staat 0 of 1 betrokkenen per logregel toe, bijvoorbeeld bij verwerkingen zonder persoonsgegevens); de warning helpt om vergeten context snel op te sporen.

Gooit de geïntercepteerde methode zelf een exceptie, dan wordt de logregel geëxporteerd met status ERROR en de `exception.*`-attributen; bij meerdere betrokkenen krijgen ook de child-logregels status ERROR. De oorspronkelijke exceptie wordt nooit gemaskeerd door een fout uit de logging zelf.
