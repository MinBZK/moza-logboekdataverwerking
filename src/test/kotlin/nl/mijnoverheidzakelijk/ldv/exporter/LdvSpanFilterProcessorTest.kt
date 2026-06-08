package nl.mijnoverheidzakelijk.ldv.exporter

import io.mockk.mockk
import io.mockk.every
import io.mockk.verify
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.common.InstrumentationScopeInfo
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

internal class LdvSpanFilterProcessorTest {

    @Test
    fun `forwards spans from the LDV instrumentation scope`() {
        val delegate = mockk<SpanProcessor>(relaxed = true)
        val processor = LdvSpanFilterProcessor(delegate)
        val span = mockk<ReadableSpan>()
        every { span.instrumentationScopeInfo } returns
            InstrumentationScopeInfo.create(LdvSpanFilterProcessor.LDV_INSTRUMENTATION_SCOPE)

        processor.onEnd(span)

        verify { delegate.onEnd(span) }
    }

    @Test
    fun `drops spans from other instrumentation scopes`() {
        val delegate = mockk<SpanProcessor>(relaxed = true)
        val processor = LdvSpanFilterProcessor(delegate)
        val span = mockk<ReadableSpan>()
        every { span.instrumentationScopeInfo } returns
            InstrumentationScopeInfo.create("io.opentelemetry.jdbc")

        processor.onEnd(span)

        verify(exactly = 0) { delegate.onEnd(any()) }
    }

    @Test
    fun `forwards onStart for LDV-scope spans`() {
        val delegate = mockk<SpanProcessor>(relaxed = true)
        val processor = LdvSpanFilterProcessor(delegate)
        val span = mockk<ReadWriteSpan>()
        every { span.instrumentationScopeInfo } returns
            InstrumentationScopeInfo.create(LdvSpanFilterProcessor.LDV_INSTRUMENTATION_SCOPE)

        processor.onStart(Context.root(), span)

        verify { delegate.onStart(any(), span) }
    }

    @Test
    fun `drops onStart for other instrumentation scopes`() {
        val delegate = mockk<SpanProcessor>(relaxed = true)
        val processor = LdvSpanFilterProcessor(delegate)
        val span = mockk<ReadWriteSpan>()
        every { span.instrumentationScopeInfo } returns
            InstrumentationScopeInfo.create("io.opentelemetry.jdbc")

        processor.onStart(Context.root(), span)

        verify(exactly = 0) { delegate.onStart(any(), any()) }
    }

    /**
     * End-to-end through a real OpenTelemetry SDK, the same wiring a host applies.
     * Proves that when the processor is attached to a shared SDK, only LDV-scope
     * spans reach the exporter and other instrumentation does not.
     */
    @Test
    fun `through a real SDK only LDV-scope spans reach the exporter`() {
        val recording = RecordingSpanExporter()
        val sdk = SdkTracerProvider.builder()
            .addSpanProcessor(LdvSpanFilterProcessor(SimpleSpanProcessor.create(recording)))
            .build()

        try {
            sdk.get(LdvSpanFilterProcessor.LDV_INSTRUMENTATION_SCOPE)
                .spanBuilder("ldv-span").startSpan().end()
            sdk.get("io.opentelemetry.jdbc")
                .spanBuilder("db-span").startSpan().end()
            sdk.forceFlush().join(5, TimeUnit.SECONDS)

            assert(recording.exported.map { it.name } == listOf("ldv-span")) {
                "expected only the LDV span, got ${recording.exported.map { it.name }}"
            }
        } finally {
            sdk.shutdown().join(5, TimeUnit.SECONDS)
        }
    }

    private class RecordingSpanExporter : SpanExporter {
        val exported = mutableListOf<SpanData>()
        override fun export(spans: MutableCollection<SpanData>): CompletableResultCode {
            exported.addAll(spans)
            return CompletableResultCode.ofSuccess()
        }

        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()
        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }
}
