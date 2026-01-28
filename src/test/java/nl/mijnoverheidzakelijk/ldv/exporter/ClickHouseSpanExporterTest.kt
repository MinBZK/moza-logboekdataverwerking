package nl.mijnoverheidzakelijk.ldv.exporter

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.confirmVerified
import io.mockk.every
import io.mockk.impl.annotations.InjectMockKs
import io.mockk.impl.annotations.SpyK
import io.mockk.junit5.MockKExtension
import io.mockk.mockk
import io.mockk.unmockkObject
import io.mockk.verify
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import nl.mijnoverheidzakelijk.ldv.repository.ClickHouseRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.io.IOException

@ExtendWith(MockKExtension::class)
internal class ClickHouseSpanExporterTest {
    private val repository: ClickHouseRepository = mockk(relaxed = true)
    private val tableName = "myTableName"
    private val objectMapper: ObjectMapper = mockk(relaxed = true)

    @SpyK(recordPrivateCalls = true)
    @InjectMockKs
    private lateinit var clickhouseSpanExporter: ClickHouseSpanExporter

    private val testSpan: SpanData = mockk {
        every { traceId } returns "myTraceId"
        every { spanId } returns "mySpanId"
        every { status.statusCode } returns StatusCode.OK
        every { name } returns "myName"
        every { startEpochNanos } returns 20L
        every { endEpochNanos } returns 25L
        every { parentSpanId } returns "myParentSpanId"
        every { attributes.asMap().entries } returns mutableSetOf<MutableMap.MutableEntry<AttributeKey<*>, Any>>(
            mockk<MutableMap.MutableEntry<AttributeKey<*>, Any>> {
                every { key.key } returns "myAtrributeKey1"
                every { value } returns "myAttributeValue1"
            },
            mockk<MutableMap.MutableEntry<AttributeKey<*>, Any>> {
                every { key.key } returns "myAtrributeKey2"
                every { value } returns "myAttributeValue2"
            },
        )
        every { resource.attributes.asMap().entries } returns mutableSetOf<MutableMap.MutableEntry<AttributeKey<*>, Any>>(
            mockk<MutableMap.MutableEntry<AttributeKey<*>, Any>> {
                every { key.key } returns "myResourceAtrributeKey1"
                every { value } returns "myResourceAttributeValue1"
            },
            mockk<MutableMap.MutableEntry<AttributeKey<*>, Any>> {
                every { key.key } returns "myResourceAtrributeKey2"
                every { value } returns "myResourceAttributeValue2"
            },
        )
    }

    @BeforeEach
    fun setUp() {
        verify { repository.ensureSchema() }
    }

    @Test
    fun `Call export with single span data, and expect a mapped string to be stored, with a success result` () {
        // given
        val realMapper = ObjectMapper()
        every { objectMapper.writeValueAsString(any()) } answers {
            // `arg<Any>(0)` is the map that the exporter built.
            realMapper.writeValueAsString(arg<Any>(0))
        }

        // when
        val result = clickhouseSpanExporter.export(mutableSetOf(testSpan))

        // then
        verify { clickhouseSpanExporter.export(mutableSetOf(testSpan)) }
        verify { clickhouseSpanExporter["mapSpanToJson"](testSpan) }
        verify { objectMapper.writeValueAsString(any()) }
        verify { repository.insertJsonEachRow(tableName, "{\"traceId\":\"myTraceId\",\"spanId\":\"mySpanId\",\"status\":\"OK\",\"name\":\"myName\",\"startTime\":0,\"endTime\":0,\"parentSpanId\":\"myParentSpanId\",\"attributes\":{\"myAtrributeKey1\":\"myAttributeValue1\",\"myAtrributeKey2\":\"myAttributeValue2\"},\"resource\":{\"myResourceAtrributeKey1\":\"myResourceAttributeValue1\",\"myResourceAtrributeKey2\":\"myResourceAttributeValue2\"}}\n") }
        confirmVerified(objectMapper, repository, clickhouseSpanExporter)
        assert(CompletableResultCode.ofSuccess() == result)
    }

