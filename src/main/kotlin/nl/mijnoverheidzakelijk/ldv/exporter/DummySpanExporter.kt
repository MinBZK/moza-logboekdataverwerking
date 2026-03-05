package nl.mijnoverheidzakelijk.ldv.exporter

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter

/**
 * No-op [SpanExporter] implementation used when logboek data processing is disabled.
 *
 * All operations return success without performing any actual work.
 */
class DummySpanExporter : SpanExporter {
    override fun export(spanData: Collection<SpanData>): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}
