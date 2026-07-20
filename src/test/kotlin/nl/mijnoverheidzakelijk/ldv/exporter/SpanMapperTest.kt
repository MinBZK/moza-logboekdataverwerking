package nl.mijnoverheidzakelijk.ldv.exporter

import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.trace.data.SpanData
import org.junit.jupiter.api.Test

internal class SpanMapperTest {

    private fun spanData(parentValid: Boolean = true): SpanData = mockk {
        every { traceId } returns "myTraceId"
        every { spanId } returns "mySpanId"
        every { status.statusCode } returns StatusCode.OK
        every { name } returns "myName"
        // 20 ms / 25 ms expressed in nanoseconds, to assert ns -> ms conversion.
        every { startEpochNanos } returns 20_000_000L
        every { endEpochNanos } returns 25_000_000L
        every { parentSpanContext.isValid } returns parentValid
        every { parentSpanId } returns "myParentSpanId"
        every { attributes.asMap().entries } returns mutableSetOf<MutableMap.MutableEntry<AttributeKey<*>, Any>>(
            mockk {
                every { key.key } returns "attrKey"
                every { value } returns "attrValue"
            },
        )
        every { resource.attributes.asMap().entries } returns mutableSetOf<MutableMap.MutableEntry<AttributeKey<*>, Any>>(
            mockk {
                every { key.key } returns "resKey"
                every { value } returns "resValue"
            },
        )
    }

    @Test
    fun `maps all fields and converts nanoseconds to milliseconds`() {
        val row = SpanMapper.toRow(spanData())

        assert(row.traceId == "myTraceId")
        assert(row.spanId == "mySpanId")
        assert(row.status == StatusCode.OK)
        assert(row.name == "myName")
        assert(row.startTimeMillis == 20L)
        assert(row.endTimeMillis == 25L)
        assert(row.parentSpanId == "myParentSpanId")
        assert(row.attributes == mapOf("attrKey" to "attrValue"))
        assert(row.resource == mapOf("resKey" to "resValue"))
    }

    @Test
    fun `parentSpanId is null for a root span with invalid parent context`() {
        val row = SpanMapper.toRow(spanData(parentValid = false))

        assert(row.parentSpanId == null)
    }
}
