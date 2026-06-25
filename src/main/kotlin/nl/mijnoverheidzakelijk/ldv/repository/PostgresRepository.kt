package nl.mijnoverheidzakelijk.ldv.repository

import com.fasterxml.jackson.databind.ObjectMapper
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader
import nl.mijnoverheidzakelijk.ldv.exporter.SpanRow
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Repository encapsulating basic PostgreSQL operations used by the exporter.
 *
 * PostgreSQL is an alternative backend to ClickHouse, suited to deployments where
 * running PostgreSQL is operationally preferable. The span `attributes` and
 * `resource` maps are stored as `jsonb`
 * columns; the remaining fields map to plain columns whose names match the
 * [SpanRow] fields produced by
 * [nl.mijnoverheidzakelijk.ldv.exporter.SpanMapper].
 *
 * The repository holds a single JDBC [Connection], created lazily on first use.
 * JDBC connections are not thread-safe, so every public operation is
 * `@Synchronized` to serialize access: this matters for the
 * [io.opentelemetry.sdk.trace.export.SimpleSpanProcessor] path, which can invoke
 * `export()` from arbitrary application threads. (The default `BatchSpanProcessor`
 * exports from a single worker thread, where the lock is uncontended.) Before
 * each read/write the connection is validated and transparently re-established
 * if it has gone stale (DB restart, idle timeout, network blip), so a transient
 * failure does not permanently wedge the exporter.
 */
