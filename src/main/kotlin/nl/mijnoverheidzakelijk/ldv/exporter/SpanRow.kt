package nl.mijnoverheidzakelijk.ldv.exporter

import io.opentelemetry.api.trace.StatusCode

/**
 * Backend-neutral, typed representation of a single span row shared by the
 * database exporters. Backend repositories translate these fields to their own
 * column conventions.
 *
 * Using a data class (instead of an untyped `Map<String, Any>`) keeps the
 * producer ([SpanMapper]) and consumers (the repositories) coupled at compile
 * time, so a renamed or retyped field is a build error rather than a span
 * silently dropped at insert time.
 *
 * @property startTimeMillis span start as milliseconds since the Unix epoch (the
 *           unit the LDV log-record structure requires).
 * @property endTimeMillis span end as milliseconds since the Unix epoch.
 * @property parentSpanId the parent span id, or `null` for a root span (when the
 *           parent span context is not valid).
 * @property attributes span attributes (includes the `dpl.core.*` LDV metadata),
 *           serialized to JSON by backends that store them as a JSON column.
 * @property resource resource attributes identifying the producing system.
 */
data class SpanRow(
    val traceId: String,
    val spanId: String,
    val status: StatusCode,
    val name: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val parentSpanId: String?,
    val attributes: Map<String, String>,
    val resource: Map<String, String>,
)
