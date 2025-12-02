# Logboek Dataverwerkingen Java implementatie

Dit is een Java implementatie van de - in ontwikkeling zijnde - standaard Logboek Dataverwerkingen (LDV) van Logius.

## Inleiding

Vanuit het programma MijnOverheid Zakelijk sluiten we zoveel mogelijk aan op de standaarden uit het stelsel Generieke Digitale Infrastructuur: https://www.digitaleoverheid.nl/mido/generieke-digitale-infrastructuur-gdi/. Een van de onderdelen daarvan is de standaard Logboek Dataverwerkingen van Logius: Voor meer informatie over de LDV standaard, zie: https://github.com/Logius-standaarden/logboek-dataverwerkingen.

## Doel

Dit Open Source project is opgezet om de LDV standaard eenvoudig aan nieuwe of bestaande Java oplossingen toe te voegen.

## Afhankelijkheden

- **Clickhouse database** - Deze implementatie is gemaakt met een Clickhouse database voor het opslaan van de logging: https://clickhouse.com/
- **Verwerkingsactiviteiten register** - Bij het loggen van de activiteit wordt verwezen naar een ID van een verwerkingsactiviteit in een activiteiten register. Meer informatie hierover is te vinden in de documentatie van de standaard. Hierbij wordt geen richtlijn opgegeven voor de technische implementatie en deze is daarom niet inbegrepen bij deze implementatie.

## Hoe te gebruiken

Om deze package te gebruiken moet je in je (maven) project de volgende variablen in je `application.properties` file toevoegen:
```
logboekdataverwerking.enabled=true
logboekdataverwerking.service-name=service-name
logboekdataverwerking.clickhouse.endpoint=http://localhost:8123
logboekdataverwerking.clickhouse.username=user
logboekdataverwerking.clickhouse.password=password
logboekdataverwerking.clickhouse.database=db_name
logboekdataverwerking.clickhouse.table=table_name
```

of `application.yml`:
```
logboekdataverwerking:
    enabled: true
    service-name: service-name
    clickhouse:
        endpoint: http://localhost:8123
        username: user
        password: password
        database: db_name
        table: table_name
```

Hierna kun je endpoints voorzien van de `@Logboek()` annotatie:

`@Logboek(name = "behandelen-aanvraag", processingActivityId = "1234")`

Hierbij is `name` de beschrijving van je eigen trace log en `processingActivityId` is de verwijzing naar een Register met meer informatie over de Verwerkingsactiviteit.

Daarnaast kan er in de betreffende functie extra informatie aan de Span worden toegevoegd:

```java
    var innerSpan = handler.startSpan("span-2", null);
    Thread.sleep(1000);
    LogboekContext innerContext = new LogboekContext();
    innerContext.setStatus(StatusCode.ERROR);
    innerContext.setDataSubjectId("123");
    innerContext.setDataSubjectType("BSN");
    innerContext.setProcessingActivityId("4321");
    innerSpan.end();
```

### Uitschakelen tijdens testen

Om de database en OpenTelemetry functionaliteit uit te schakelen tijdens testen, stel je `logboekdataverwerking.enabled=false` in je test configuratie bestand:

**test/resources/application.properties:**
```
logboekdataverwerking.enabled=false
```

Wanneer uitgeschakeld, worden er geen verbindingen met de database gemaakt.



## TODO's

- We zoeken nog een overheidsbreed beschikbare Maven repository voor het hosten van de package, zodat developers deze eenvoudig kunnen importeren in hun Java oplossingen.
