package nl.mijnoverheidzakelijk.ldv.exporter

import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter

class DummySpanExporter: SpanExporter {
    override fun export(spanData: Collection<SpanData>): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}
