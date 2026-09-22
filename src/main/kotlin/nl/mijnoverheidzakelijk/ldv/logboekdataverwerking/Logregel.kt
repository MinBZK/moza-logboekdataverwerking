package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import io.opentelemetry.api.trace.SpanContext

/**
 * Reference to an exported logregel: its span context plus the LDV fields a later
 * outcome logregel repeats. [ProcessingHandler.addLogboekContextToSpan] returns one
 * per logregel it exported; [ProcessingHandler.recordFailedOutcome] takes them. An
 * afnemer that manages its own spans and only keeps a [SpanContext] builds the
 * reference itself.
 *
 * @property spanContext          trace_id and span_id of the logregel
 * @property name                 name of the logregel
 * @property processingActivityId `dpl.core.processing_activity_id`, or null when absent
 * @property subject              the betrokkene on the logregel, or null when absent
 */
data class Logregel(
    val spanContext: SpanContext,
    val name: String,
    val processingActivityId: String?,
    val subject: DataSubject?,
)
