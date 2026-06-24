package nl.mijnoverheidzakelijk.ldv.exporter

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import nl.mijnoverheidzakelijk.ldv.repository.PostgresRepository
import java.util.logging.Level
import java.util.logging.Logger

/**
 * OpenTelemetry [SpanExporter] that writes spans to a PostgreSQL table.
 *
 * PostgreSQL is a lighter-weight alternative to ClickHouse intended for
 * development. The exporter is selected via `logboekdataverwerking.dbms=postgresql`.
 * On construction it ensures the target schema exists and then inserts exported
 * spans in batches.
 */
class PostgresSpanExporter(
    private val repository: PostgresRepository = PostgresRepository(),
) : SpanExporter {

    init {
        repository.ensureSchema()
    }

    /**
     * Exports a collection of spans to PostgreSQL.
     *
     * @param spans the spans to export
     * @return success or failure result code
     */
    override fun export(spans: MutableCollection<SpanData>): CompletableResultCode {
        val rows: List<SpanRow> = try {
            spans.map { SpanMapper.toRow(it) }
        } catch (e: Exception) {
            // A mapping failure is a code defect, not a transient DB issue. The
            // whole batch is dropped (no retry), so flag the cause distinctly from
            // an insert failure and list the lost LDV records.
            LOGGER.log(
                Level.SEVERE,
                "Failed to map ${spans.size} span(s) for PostgreSQL export; lost spans: ${lostSpanIds(spans)}",
                e,
            )
            return CompletableResultCode.ofFailure()
        }

        if (rows.isEmpty()) {
            return CompletableResultCode.ofSuccess()
        }

        return try {
            repository.insertSpans(rows)
            CompletableResultCode.ofSuccess()
        } catch (e: Exception) {
            // The whole batch is dropped (no retry), so log the count and the
            // trace/span ids of the lost LDV records to keep them traceable.
            LOGGER.log(
                Level.SEVERE,
                "Failed to export ${spans.size} span(s) to PostgreSQL; lost spans: ${lostSpanIds(spans)}",
                e,
            )
            CompletableResultCode.ofFailure()
        }
    }

    /** Renders `traceId:spanId` for the lost spans, capped to keep the log line bounded. */
    private fun lostSpanIds(spans: Collection<SpanData>): String =
        spans.joinToString(separator = ", ", limit = MAX_LOGGED_SPAN_IDS, truncated = "…") {
            "${it.traceId}:${it.spanId}"
        }

    /**
     * No-op flush for this exporter. Returns success.
     *
     * @return success result code
     */
    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    /**
     * Shuts down the exporter and closes the underlying PostgreSQL connection.
     *
     * Returns success unconditionally: a connection-close failure has no recovery
     * action at shutdown. The repository's `close()` already logs and swallows any
     * such failure, so it never surfaces here.
     *
     * @return success result code
     */
    override fun shutdown(): CompletableResultCode {
        repository.close()
        return CompletableResultCode.ofSuccess()
    }

    companion object {
        private val LOGGER: Logger = Logger.getLogger(PostgresSpanExporter::class.java.getName())

        /** Upper bound on how many lost span ids are listed in a single failure log line. */
        private const val MAX_LOGGED_SPAN_IDS = 50
    }
}
