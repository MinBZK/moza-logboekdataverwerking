package nl.mijnoverheidzakelijk.ldv.exporter

import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SpanProcessor

/**
 * A [SpanProcessor] decorator that only forwards LDV spans to [delegate].
 *
 * LDV spans are identified by their **instrumentation scope**
 * ([LDV_INSTRUMENTATION_SCOPE]), which is fixed at span creation by the LDV
 * tracer and is immutable, so it is a spoof-proof signal: nothing the host app
 * does (including setting attributes) can cause a non-LDV span to be routed
 * here. LDV spans also carry the `dpl.core.processing_activity_id` attribute,
 * but routing deliberately keys on scope rather than on that attribute.
 *
 * The filter matters when this processor is registered on a host application's
 * shared OpenTelemetry SDK (e.g. via quarkus-opentelemetry): without it, every
 * application span (HTTP, DB, ...) would be exported to ClickHouse, not just the
 * data-processing log spans. In a standalone SDK the filter is a harmless no-op
 * because only LDV spans exist there.
 */
class LdvSpanFilterProcessor(private val delegate: SpanProcessor) : SpanProcessor {

    override fun onStart(parentContext: Context, span: ReadWriteSpan) {
        delegate.onStart(parentContext, span)
    }

    override fun isStartRequired(): Boolean = delegate.isStartRequired()

    override fun onEnd(span: ReadableSpan) {
        if (span.instrumentationScopeInfo.name == LDV_INSTRUMENTATION_SCOPE) {
            delegate.onEnd(span)
        }
    }

    override fun isEndRequired(): Boolean = delegate.isEndRequired()

    override fun shutdown(): CompletableResultCode = delegate.shutdown()

    override fun forceFlush(): CompletableResultCode = delegate.forceFlush()

    companion object {
        /**
         * Instrumentation scope name used by the LDV tracer. Spans created under
         * this scope are the data-processing log spans destined for ClickHouse.
         */
        const val LDV_INSTRUMENTATION_SCOPE: String = "nl.mijnoverheidzakelijk.ldv"
    }
}
