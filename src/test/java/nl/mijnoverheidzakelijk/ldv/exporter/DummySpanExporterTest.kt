package nl.mijnoverheidzakelijk.ldv.exporter

import io.mockk.confirmVerified
import io.mockk.impl.annotations.SpyK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

@ExtendWith(MockKExtension::class)
internal class DummySpanExporterTest {
    @SpyK(recordPrivateCalls = true)
    private var dummySpanExporter = DummySpanExporter()

    @ParameterizedTest
    @MethodSource("Call export with any span data, and expect a success result")
    fun `Call export with any span data, and expect a success result` (spanData: Collection<SpanData>) {
        // when
        val result = dummySpanExporter.export(spanData)

        // then
        verify { dummySpanExporter.export(spanData) }
        confirmVerified(dummySpanExporter)
        assert(CompletableResultCode.ofSuccess() == result)
    }

    companion object {
        @JvmStatic
        private fun `Call export with any span data, and expect a success result` (): List<List<Collection<SpanData>>> {
            return listOf(
                listOf(),
                listOf(mockk()),
                listOf(mockk(), mockk()),
            )
        }
    }

    @Test
    fun `Call shutdown and expect success result` () {
        // when
        val result = dummySpanExporter.shutdown()

        // then
        verify { dummySpanExporter.shutdown() }
        confirmVerified(dummySpanExporter)
        assert(CompletableResultCode.ofSuccess() == result)
    }

    @Test
    fun `Call flush and expect success result` () {
        // when
        val result = dummySpanExporter.flush()

        // then
        verify { dummySpanExporter.flush() }
        confirmVerified(dummySpanExporter)
        assert(CompletableResultCode.ofSuccess() == result)
    }
}
