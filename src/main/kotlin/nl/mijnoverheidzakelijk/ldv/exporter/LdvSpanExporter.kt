package nl.mijnoverheidzakelijk.ldv.exporter

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import nl.mijnoverheidzakelijk.ldv.repository.SpanRepository
import java.util.logging.Level
import java.util.logging.Logger

/**
 * The single OpenTelemetry [SpanExporter] for every LDV database backend.
 *
 * Spans are mapped to the shared, typed [SpanRow] by [SpanMapper] and handed to
 * a backend-specific [SpanRepository]; the exporter itself is backend-agnostic.
 * Selecting or adding a backend is a matter of which [SpanRepository] is injected
 * (see [nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.ProcessingHandler]),
 * so the span→row mapping and the lost-record logging live in exactly one place.
 *
 * On construction it ensures the target schema exists. A failed export is NOT
 * retried — no OpenTelemetry span processor re-offers a failed batch — so a
 * failure logs the lost records' `traceId:spanId` to keep them traceable.
 */
class LdvSpanExporter(
    private val repository: SpanRepository,
    private val maxLoggedSpanIds: Int = DEFAULT_MAX_LOGGED_SPAN_IDS,
) : SpanExporter {

    init {
        repository.ensureSchema()
    }

    /**
     * Exports a collection of spans via the configured [SpanRepository].
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
                "Failed to map ${spans.size} span(s) for export; lost spans: ${lostSpanIds(spans)}",
                e,
            )
            return CompletableResultCode.ofFailure()
        }

        if (rows.isEmpty()) {
            return CompletableResultCode.ofSuccess()
        }

        return try {
            repository.insert(rows)
            CompletableResultCode.ofSuccess()
        } catch (e: Exception) {
            // The whole batch is dropped (no retry), so log the count and the
            // trace/span ids of the lost LDV records to keep them traceable.
            LOGGER.log(
                Level.SEVERE,
                "Failed to export ${spans.size} span(s); lost spans: ${lostSpanIds(spans)}",
                e,
            )
            CompletableResultCode.ofFailure()
        }
    }

    /** Renders `traceId:spanId` for the lost spans, capped to keep the log line bounded. */
    private fun lostSpanIds(spans: Collection<SpanData>): String =
        spans.joinToString(separator = ", ", limit = maxLoggedSpanIds, truncated = "…") {
            "${it.traceId}:${it.spanId}"
        }

    /**
     * Flushes pending exports. This exporter holds no internal buffer — every
     * batch is written synchronously in [export] — so there is nothing to flush
     * and this always returns success. (Any queueing lives in the OpenTelemetry
     * [io.opentelemetry.sdk.trace.export.BatchSpanProcessor], which drains itself
     * via [export], not via this method.)
     *
     * @return success result code
     */
    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    /**
     * Shuts down the exporter and releases the repository's resources.
     *
     * @return success, or failure if closing the repository throws
     */
    override fun shutdown(): CompletableResultCode = try {
        repository.close()
        CompletableResultCode.ofSuccess()
    } catch (e: Exception) {
        LOGGER.log(Level.SEVERE, "Failed to close span repository", e)
        CompletableResultCode.ofFailure()
    }

    companion object {
        private val LOGGER: Logger = Logger.getLogger(LdvSpanExporter::class.java.getName())

        /**
         * Default upper bound on how many lost span ids are listed in a single
         * failure log line. Sized to cover a full `BatchSpanProcessor` default
         * batch (512) so a whole failed batch is logged without truncation.
         */
        const val DEFAULT_MAX_LOGGED_SPAN_IDS = 512
    }
}
