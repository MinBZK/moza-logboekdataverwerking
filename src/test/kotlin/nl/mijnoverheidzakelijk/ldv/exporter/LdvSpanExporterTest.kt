package nl.mijnoverheidzakelijk.ldv.exporter

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.slot
import io.mockk.unmockkObject
import io.mockk.verify
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import nl.mijnoverheidzakelijk.ldv.repository.SpanRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

internal class LdvSpanExporterTest {

    private lateinit var mockRepository: SpanRepository
    private lateinit var exporter: LdvSpanExporter

    private val mockTestSpan: SpanData = mockk {
        every { traceId } returns "myTraceId"
        every { spanId } returns "mySpanId"
        every { status.statusCode } returns StatusCode.OK
        every { name } returns "myName"
        every { startEpochNanos } returns 20_000_000L
        every { endEpochNanos } returns 25_000_000L
        every { parentSpanContext.isValid } returns true
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

    @BeforeEach
    fun setUp() {
        mockRepository = mockk(relaxed = true)
        exporter = LdvSpanExporter(mockRepository)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `Constructor ensures schema`() {
        verify { mockRepository.ensureSchema() }
    }

    @Test
    fun `Export with single span inserts mapped row and returns success`() {
        val rows = slot<List<SpanRow>>()
        every { mockRepository.insert(capture(rows)) } returns Unit

        val result = exporter.export(mutableSetOf(mockTestSpan))

        assert(CompletableResultCode.ofSuccess() == result)
        val row = rows.captured.single()
        assert(row.traceId == "myTraceId")
        assert(row.spanId == "mySpanId")
        assert(row.status == StatusCode.OK)
        assert(row.startTimeMillis == 20L)
        assert(row.endTimeMillis == 25L)
        assert(row.parentSpanId == "myParentSpanId")
        assert(row.attributes == mapOf("attrKey" to "attrValue"))
        assert(row.resource == mapOf("resKey" to "resValue"))
    }

    @Test
    fun `Export with no spans returns success without inserting`() {
        val result = exporter.export(mutableSetOf())

        assert(CompletableResultCode.ofSuccess() == result)
        verify(exactly = 0) { mockRepository.insert(any()) }
    }

    @Test
    fun `Export with repository exception returns failure`() {
        every { mockRepository.insert(any()) } throws RuntimeException("Oops")

        val result = exporter.export(mutableSetOf(mockTestSpan))

        assert(CompletableResultCode.ofFailure() == result)
    }

    @Test
    fun `Shutdown closes repository and returns success`() {
        val result = exporter.shutdown()

        verify { mockRepository.close() }
        assert(CompletableResultCode.ofSuccess() == result)
    }

    @Test
    fun `Shutdown returns failure when repository close throws`() {
        every { mockRepository.close() } throws RuntimeException("close boom")

        val result = exporter.shutdown()

        assert(CompletableResultCode.ofFailure() == result)
    }

    @Test
    fun `Flush returns success`() {
        assert(CompletableResultCode.ofSuccess() == exporter.flush())
    }

    @Test
    fun `Export returns failure, skips insert and logs a distinct message when mapping throws`() {
        mockkObject(SpanMapper)
        val records = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records.add(record) }
            override fun flush() {}
            override fun close() {}
        }
        val logger = Logger.getLogger(LdvSpanExporter::class.java.name)
        logger.addHandler(handler)
        try {
            every { SpanMapper.toRow(any()) } throws RuntimeException("mapping bug")

            val result = exporter.export(mutableSetOf(mockTestSpan))

            assert(CompletableResultCode.ofFailure() == result)
            verify(exactly = 0) { mockRepository.insert(any()) }
            val message = records.single { it.level == Level.SEVERE }.message
            assert(message.contains("Failed to map")) // distinct from the insert-failure message
            assert(message.contains("myTraceId:mySpanId"))
        } finally {
            logger.removeHandler(handler)
            unmockkObject(SpanMapper)
        }
    }

    @Test
    fun `Failure logs the lost span ids and truncates beyond the cap`() {
        every { mockRepository.insert(any()) } throws RuntimeException("boom")
        // Use a small cap so the test does not need a full-batch worth of spans.
        val cappedExporter = LdvSpanExporter(mockRepository, maxLoggedSpanIds = 50)
        val records = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { records.add(record) }
            override fun flush() {}
            override fun close() {}
        }
        val logger = Logger.getLogger(LdvSpanExporter::class.java.name)
        logger.addHandler(handler)
        try {
            val spans = (1..60).map { span(tid = "t$it", sid = "s$it") }.toMutableSet()
            cappedExporter.export(spans)
        } finally {
            logger.removeHandler(handler)
        }

        val message = records.single { it.level == Level.SEVERE }.message
        assert(message.contains("60 span(s)"))
        assert(message.contains("t1:s1"))
        assert(message.contains("…")) // truncated past maxLoggedSpanIds
    }

    private fun span(tid: String, sid: String): SpanData = mockk {
        every { traceId } returns tid
        every { spanId } returns sid
        every { status.statusCode } returns StatusCode.OK
        every { name } returns "n"
        every { startEpochNanos } returns 0L
        every { endEpochNanos } returns 0L
        every { parentSpanContext.isValid } returns false
        every { attributes.asMap().entries } returns mutableSetOf()
        every { resource.attributes.asMap().entries } returns mutableSetOf()
    }
}
