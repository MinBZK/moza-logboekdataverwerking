Om deze package te gebruiken moet je in je (maven) project de volgende variablen in je `application.properties` file toevoegen:
```
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
    service-name: service-name
    clickhouse:
        endpoint: http://localhost:8123
        username: user
        password: password
        database: db_name
        table: table_name
```