class PostgresRepository(
    private val table: String = ConfigurationLoader.postgresqlTable,
    private val objectMapper: ObjectMapper = ObjectMapper(),
    private val connectionFactory: () -> Connection = {
        DriverManager.getConnection(
            ConfigurationLoader.postgresqlUrl,
            ConfigurationLoader.postgresqlUsername,
            ConfigurationLoader.postgresqlPassword,
        )
    },
    private val connectionValidationTimeoutSeconds: Int =
        ConfigurationLoader.postgresqlConnectionValidationTimeoutSeconds,
) : SpanRepository {
    init {
        TableNames.requireValid(table)
        require(connectionValidationTimeoutSeconds >= 0) {
            "connectionValidationTimeoutSeconds must be >= 0, was $connectionValidationTimeoutSeconds"
        }
    }

    private val insertSql =
        "INSERT INTO $table " +
            "(trace_id, span_id, status, \"name\", start_time, end_time, parent_span_id, attributes, resource) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)"

    /** Null until first use, and after [invalidateConnection]; (re)created by [connection]. */
    private var connection: Connection? = null

    /**
     * Returns a usable connection, (re-)establishing it if there is none or the
     * current one is no longer valid.
     *
     * Mutates the shared [connection] field without taking the lock itself, so it
     * MUST only be called while holding this instance's monitor (i.e. from a
     * `@Synchronized` method). Every public entry point satisfies this.
     *
     * @throws RuntimeException if a new connection cannot be established
     */
    private fun connection(): Connection {
        val current = connection
        if (current != null && isUsable(current)) {
            return current
        }
        closeQuietly(current)
        val fresh = runCatching { connectionFactory() }
            .getOrElse { throw RuntimeException("Failed to (re)establish PostgreSQL connection", it) }
        connection = fresh
        return fresh
    }

    /**
     * Closes the current connection and drops the reference so the next use reconnects.
     *
     * Like [connection], mutates the shared [connection] field without locking, so it
     * MUST only be called while holding this instance's monitor.
     */
    private fun invalidateConnection() {
        closeQuietly(connection)
        connection = null
    }

    /**
     * Liveness check that treats a thrown probe as "not usable" so we reconnect
     * rather than let an unexpected [java.sql.Connection.isValid] failure propagate
     * and wedge the exporter on a stale connection.
     */
    private fun isUsable(conn: Connection): Boolean =
        runCatching { conn.isValid(connectionValidationTimeoutSeconds) }.getOrDefault(false)

    /** Closes a connection, logging (not rethrowing) a close failure so it leaves a trail. */
    private fun closeQuietly(conn: Connection?) {
        conn ?: return
        runCatching { conn.close() }.onFailure {
            LOGGER.log(Level.WARNING, "Failed to close PostgreSQL connection while recycling it", it)
        }
    }

    /**
     * Ensures that the target table exists with the expected schema.
     *
     * @throws RuntimeException if the DDL operation fails
     */
    @Synchronized
    override fun ensureSchema() {
        val conn = connection()
        try {
            conn.createStatement().use { statement ->
                statement.execute(
                    """
                    CREATE TABLE IF NOT EXISTS $table (
                        trace_id text NOT NULL,
                        span_id text NOT NULL,
                        status text NOT NULL,
                        "name" text NOT NULL,
                        start_time bigint NOT NULL,
                        end_time bigint NOT NULL,
                        parent_span_id text,
                        attributes jsonb,
                        resource jsonb,
                        PRIMARY KEY (trace_id, span_id)
                    )
                    """.trimIndent()
                )
            }
        } catch (e: SQLException) {
            invalidateConnection()
            throw RuntimeException("Failed to ensure PostgreSQL schema", e)
        }
    }

    /**
     * Inserts the given span rows into the configured table as a single
     * transaction. On failure the transaction is rolled back, so a batch is
     * committed all-or-nothing rather than leaving partial rows behind.
     *
     * The `attributes`/`resource` JSON is serialized up front, before the
     * transaction is opened, so that CPU-bound work neither holds the connection
     * lock nor risks leaving an open transaction behind on a serialization error.
     * A serialization failure is reported distinctly from a database failure.
     *
     * @param rows rows produced by [nl.mijnoverheidzakelijk.ldv.exporter.SpanMapper]
     * @throws RuntimeException if serialization or the insert fails
     */
    @Synchronized
    override fun insert(rows: List<SpanRow>) {
        if (rows.isEmpty()) return

        val prepared = try {
            rows.map { span ->
                PreparedRow(
                    span = span,
                    attributesJson = objectMapper.writeValueAsString(span.attributes),
                    resourceJson = objectMapper.writeValueAsString(span.resource),
                )
            }
        } catch (e: Exception) {
            throw RuntimeException("Failed to serialize spans for PostgreSQL", e)
        }

        val conn = connection()
        try {
            conn.autoCommit = false
            conn.prepareStatement(insertSql).use { statement ->
                for ((span, attributesJson, resourceJson) in prepared) {
                    statement.setString(1, span.traceId)
                    statement.setString(2, span.spanId)
                    statement.setString(3, span.status.name)
                    statement.setString(4, span.name)
                    statement.setLong(5, span.startTimeMillis)
                    statement.setLong(6, span.endTimeMillis)
                    statement.setString(7, span.parentSpanId)
                    statement.setString(8, attributesJson)
                    statement.setString(9, resourceJson)
                    statement.addBatch()
                }
                val updateCounts = statement.executeBatch()
                // The PostgreSQL driver aborts the batch and throws on the first
                // failing row, so reaching here normally means every row succeeded.
                // Guard against a driver/config that instead continues past failures
                // and reports EXECUTE_FAILED per row, which would otherwise let
                // commit() persist a partial batch and silently lose LDV records.
                if (updateCounts.any { it == Statement.EXECUTE_FAILED }) {
                    throw SQLException("PostgreSQL batch insert reported a failed row")
                }
            }
            conn.commit()
        } catch (e: SQLException) {
            runCatching { conn.rollback() }.onFailure {
                LOGGER.log(Level.WARNING, "PostgreSQL rollback failed after insert error; recycling connection", it)
            }
            invalidateConnection()
            throw RuntimeException("Failed to insert into PostgreSQL", e)
        }

        // The batch is durably committed past this point. Restoring autoCommit is
        // bookkeeping for connection reuse: if it fails the data is NOT lost, so
        // recycle the connection rather than report an insert failure (which would
        // mislabel a committed batch as lost).
        try {
            conn.autoCommit = true
        } catch (e: SQLException) {
            LOGGER.log(Level.WARNING, "PostgreSQL commit succeeded but restoring autoCommit failed; recycling connection", e)
            invalidateConnection()
        }
    }

    @Synchronized
    override fun close() {
        invalidateConnection()
    }

    private data class PreparedRow(
        val span: SpanRow,
        val attributesJson: String,
        val resourceJson: String,
    )

    companion object {
        private val LOGGER: Logger = Logger.getLogger(PostgresRepository::class.java.getName())
    }
}
