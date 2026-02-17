package nl.mijnoverheidzakelijk.ldv.repository

import com.clickhouse.client.api.Client
import com.clickhouse.data.ClickHouseFormat
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader
import org.apache.commons.configuration2.ex.ConfigurationException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Repository encapsulating basic ClickHouse operations used by the exporter.
 */
class ClickHouseRepository {
    private val client: Client = Client.Builder()
        .addEndpoint(ConfigurationLoader.clickhouseEndpoint)
        .setUsername(ConfigurationLoader.clickhouseUsername)
        .setPassword(ConfigurationLoader.clickhousePassword)
        .setDefaultDatabase(ConfigurationLoader.clickhouseDatabase)
        .build()

    /**
     * Ensures that the target table exists with the expected schema.
     * 
     * @throws ConfigurationException if the table name cannot be resolved
     * @throws RuntimeException       if the DDL operation fails
     */
    @Throws(ConfigurationException::class)
    fun ensureSchema() {
        val table = ConfigurationLoader.clickhouseTable
        try {
            // Schema matching SpanData structure (camelCase)
            client.query(
                """
                CREATE TABLE IF NOT EXISTS $table (
                    traceId String,
                    spanId String,
                    status String,
                    name String,
                    startTime Int64,
                    endTime Int64,
                    parentSpanId String,
                    attributes Map(String, String),
                    resource Map(String, String)
                )
                ENGINE = MergeTree()
                ORDER BY (traceId, spanId);
                """.trimIndent()
            ).get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw RuntimeException("Failed to ensure ClickHouse schema", e)
        }
    }

    /**
     * Inserts a JSON payload into the specified table.
     *
     * @param table              the target table name
     * @param jsonEachRowPayload payload where each line is a JSON object
     * @throws RuntimeException if the insert fails
     */
    fun insertJsonEachRow(table: String, jsonEachRowPayload: String) {
        try {
            val data: InputStream = ByteArrayInputStream(jsonEachRowPayload.toByteArray(StandardCharsets.UTF_8))
            client.insert(table, data, ClickHouseFormat.JSONEachRow).get()
        } catch (e: Exception) {
            throw RuntimeException("Failed to insert into ClickHouse", e)
        }
    }
}
