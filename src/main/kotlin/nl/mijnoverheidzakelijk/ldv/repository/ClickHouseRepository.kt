package nl.mijnoverheidzakelijk.ldv.repository

import com.clickhouse.client.api.ClickHouseException
import com.clickhouse.client.api.Client
import com.clickhouse.client.api.ServerException
import com.clickhouse.data.ClickHouseFormat
import com.fasterxml.jackson.databind.ObjectMapper
import io.opentelemetry.api.trace.SpanId
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader
import nl.mijnoverheidzakelijk.ldv.exporter.SpanRow
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * Repository encapsulating basic ClickHouse operations used by the exporter.
 *
 * Implements [SpanRepository]; the span `attributes`/`resource` maps are stored
 * as `Map(String, String)` columns and a root span's `parentSpanId` is rendered
 * as the all-zero invalid id (see [insert]). The ClickHouse [Client] is
 * internally thread-safe, so — unlike [PostgresRepository] — no external
 * synchronization is needed for the `SimpleSpanProcessor` path that can call
 * [insert] from arbitrary application threads.
 */
class ClickHouseRepository(
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val queryTimeoutSeconds: Int = ConfigurationLoader.clickhouseQueryTimeoutSeconds,
) : SpanRepository {
    private val table: String = ConfigurationLoader.clickhouseTable
    private val client: Client = Client.Builder()
        .addEndpoint(ConfigurationLoader.clickhouseEndpoint)
        .setUsername(ConfigurationLoader.clickhouseUsername)
        .setPassword(ConfigurationLoader.clickhousePassword)
        .setDefaultDatabase(ConfigurationLoader.clickhouseDatabase)
        .build()

    init {
        TableNames.requireValid(table)
        // Must be > 0, not >= 0: Future.get(0, SECONDS) polls once and times out
        // immediately rather than waiting, which would fail every export.
        require(queryTimeoutSeconds > 0) {
            "queryTimeoutSeconds must be > 0, was $queryTimeoutSeconds"
        }
    }

    /**
     * Ensures that the target table exists with the expected schema.
     *
     * @throws RuntimeException if the DDL operation fails or times out
     */
    override fun ensureSchema() {
        try {
            // Columns match the JSONEachRow keys produced by toJsonMap (camelCase).
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
            ).get(queryTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw storageFailure("Failed to ensure ClickHouse schema", e)
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
        "startTime" to row.startTimeMillis,
        "endTime" to row.endTimeMillis,
        "parentSpanId" to (row.parentSpanId ?: SpanId.getInvalid()),
        "attributes" to row.attributes,
        "resource" to row.resource,
    )

    /**
     * Inserts a JSON payload into the configured table.
     *
     * @param jsonEachRowPayload payload where each line is a JSON object
     * @throws RuntimeException if the insert fails or times out
     */
    fun insertJsonEachRow(jsonEachRowPayload: String) {
        try {
            val data: InputStream = ByteArrayInputStream(jsonEachRowPayload.toByteArray(StandardCharsets.UTF_8))
            client.insert(table, data, ClickHouseFormat.JSONEachRow).get(queryTimeoutSeconds.toLong(), TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw storageFailure("Failed to insert into ClickHouse", e)
        }
    }

    /**
     * Wraps a ClickHouse failure, lifting out what identifies it without quoting it:
     * the server's error code and the query id (the full error is in
     * `system.query_log` under that id). The server's message stays in the cause.
     */
    private fun storageFailure(message: String, e: Exception): SpanStorageException {
        val chain = generateSequence<Throwable>(e) { it.cause }.take(CAUSE_SEARCH_DEPTH).toList()
        return SpanStorageException.create(
            message,
            e,
            // CODE_UNKNOWN (0) is what the client reports for a non-ClickHouse error, e.g. a proxy 502.
            vendorCode = chain.firstNotNullOfOrNull { it as? ServerException }?.code?.takeIf { it != 0 },
            queryId = chain.firstNotNullOfOrNull { (it as? ClickHouseException)?.queryId },
        )
    }

    override fun close() {
        client.close()
    }

    private companion object {
        // How deep to look for the ClickHouse exception: the client throws it directly, or
        // under an ExecutionException when it runs its operations asynchronously.
        const val CAUSE_SEARCH_DEPTH = 10
    }
}
