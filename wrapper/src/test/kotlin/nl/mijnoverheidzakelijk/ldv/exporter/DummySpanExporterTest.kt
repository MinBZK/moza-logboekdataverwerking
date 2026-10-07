package nl.mijnoverheidzakelijk.ldv.exporter

import io.mockk.mockk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

@DisplayName("DummySpanExporter")
internal class DummySpanExporterTest {
    private val exporter = DummySpanExporter()

    @ParameterizedTest(name = "export with {0} spans returns success")
    @MethodSource("spanDataProvider")
    fun `Export returns success for any span data`(spanData: Collection<SpanData>) {
        val result = exporter.export(spanData)

        assert(result == CompletableResultCode.ofSuccess())
    }

    companion object {
        @JvmStatic
        fun spanDataProvider(): List<Collection<SpanData>> = listOf(
            emptyList(),
            listOf(mockk()),
            listOf(mockk(), mockk())
        )
    }

    @Test
    fun `Shutdown returns success`() {
        val result = exporter.shutdown()

        assert(result == CompletableResultCode.ofSuccess())
    }

    @Test
    fun `Flush returns success`() {
        val result = exporter.flush()

        assert(result == CompletableResultCode.ofSuccess())
    }
}