    @Test
    fun `Call export with no span data, and expect a mapped string to be stored with a success result` () {
        // given
        val realMapper = ObjectMapper()
        every { objectMapper.writeValueAsString(any()) } answers {
            // `arg<Any>(0)` is the map that the exporter built.
            realMapper.writeValueAsString(arg<Any>(0))
        }

        // when
        val result = clickhouseSpanExporter.export(mutableSetOf())

        // then
        verify { clickhouseSpanExporter.export(mutableSetOf()) }
        verify(inverse = true) { repository.insertJsonEachRow(tableName, "{\"traceId\":\"myTraceId\",\"spanId\":\"mySpanId\",\"status\":\"OK\",\"name\":\"myName\",\"startTime\":0,\"endTime\":0,\"parentSpanId\":\"myParentSpanId\",\"attributes\":{\"myAtrributeKey1\":\"myAttributeValue1\",\"myAtrributeKey2\":\"myAttributeValue2\"},\"resource\":{\"myResourceAtrributeKey1\":\"myResourceAttributeValue1\",\"myResourceAtrributeKey2\":\"myResourceAttributeValue2\"}}\n") }
        confirmVerified(objectMapper, repository, clickhouseSpanExporter)
        assert(CompletableResultCode.ofSuccess() == result)
    }

    @Test
    fun `Call export, objectMapper throws exception, expect log and failure result` () {
        // given
        val ioException = IOException("Oops")
        every { objectMapper.writeValueAsString(any()) } throws ioException

        // when
        val result = clickhouseSpanExporter.export(mutableSetOf(testSpan))

        // then
        verify { clickhouseSpanExporter.export(mutableSetOf(testSpan)) }
        verify { clickhouseSpanExporter["mapSpanToJson"](testSpan) }
        verify { objectMapper.writeValueAsString(any()) }
        confirmVerified(objectMapper, repository, clickhouseSpanExporter)
        assert(CompletableResultCode.ofFailure() == result)
    }

    @Test
    fun `Call export, repository throws exception, expect log and failure result` () {
        // given
        val realMapper = ObjectMapper()
        every { objectMapper.writeValueAsString(any()) } answers {
            // `arg<Any>(0)` is the map that the exporter built.
            realMapper.writeValueAsString(arg<Any>(0))
        }
        val runtimeException = RuntimeException("Oops")
        every { repository.insertJsonEachRow(any(), any()) } throws runtimeException

        // when
        val result = clickhouseSpanExporter.export(mutableSetOf(testSpan))

        // then
        verify { clickhouseSpanExporter.export(mutableSetOf(testSpan)) }
        verify { clickhouseSpanExporter["mapSpanToJson"](testSpan) }
        verify { objectMapper.writeValueAsString(any()) }
        verify { repository.insertJsonEachRow(tableName, "{\"traceId\":\"myTraceId\",\"spanId\":\"mySpanId\",\"status\":\"OK\",\"name\":\"myName\",\"startTime\":0,\"endTime\":0,\"parentSpanId\":\"myParentSpanId\",\"attributes\":{\"myAtrributeKey1\":\"myAttributeValue1\",\"myAtrributeKey2\":\"myAttributeValue2\"},\"resource\":{\"myResourceAtrributeKey1\":\"myResourceAttributeValue1\",\"myResourceAtrributeKey2\":\"myResourceAttributeValue2\"}}\n") }
        confirmVerified(objectMapper, repository, clickhouseSpanExporter)
        assert(CompletableResultCode.ofFailure() == result)

        unmockkObject(ClickHouseSpanExporter)
    }

    @Test
    fun `Call shutdown and expect success result` () {
        // when
        val result = clickhouseSpanExporter.shutdown()

        // then
        verify { clickhouseSpanExporter.shutdown() }
        confirmVerified(clickhouseSpanExporter)
        assert(CompletableResultCode.ofSuccess() == result)
    }

    @Test
    fun `Call flush and expect success result` () {
        // when
        val result = clickhouseSpanExporter.flush()

        // then
        verify { clickhouseSpanExporter.flush() }
        confirmVerified(clickhouseSpanExporter)
        assert(CompletableResultCode.ofSuccess() == result)
    }
}
