package nl.mijnoverheidzakelijk.ldv.repository;

import com.clickhouse.client.api.Client;
import com.clickhouse.data.ClickHouseFormat;
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader;
import org.apache.commons.configuration2.ex.ConfigurationException;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Repository encapsulating basic ClickHouse operations used by the exporter.
 */
public class ClickHouseRepository {
    private final Client client;

    /**
     * Creates a ClickHouse client using configuration values.
     *
     * @throws ConfigurationException if configuration cannot be read
     */
    public ClickHouseRepository() throws ConfigurationException {
        this.client = new Client.Builder()
                .addEndpoint(ConfigurationLoader.getValueByKey("logboekdataverwerking.clickhouse.endpoint", String.class))
                .setUsername(ConfigurationLoader.getValueByKey("logboekdataverwerking.clickhouse.username", String.class))
                .setPassword(ConfigurationLoader.getValueByKey("logboekdataverwerking.clickhouse.password", String.class))
                .setDefaultDatabase(ConfigurationLoader.getValueByKey("logboekdataverwerking.clickhouse.database", String.class))
                .build();

    }

    /**
     * Ensures that the target table exists with the expected schema.
     *
     * @throws ConfigurationException if the table name cannot be resolved
     * @throws RuntimeException       if the DDL operation fails
     */
    public void ensureSchema() throws ConfigurationException {
        String table = ConfigurationLoader.getValueByKey("logboekdataverwerking.clickhouse.table", String.class);
        try {
            // Schema matching SpanData structure (camelCase)
            client.query("CREATE TABLE IF NOT EXISTS " + table + " (\n" +
                            "    traceId String,\n" +
                            "    spanId String,\n" +
                            "    status String,\n" +
                            "    name String,\n" +
                            "    startTime Int64,\n" +
                            "    endTime Int64,\n" +
                            "    parentSpanId String,\n" +
                            "    attributes Map(String, String),\n" +
                            "    resource Map(String, String)\n" +
                            ")\n" +
                            "ENGINE = MergeTree()\n" +
                            "ORDER BY (traceId, spanId);")
                    .get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Failed to ensure ClickHouse schema", e);
        }
    }

    /**
     * Inserts a JSON payload into the specified table.
     *
     * @param table              the target table name
     * @param jsonEachRowPayload payload where each line is a JSON object
     * @throws RuntimeException if the insert fails
     */
    public void insertJsonEachRow(String table, String jsonEachRowPayload) {
        try {
            InputStream data = new ByteArrayInputStream(jsonEachRowPayload.getBytes(StandardCharsets.UTF_8));
            client.insert(table, data, ClickHouseFormat.JSONEachRow).get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to insert into ClickHouse", e);
        }
    }

}
