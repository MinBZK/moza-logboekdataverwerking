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
        // ThreadLocal state survives the test; a leaked failure would fail an unrelated test.
        LogboekWriteFailureRecorder.clear()
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
    fun `Export records the insert failure for the fail-closed policy`() {
        val cause = RuntimeException("Oops")
        every { mockRepository.insert(any()) } throws cause

        exporter.export(mutableSetOf(mockTestSpan))

        assert(LogboekWriteFailureRecorder.consume() === cause)
    }

    @Test
    fun `Export records the mapping failure for the fail-closed policy`() {
        mockkObject(SpanMapper)
        try {
            val cause = RuntimeException("mapping bug")
            every { SpanMapper.toRow(any()) } throws cause

            exporter.export(mutableSetOf(mockTestSpan))

            assert(LogboekWriteFailureRecorder.consume() === cause)
        } finally {
            unmockkObject(SpanMapper)
        }
    }

    @Test
    fun `Export inserts the mappable rows and loses only the unmappable span`() {
        val badSpan: SpanData = mockk {
            every { traceId } returns "badTraceId"
            every { spanId } returns "badSpanId"
        }
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
            val cause = RuntimeException("mapping bug")
            val goodRow = SpanMapper.toRow(mockTestSpan)
            every { SpanMapper.toRow(mockTestSpan) } returns goodRow
            every { SpanMapper.toRow(badSpan) } throws cause

            val rows = slot<List<SpanRow>>()
            every { mockRepository.insert(capture(rows)) } returns Unit

            val result = exporter.export(mutableListOf(mockTestSpan, badSpan))

            // The good logregel is salvaged rather than dropped with the bad one.
            assert(rows.captured == listOf(goodRow))
            // ...but the batch is still a failure, so fail-closed trips on the loss.
            assert(CompletableResultCode.ofFailure() == result)
            assert(LogboekWriteFailureRecorder.consume() === cause)
            val message = records.single { it.level == Level.SEVERE }.message
            assert(message.contains("Failed to map 1 of 2"))
            assert(message.contains("badTraceId:badSpanId"))
            assert(!message.contains("myTraceId:mySpanId")) // the salvaged span is not reported lost
        } finally {
            logger.removeHandler(handler)
            unmockkObject(SpanMapper)
        }
    }

    @Test
    fun `Successful export leaves no failure recorded`() {
        exporter.export(mutableSetOf(mockTestSpan))

        assert(LogboekWriteFailureRecorder.consume() == null)
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
