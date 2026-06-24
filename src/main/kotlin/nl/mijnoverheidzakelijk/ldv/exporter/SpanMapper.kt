package nl.mijnoverheidzakelijk.ldv.exporter

import io.opentelemetry.sdk.trace.data.SpanData
import java.util.concurrent.TimeUnit

/**
 * Maps OpenTelemetry [SpanData] to a backend-neutral [SpanRow] shared by the
 * database exporters.
 */
object SpanMapper {

    /**
     * Maps [SpanData] to a [SpanRow].
     *
     * Scalar fields bind directly to columns; `startEpochNanos`/`endEpochNanos`
     * are converted from nanoseconds to milliseconds since the Unix epoch, as
     * required by the LDV log-record structure. `attributes` and `resource` are
     * kept as string maps for the repository to serialize (e.g. to a JSON
     * column). `parentSpanId` is `null` for root spans (invalid parent context),
     * matching the LDV spec where `parent_span_id` is optional. Note this diverges
     * intentionally from the ClickHouse exporter, which writes the all-zero invalid
     * id (`0000000000000000`) for a root span instead of a null.
     *
     * @param span the span to map
     * @return the typed row representation
     */
    fun toRow(span: SpanData): SpanRow {
        return SpanRow(
            traceId = span.traceId,
            spanId = span.spanId,
            status = span.status.statusCode,
            name = span.name,
            startTime = TimeUnit.NANOSECONDS.toMillis(span.startEpochNanos),
            endTime = TimeUnit.NANOSECONDS.toMillis(span.endEpochNanos),
            parentSpanId = if (span.parentSpanContext.isValid) span.parentSpanId else null,
            attributes = span.attributes.asMap().entries.associate { it.key.key to it.value.toString() },
            resource = span.resource.attributes.asMap().entries.associate { it.key.key to it.value.toString() }
        )
    }
}
