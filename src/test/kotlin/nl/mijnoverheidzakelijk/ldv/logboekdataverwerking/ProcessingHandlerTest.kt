package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader
import nl.mijnoverheidzakelijk.ldv.exporter.LdvSpanFilterProcessor
import nl.mijnoverheidzakelijk.ldv.exporter.LogboekWriteFailureRecorder
import java.util.Optional
import org.eclipse.microprofile.config.Config
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class ProcessingHandlerTest {

    private lateinit var handler: ProcessingHandler
    private lateinit var mockOpenTelemetry: OpenTelemetry
    private lateinit var mockTracer: Tracer
    private lateinit var mockSpanBuilder: SpanBuilder
    private lateinit var mockSpan: Span

    companion object {
        private lateinit var mockConfig: Config

        @JvmStatic
        @BeforeAll
        fun setUpClass() {
            mockConfig = mockk()
            every { mockConfig.getValue("logboekdataverwerking.service-name", String::class.java) } returns "test-service"
            every { mockConfig.getValue("logboekdataverwerking.enabled", Boolean::class.java) } returns false
            ConfigurationLoader.configProvider = { mockConfig }
        }
    }

    @BeforeEach
    fun setUp() {
        // Create mocks
        mockOpenTelemetry = mockk()
        mockTracer = mockk()
        mockSpanBuilder = mockk()
        mockSpan = mockk(relaxed = true)

        // Set up mock chain
        every { mockOpenTelemetry.getTracer(any()) } returns mockTracer
        every { mockTracer.spanBuilder(any()) } returns mockSpanBuilder
        every { mockSpanBuilder.startSpan() } returns mockSpan
        every { mockSpanBuilder.setParent(any<Context>()) } returns mockSpanBuilder

        // Inject the mock OpenTelemetry directly; LDV always uses its own dedicated SDK,
        // so unit tests bypass init() (which would build a real SDK) and set the field.
        handler = ProcessingHandler()
        val field = ProcessingHandler::class.java.getDeclaredField("openTelemetry")
        field.isAccessible = true
        field.set(handler, mockOpenTelemetry)
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Nested
    @DisplayName("startSpan")
    inner class StartSpanTests {

        @Test
        fun `StartSpan without context creates span without parent`() {
            // when
            val result = handler.startSpan("test-span", null)

            // then
            verify { mockTracer.spanBuilder("test-span") }
            verify { mockSpanBuilder.startSpan() }
            verify(inverse = true) { mockSpanBuilder.setParent(any<Context>()) }
            assert(result == mockSpan)
        }

        @Test
        fun `StartSpan with context creates span with parent`() {
            // given
            val parentContext: Context = mockk()

            // when
            val result = handler.startSpan("test-span", parentContext)

            // then
            verify { mockTracer.spanBuilder("test-span") }
            verify { mockSpanBuilder.setParent(parentContext) }
            verify { mockSpanBuilder.startSpan() }
            assert(result == mockSpan)
        }

        @Test
        fun `StartSpan uses the dedicated LDV instrumentation scope for tracer`() {
            // when
            handler.startSpan("any-span", null)

            // then
            verify { mockOpenTelemetry.getTracer(LdvSpanFilterProcessor.LDV_INSTRUMENTATION_SCOPE) }
        }
    }

    @Nested
    @DisplayName("buildLdvSpanProcessor")
    inner class BuildLdvSpanProcessorTests {

        private lateinit var cfg: Config

        @BeforeEach
        fun setUpConfig() {
            cfg = mockk()
            ConfigurationLoader.configProvider = { cfg }
        }

        @AfterEach
        fun restoreConfig() {
            ConfigurationLoader.configProvider = { mockConfig }
        }

        @Test
        fun `disabled returns a no-op processor without reading backend config`() {
            every { cfg.getValue("logboekdataverwerking.enabled", Boolean::class.java) } returns false

            // Should not throw and should not require any backend config keys.
            ProcessingHandler.buildLdvSpanProcessor()
        }

        @Test
        fun `postgresql backend validates postgresql config and fails loud when incomplete`() {
            every { cfg.getValue("logboekdataverwerking.enabled", Boolean::class.java) } returns true
            every { cfg.getOptionalValue("logboekdataverwerking.dbms", String::class.java) } returns Optional.of("postgresql")
            every {
                cfg.getValue(match<String> { it.startsWith("logboekdataverwerking.postgresql.") }, String::class.java)
            } throws NoSuchElementException("missing")

            val ex = assertThrows<IllegalStateException> {
                ProcessingHandler.buildLdvSpanProcessor()
            }
            assert(ex.message!!.contains("logboekdataverwerking.postgresql."))
        }

        @Test
        fun `default clickhouse backend validates clickhouse config and fails loud when incomplete`() {
            every { cfg.getValue("logboekdataverwerking.enabled", Boolean::class.java) } returns true
            every { cfg.getOptionalValue("logboekdataverwerking.dbms", String::class.java) } returns Optional.empty()
            every {
                cfg.getValue(match<String> { it.startsWith("logboekdataverwerking.clickhouse.") }, String::class.java)
            } throws NoSuchElementException("missing")

            val ex = assertThrows<IllegalStateException> {
                ProcessingHandler.buildLdvSpanProcessor()
            }
            assert(ex.message!!.contains("logboekdataverwerking.clickhouse."))
        }

        @Test
        fun `unsupported dbms value fails loud`() {
            every { cfg.getValue("logboekdataverwerking.enabled", Boolean::class.java) } returns true
            every { cfg.getOptionalValue("logboekdataverwerking.dbms", String::class.java) } returns Optional.of("mysql")

            assertThrows<IllegalArgumentException> {
                ProcessingHandler.buildLdvSpanProcessor()
            }
        }
    }

    @Nested
    @DisplayName("addLogboekContextToSpan")
    inner class AddLogboekContextToSpanTests {

        @Test
        fun `Adds all attributes from LogboekContext to span`() {
            // given
            val logboekContext = LogboekContext().apply {
                processingActivityId = "https://register.example.org/activiteiten/activity-123"
                dataSubjectId = "subject-456"
                dataSubjectType = "BSN"
                status = StatusCode.OK
            }

            // when
            handler.addLogboekContextToSpan(mockSpan, logboekContext)

            // then
            verify { mockSpan.setAttribute("dpl.core.processing_activity_id", "https://register.example.org/activiteiten/activity-123") }
            verify { mockSpan.setAttribute("dpl.core.data_subject_id", "subject-456") }
            verify { mockSpan.setAttribute("dpl.core.data_subject_id_type", "BSN") }
            verify { mockSpan.setStatus(StatusCode.OK) }
        }

        @Test
        fun `Throws when processingActivityId is null`() {
            val logboekContext = LogboekContext().apply {
                dataSubjectId = "subject-456"
                dataSubjectType = "BSN"
            }

            assertThrows<IllegalArgumentException> {
                handler.addLogboekContextToSpan(mockSpan, logboekContext)
            }
        }

        @Test
        fun `Throws when processingActivityId is empty`() {
            val logboekContext = LogboekContext().apply {
                processingActivityId = ""
                dataSubjectId = "subject-456"
                dataSubjectType = "BSN"
            }

            assertThrows<IllegalArgumentException> {
                handler.addLogboekContextToSpan(mockSpan, logboekContext)
            }
        }

        @Test
        fun `Throws when dataSubjectId is null`() {
            val logboekContext = LogboekContext().apply {
                processingActivityId = "https://register.example.org/activiteiten/activity-123"
                dataSubjectType = "BSN"
            }

            assertThrows<IllegalArgumentException> {
                handler.addLogboekContextToSpan(mockSpan, logboekContext)
            }
        }

        @Test
        fun `Throws when dataSubjectId is empty`() {
            val logboekContext = LogboekContext().apply {
                processingActivityId = "https://register.example.org/activiteiten/activity-123"
                dataSubjectId = ""
                dataSubjectType = "BSN"
            }

            assertThrows<IllegalArgumentException> {
                handler.addLogboekContextToSpan(mockSpan, logboekContext)
            }
        }

        @Test
        fun `Throws when dataSubjectType is null`() {
            val logboekContext = LogboekContext().apply {
                processingActivityId = "https://register.example.org/activiteiten/activity-123"
                dataSubjectId = "subject-456"
            }

            assertThrows<IllegalArgumentException> {
                handler.addLogboekContextToSpan(mockSpan, logboekContext)
            }
        }

        @Test
        fun `Throws when dataSubjectType is empty`() {
            val logboekContext = LogboekContext().apply {
                processingActivityId = "https://register.example.org/activiteiten/activity-123"
                dataSubjectId = "subject-456"
                dataSubjectType = ""
            }

            assertThrows<IllegalArgumentException> {
                handler.addLogboekContextToSpan(mockSpan, logboekContext)
            }
        }

        @Test
        fun `Throws when processingActivityId is not a valid URI`() {
            val logboekContext = LogboekContext().apply {
                processingActivityId = "not a valid uri {}"
                dataSubjectId = "subject-456"
                dataSubjectType = "BSN"
            }

            assertThrows<IllegalArgumentException> {
                handler.addLogboekContextToSpan(mockSpan, logboekContext)
            }
        }

        @Test
        fun `Throws when processingActivityId is a relative URI`() {
            val logboekContext = LogboekContext().apply {
                processingActivityId = "/activiteiten/activity-123"
                dataSubjectId = "subject-456"
                dataSubjectType = "BSN"
            }

            assertThrows<IllegalArgumentException> {
                handler.addLogboekContextToSpan(mockSpan, logboekContext)
            }
        }

        @Test
        fun `Sets error status when LogboekContext has error status`() {
            // given
            val logboekContext = LogboekContext().apply {
                processingActivityId = "https://register.example.org/activiteiten/activity-123"
                dataSubjectId = "subject-456"
                dataSubjectType = "BSN"
                status = StatusCode.ERROR
            }

            // when
            handler.addLogboekContextToSpan(mockSpan, logboekContext)

            // then
            verify { mockSpan.setStatus(StatusCode.ERROR) }
        }

        @Test
        fun `Propagating failure still sets all attributes but does not touch span status`() {
            // given
            val logboekContext = LogboekContext().apply {
                processingActivityId = "https://register.example.org/activiteiten/activity-123"
                dataSubjectId = "subject-456"
                dataSubjectType = "BSN"
                status = StatusCode.OK
            }

            // when
            handler.addLogboekContextToSpan(mockSpan, logboekContext, propagatingFailure = true)

            // then
            verify { mockSpan.setAttribute("dpl.core.processing_activity_id", "https://register.example.org/activiteiten/activity-123") }
            verify { mockSpan.setAttribute("dpl.core.data_subject_id", "subject-456") }
            verify { mockSpan.setAttribute("dpl.core.data_subject_id_type", "BSN") }
            verify(inverse = true) { mockSpan.setStatus(any<StatusCode>()) }
            verify(inverse = true) { mockSpan.setStatus(any<StatusCode>(), any<String>()) }
        }

        @Test
        fun `Emits a separate child logregel per betrokkene when multiple subjects`() {
            // given
            val logboekContext = LogboekContext().apply {
                processingActivityId = "https://register.example.org/activiteiten/activity-123"
                addSubject("subject-1", "BSN")
                addSubject("subject-2", "KVK")
                status = StatusCode.OK
            }

            // when
            handler.addLogboekContextToSpan(mockSpan, logboekContext)

            // then: one child span per betrokkene (action name absent here -> fallback name)
            verify(exactly = 2) { mockTracer.spanBuilder("verwerking-betrokkene") }
            verify { mockSpan.setAttribute("dpl.core.data_subject_id", "subject-1") }
            verify { mockSpan.setAttribute("dpl.core.data_subject_id_type", "BSN") }
            verify { mockSpan.setAttribute("dpl.core.data_subject_id", "subject-2") }
            verify { mockSpan.setAttribute("dpl.core.data_subject_id_type", "KVK") }
        }

        @Test
        fun `Throws when no betrokkene is present at all`() {
            val logboekContext = LogboekContext().apply {
                processingActivityId = "https://register.example.org/activiteiten/activity-123"
            }

            val e = assertThrows<IllegalArgumentException> {
                handler.addLogboekContextToSpan(mockSpan, logboekContext)
            }
            assert(e.message!!.contains("data_subject_id is required"))
        }

        @Test
        fun `Names the missing type when only the id is set`() {
            val logboekContext = LogboekContext().apply {
                processingActivityId = "https://register.example.org/activiteiten/activity-123"
                dataSubjectId = "subject-1"
            }

            val e = assertThrows<IllegalArgumentException> {
                handler.addLogboekContextToSpan(mockSpan, logboekContext)
            }
            assert(e.message!!.contains("data_subject_id_type is required"))
        }

        @Test
        fun `Propagating failure does not throw when context is incomplete`() {
            val logboekContext = LogboekContext().apply {
                dataSubjectId = "subject-456"
            }

            handler.addLogboekContextToSpan(mockSpan, logboekContext, propagatingFailure = true)

            // The present half of the pair is still applied; nothing throws.
            verify { mockSpan.setAttribute("dpl.core.data_subject_id", "subject-456") }
            verify(inverse = true) { mockSpan.setAttribute("dpl.core.processing_activity_id", any<String>()) }
            verify(inverse = true) { mockSpan.setAttribute("dpl.core.data_subject_id_type", any<String>()) }
        }

        @Test
        fun `Propagating failure does not validate processingActivityId as a URI`() {
            val logboekContext = LogboekContext().apply {
                processingActivityId = "not a valid uri {}"
                dataSubjectId = "subject-456"
                dataSubjectType = "BSN"
            }

            handler.addLogboekContextToSpan(mockSpan, logboekContext, propagatingFailure = true)

            verify { mockSpan.setAttribute("dpl.core.processing_activity_id", "not a valid uri {}") }
        }

        @Test
        fun `Propagating failure does not throw for a relative processingActivityId`() {
            val logboekContext = LogboekContext().apply {
                processingActivityId = "/activiteiten/activity-123"
                dataSubjectId = "subject-456"
                dataSubjectType = "BSN"
            }

            handler.addLogboekContextToSpan(mockSpan, logboekContext, propagatingFailure = true)

            verify { mockSpan.setAttribute("dpl.core.processing_activity_id", "/activiteiten/activity-123") }
        }

        @Test
        fun `Propagating failure marks child logregels ERROR for multiple subjects`() {
            val logboekContext = LogboekContext().apply {
                processingActivityId = "https://register.example.org/activiteiten/activity-123"
                addSubject("subject-1", "BSN")
                addSubject("subject-2", "KVK")
            }

            handler.addLogboekContextToSpan(mockSpan, logboekContext, propagatingFailure = true)

            // Child spans (the builder also returns mockSpan) each get ERROR; the parent's
            // status stays untouched because the interceptor owns it on this path.
            verify(exactly = 2) { mockTracer.spanBuilder("verwerking-betrokkene") }
            verify(exactly = 2) { mockSpan.setStatus(StatusCode.ERROR) }
            verify(inverse = true) { mockSpan.setStatus(StatusCode.UNSET) }
        }
    }

    @Nested
    @DisplayName("enforceWriteAcknowledgement")
    inner class EnforceWriteAcknowledgementTests {

        @AfterEach
        fun clearRecorder() = LogboekWriteFailureRecorder.clear()

        @Test
        fun `Throws LogboekWriteException when write failed and policy is fail-closed`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.write-failure-policy", String::class.java)
            } returns Optional.of("fail-closed")
            LogboekWriteFailureRecorder.record(RuntimeException("clickhouse down"))

            assertThrows<LogboekWriteException> { handler.enforceWriteAcknowledgement() }
        }

        @Test
        fun `Does not throw when write failed but policy is fail-open`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.write-failure-policy", String::class.java)
            } returns Optional.of("fail-open")
            LogboekWriteFailureRecorder.record(RuntimeException("clickhouse down"))

            handler.enforceWriteAcknowledgement()
        }

        @Test
        fun `Does not throw when no write failure was recorded`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.write-failure-policy", String::class.java)
            } returns Optional.of("fail-closed")

            handler.enforceWriteAcknowledgement()
        }

        @Test
        fun `Consumes the failure without throwing when throwOnFailure is false`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.write-failure-policy", String::class.java)
            } returns Optional.of("fail-closed")
            LogboekWriteFailureRecorder.record(RuntimeException("clickhouse down"))

            handler.enforceWriteAcknowledgement(throwOnFailure = false)

            // The failure was consumed, so a later check finds nothing to throw.
            handler.enforceWriteAcknowledgement(throwOnFailure = true)
        }
    }
}
