package nl.mijnoverheidzakelijk.ldv.repository

import com.clickhouse.client.api.Client
import com.clickhouse.data.ClickHouseFormat
import com.fasterxml.jackson.databind.ObjectMapper
import io.opentelemetry.api.trace.SpanId
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader
import nl.mijnoverheidzakelijk.ldv.exporter.SpanRow
import org.apache.commons.configuration2.ex.ConfigurationException
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Repository encapsulating basic ClickHouse operations used by the exporter.
 */
class ClickHouseRepository(
    private val objectMapper: ObjectMapper = ObjectMapper(),
) : SpanRepository {
    private val table: String = ConfigurationLoader.clickhouseTable
    private val client: Client = Client.Builder()
        .addEndpoint(ConfigurationLoader.clickhouseEndpoint)
        .setUsername(ConfigurationLoader.clickhouseUsername)
        .setPassword(ConfigurationLoader.clickhousePassword)
        .setDefaultDatabase(ConfigurationLoader.clickhouseDatabase)
        .build()

    init {
        requireValidTableName(table)
    }

    /**
     * Ensures that the target table exists with the expected schema.
     *
     * @throws ConfigurationException if the table name cannot be resolved
     * @throws RuntimeException       if the DDL operation fails
     */
    @Throws(ConfigurationException::class)
    override fun ensureSchema() {
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
     * Serializes the rows to a JSONEachRow payload and inserts them.
     *
     * The on-the-wire JSON deliberately mirrors the ClickHouse table columns: a
     * root span's `parentSpanId` is written as the all-zero invalid id (the
     * ClickHouse column is non-nullable `String`), even though [SpanRow] models
     * it as `null`. The remaining fields map one-to-one.
     *
     * @param rows rows produced by [nl.mijnoverheidzakelijk.ldv.exporter.SpanMapper]
     * @throws RuntimeException if serialization or the insert fails
     */
    override fun insert(rows: List<SpanRow>) {
        if (rows.isEmpty()) return

        val payload = StringBuilder()
        for (row in rows) {
            payload.append(objectMapper.writeValueAsString(toJsonMap(row))).append("\n")
        }
        insertJsonEachRow(payload.toString())
    }

    /** Maps a [SpanRow] to the JSONEachRow object whose keys match the ClickHouse columns. */
    private fun toJsonMap(row: SpanRow): Map<String, Any> = mapOf(
        "traceId" to row.traceId,
        "spanId" to row.spanId,
        "status" to row.status.name,
        "name" to row.name,
        "startTime" to row.startTime,
        "endTime" to row.endTime,
        "parentSpanId" to (row.parentSpanId ?: SpanId.getInvalid()),
        "attributes" to row.attributes,
        "resource" to row.resource,
    )

    /**
     * Inserts a JSON payload into the configured table.
     *
     * @param jsonEachRowPayload payload where each line is a JSON object
     * @throws RuntimeException if the insert fails
     */
    fun insertJsonEachRow(jsonEachRowPayload: String) {
        try {
            val data: InputStream = ByteArrayInputStream(jsonEachRowPayload.toByteArray(StandardCharsets.UTF_8))
            client.insert(table, data, ClickHouseFormat.JSONEachRow).get()
        } catch (e: Exception) {
            throw RuntimeException("Failed to insert into ClickHouse", e)
        }
    }

    override fun close() {
        client.close()
    }

    companion object {
        private val TABLE_NAME_PATTERN = Regex("^[a-zA-Z_][a-zA-Z0-9_.]*$")

        private fun requireValidTableName(table: String) {
            require(TABLE_NAME_PATTERN.matches(table)) {
                "Invalid table name: $table"
            }
        }
    }
}
