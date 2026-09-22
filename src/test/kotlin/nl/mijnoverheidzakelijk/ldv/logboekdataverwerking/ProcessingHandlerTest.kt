package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader
import nl.mijnoverheidzakelijk.ldv.exporter.LdvSpanFilterProcessor
import nl.mijnoverheidzakelijk.ldv.exporter.LogboekWriteFailureRecorder
import java.util.Optional
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
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

        @Test
        fun `batch with fail-closed warns that the policy degrades to log-only`() {
            every { cfg.getValue("logboekdataverwerking.enabled", Boolean::class.java) } returns true
            every { cfg.getOptionalValue("logboekdataverwerking.dbms", String::class.java) } returns Optional.of("postgresql")
            every {
                cfg.getValue(match<String> { it.startsWith("logboekdataverwerking.postgresql.") }, String::class.java)
            } returns "x"
            every {
                cfg.getOptionalValue("logboekdataverwerking.postgresql.connection-validation-timeout-seconds", String::class.java)
            } returns Optional.empty()
            every { cfg.getOptionalValue("logboekdataverwerking.span-processor", String::class.java) } returns Optional.of("batch")
            every { cfg.getOptionalValue("logboekdataverwerking.write-failure-policy", String::class.java) } returns Optional.of("fail-closed")

            val records = mutableListOf<LogRecord>()
            val capture = object : Handler() {
                override fun publish(record: LogRecord) { records.add(record) }
                override fun flush() {}
                override fun close() {}
            }
            val logger = Logger.getLogger(ProcessingHandler::class.java.name)
            logger.addHandler(capture)
            try {
                // The exporter validates the schema at construction and fails on the
                // dummy connection config; that happens after the warning, which is
                // all this test is about, so the failure is tolerated.
                runCatching { ProcessingHandler.buildLdvSpanProcessor() }
            } finally {
                logger.removeHandler(capture)
            }

            assert(records.any {
                it.level == Level.WARNING && it.message.contains("fail-closed has no effect under span-processor=batch")
            })
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
        fun `Missing processingActivityId is exported without the attribute`() {
            val logboekContext = LogboekContext().apply {
                dataSubjectId = "subject-456"
                dataSubjectType = "BSN"
                status = StatusCode.OK
            }

            handler.addLogboekContextToSpan(mockSpan, logboekContext)

            // LDV 3.3.2.1: warn, never throw; the rest of the logregel still exports.
            verify(inverse = true) { mockSpan.setAttribute("dpl.core.processing_activity_id", any<String>()) }
            verify { mockSpan.setAttribute("dpl.core.data_subject_id", "subject-456") }
            verify { mockSpan.setAttribute("dpl.core.data_subject_id_type", "BSN") }
            verify { mockSpan.setStatus(StatusCode.OK) }
        }

        @Test
        fun `Half-set betrokkene pair applies only the present half`() {
            val logboekContext = LogboekContext().apply {
                processingActivityId = "https://register.example.org/activiteiten/activity-123"
                dataSubjectId = "subject-1"
            }

            handler.addLogboekContextToSpan(mockSpan, logboekContext)

            verify { mockSpan.setAttribute("dpl.core.data_subject_id", "subject-1") }
            verify(inverse = true) { mockSpan.setAttribute("dpl.core.data_subject_id_type", any<String>()) }
        }

        @Test
        fun `Invalid processingActivityId URI is exported as-is`() {
            val logboekContext = LogboekContext().apply {
                processingActivityId = "not a valid uri {}"
                dataSubjectId = "subject-456"
                dataSubjectType = "BSN"
            }

            handler.addLogboekContextToSpan(mockSpan, logboekContext)

            verify { mockSpan.setAttribute("dpl.core.processing_activity_id", "not a valid uri {}") }
        }

        @Test
        fun `Relative processingActivityId is exported as-is`() {
            val logboekContext = LogboekContext().apply {
                processingActivityId = "/activiteiten/activity-123"
                dataSubjectId = "subject-456"
                dataSubjectType = "BSN"
            }

            handler.addLogboekContextToSpan(mockSpan, logboekContext)

            verify { mockSpan.setAttribute("dpl.core.processing_activity_id", "/activiteiten/activity-123") }
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
            handler.addLogboekContextToSpan(mockSpan, logboekContext, propagatingException = RuntimeException("boom"))

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
        fun `No betrokkene exports the logregel without subject attributes`() {
            // Valid per the standard (0 of 1 betrokkenen per logregel); warned so
            // forgotten context in persoonsgegevens verwerkingen is still spotted.
            val logboekContext = LogboekContext().apply {
                processingActivityId = "https://register.example.org/activiteiten/activity-123"
                status = StatusCode.OK
            }

            handler.addLogboekContextToSpan(mockSpan, logboekContext)

            verify { mockSpan.setAttribute("dpl.core.processing_activity_id", "https://register.example.org/activiteiten/activity-123") }
            verify { mockSpan.setStatus(StatusCode.OK) }
            verify(inverse = true) { mockSpan.setAttribute("dpl.core.data_subject_id", any<String>()) }
            verify(inverse = true) { mockSpan.setAttribute("dpl.core.data_subject_id_type", any<String>()) }
        }

        @Test
        fun `Propagating failure marks child logregels ERROR with exception attributes for multiple subjects`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.log-exception-stacktrace", String::class.java)
            } returns Optional.empty()
            val logboekContext = LogboekContext().apply {
                processingActivityId = "https://register.example.org/activiteiten/activity-123"
                addSubject("subject-1", "BSN")
                addSubject("subject-2", "KVK")
            }

            // Distinct child mocks so an ERROR wrongly landing on the parent (or missing
            // on one child) cannot hide behind a shared-mock call count.
            val child1 = mockk<Span>(relaxed = true)
            val child2 = mockk<Span>(relaxed = true)
            every { mockSpanBuilder.startSpan() } returnsMany listOf(child1, child2)

            handler.addLogboekContextToSpan(mockSpan, logboekContext, propagatingException = IllegalStateException("boom"))

            // Each per-betrokkene logregel carries the failure detail; the parent's
            // status stays untouched because the interceptor owns it on this path.
            verify(exactly = 2) { mockTracer.spanBuilder("verwerking-betrokkene") }
            listOf(child1, child2).forEach { child ->
                verify { child.setStatus(StatusCode.ERROR) }
                verify { child.setAttribute("exception.type", "java.lang.IllegalStateException") }
                verify { child.setAttribute("exception.message", "boom") }
                // Stacktrace off by default (dataminimalisatie).
                verify(inverse = true) { child.setAttribute("exception.stacktrace", any<String>()) }
            }
            verify(inverse = true) { mockSpan.setStatus(any()) }
        }

        @Test
        fun `Announced exception applies the context status instead of ERROR`() {
            val nietGevonden = IllegalStateException("niet gevonden")
            val logboekContext = LogboekContext().apply {
                processingActivityId = "https://register.example.org/activiteiten/activity-123"
                dataSubjectId = "subject-456"
                dataSubjectType = "BSN"
                expectException(nietGevonden)
            }

            handler.addLogboekContextToSpan(mockSpan, logboekContext, propagatingException = nietGevonden)

            verify { mockSpan.setStatus(StatusCode.UNSET) }
            verify(inverse = true) { mockSpan.setAttribute("exception.type", any<String>()) }
            verify(inverse = true) { mockSpan.setAttribute("exception.message", any<String>()) }
        }

        @Test
        fun `Announced exception leaves child logregels out of ERROR`() {
            val nietGevonden = IllegalStateException("niet gevonden")
            val logboekContext = LogboekContext().apply {
                processingActivityId = "https://register.example.org/activiteiten/activity-123"
                addSubject("subject-1", "BSN")
                addSubject("subject-2", "KVK")
                expectException(nietGevonden)
            }
            val child1 = mockk<Span>(relaxed = true)
            val child2 = mockk<Span>(relaxed = true)
            every { mockSpanBuilder.startSpan() } returnsMany listOf(child1, child2)

            handler.addLogboekContextToSpan(mockSpan, logboekContext, propagatingException = nietGevonden)

            verify(exactly = 2) { mockTracer.spanBuilder("verwerking-betrokkene") }
            verify { mockSpan.setStatus(StatusCode.UNSET) }
            listOf(child1, child2).forEach { child ->
                verify { child.setStatus(StatusCode.UNSET) }
                verify(inverse = true) { child.setStatus(StatusCode.ERROR) }
                verify(inverse = true) { child.setAttribute("exception.type", any<String>()) }
            }
        }

        @Test
        fun `A different exception than the announced one is still a failure`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.log-exception-stacktrace", String::class.java)
            } returns Optional.empty()
            val logboekContext = LogboekContext().apply {
                processingActivityId = "https://register.example.org/activiteiten/activity-123"
                addSubject("subject-1", "BSN")
                addSubject("subject-2", "KVK")
                expectException(IllegalStateException("niet gevonden"))
            }
            val child1 = mockk<Span>(relaxed = true)
            val child2 = mockk<Span>(relaxed = true)
            every { mockSpanBuilder.startSpan() } returnsMany listOf(child1, child2)

            handler.addLogboekContextToSpan(mockSpan, logboekContext, propagatingException = RuntimeException("kaboom"))

            listOf(child1, child2).forEach { child ->
                verify { child.setStatus(StatusCode.ERROR) }
                verify { child.setAttribute("exception.type", "java.lang.RuntimeException") }
            }
            verify(inverse = true) { mockSpan.setStatus(any()) }
        }

        @Test
        fun `Returns the action span as the logregel for a single betrokkene`() {
            val spanContext = spanContext("b7ad6b7169203331")
            every { mockSpan.spanContext } returns spanContext
            val logboekContext = LogboekContext().apply {
                processingActivityId = "https://register.example.org/activiteiten/activity-123"
                dataSubjectId = "subject-1"
                dataSubjectType = "BSN"
                actionName = "aanleveren"
            }

            val logregels = handler.addLogboekContextToSpan(mockSpan, logboekContext)

            assert(
                logregels == listOf(
                    Logregel(
                        spanContext,
                        "aanleveren",
                        "https://register.example.org/activiteiten/activity-123",
                        DataSubject("subject-1", "BSN"),
                    )
                )
            ) { "got $logregels" }
        }

        @Test
        fun `Returns a logregel without betrokkene when none is set`() {
            val logboekContext = LogboekContext().apply {
                processingActivityId = "https://register.example.org/activiteiten/activity-123"
            }

            val logregels = handler.addLogboekContextToSpan(mockSpan, logboekContext)

            assert(logregels.single().subject == null)
        }

        @Test
        fun `Returns the action logregel and one per betrokkene-child for multiple betrokkenen`() {
            every { mockSpan.spanContext } returns spanContext("aaaaaaaaaaaaaaaa")
            val logboekContext = LogboekContext().apply {
                addSubject("subject-1", "BSN")
                addSubject("subject-2", "KVK")
            }
            val child1 = mockk<Span>(relaxed = true)
            val child2 = mockk<Span>(relaxed = true)
            every { child1.spanContext } returns spanContext("00f067aa0ba902b7")
            every { child2.spanContext } returns spanContext("b7ad6b7169203331")
            every { mockSpanBuilder.startSpan() } returnsMany listOf(child1, child2)

            val logregels = handler.addLogboekContextToSpan(mockSpan, logboekContext)

            assert(
                logregels.map { it.spanContext.spanId } ==
                    listOf("aaaaaaaaaaaaaaaa", "00f067aa0ba902b7", "b7ad6b7169203331")
            ) { "the action logregel leads, so it gets an outcome too; got $logregels" }
            assert(logregels.first().subject == null) { "the action logregel stays subject-less" }
            assert(
                logregels.drop(1).map { it.subject } ==
                    listOf(DataSubject("subject-1", "BSN"), DataSubject("subject-2", "KVK"))
            )
            assert(logregels.all { it.name == "verwerking-betrokkene" && it.processingActivityId == null })
        }
    }

    @Nested
    @DisplayName("recordFailedOutcome")
    inner class RecordFailedOutcomeTests {

        private val original = spanContext("b7ad6b7169203331")
        private val activity = "https://register.example.org/activiteiten/activity-123"

        @BeforeEach
        fun stacktraceOffByDefault() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.log-exception-stacktrace", String::class.java)
            } returns Optional.empty()
        }

        @AfterEach
        fun clearRecorder() = LogboekWriteFailureRecorder.clear()

        @Test
        fun `Writes one ERROR logregel under the original with the same LDV fields`() {
            val parent = slot<Context>()
            every { mockSpanBuilder.setParent(capture(parent)) } returns mockSpanBuilder

            handler.recordFailedOutcome(
                Logregel(original, "aanleveren", activity, DataSubject("subject-1", "BSN")),
                IllegalStateException("levering mislukt"),
            )

            verify { mockTracer.spanBuilder("aanleveren") }
            assert(Span.fromContext(parent.captured).spanContext == original) { "parent must be the original logregel" }
            verify { mockSpan.setAttribute(ProcessingHandler.OUTCOME_ATTRIBUTE_KEY, ProcessingHandler.OUTCOME_FAILED) }
            verify { mockSpan.setAttribute("dpl.core.processing_activity_id", activity) }
            verify { mockSpan.setAttribute("dpl.core.data_subject_id", "subject-1") }
            verify { mockSpan.setAttribute("dpl.core.data_subject_id_type", "BSN") }
            verify { mockSpan.setStatus(StatusCode.ERROR, "levering mislukt") }
            verify { mockSpan.setAttribute("exception.type", "java.lang.IllegalStateException") }
            verify { mockSpan.setAttribute("exception.message", "levering mislukt") }
            verify(inverse = true) { mockSpan.setAttribute("exception.stacktrace", any<String>()) }
            verify { mockSpan.end() }
        }

        @Test
        fun `Writes one outcome logregel per betrokkene-logregel`() {
            val second = spanContext("00f067aa0ba902b7")
            val out1 = mockk<Span>(relaxed = true)
            val out2 = mockk<Span>(relaxed = true)
            every { mockSpanBuilder.startSpan() } returnsMany listOf(out1, out2)
            val parents = mutableListOf<Context>()
            every { mockSpanBuilder.setParent(capture(parents)) } returns mockSpanBuilder

            handler.recordFailedOutcome(
                listOf(
                    Logregel(original, "verwerking", null, DataSubject("subject-1", "BSN")),
                    Logregel(second, "verwerking", null, DataSubject("subject-2", "KVK")),
                ),
                RuntimeException("boom"),
            )

            assert(parents.map { Span.fromContext(it).spanContext } == listOf(original, second))
            verify { out1.setAttribute("dpl.core.data_subject_id", "subject-1") }
            verify { out2.setAttribute("dpl.core.data_subject_id", "subject-2") }
            verify(inverse = true) { out1.setAttribute("dpl.core.processing_activity_id", any<String>()) }
            listOf(out1, out2).forEach { out ->
                verify { out.setStatus(StatusCode.ERROR, "boom") }
                verify { out.end() }
            }
        }

        @Test
        fun `Stores the stacktrace on opt-in`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.log-exception-stacktrace", String::class.java)
            } returns Optional.of("true")

            handler.recordFailedOutcome(Logregel(original, "aanleveren", null, null), IllegalStateException("x"))

            verify { mockSpan.setAttribute("exception.stacktrace", match<String> { it.contains("IllegalStateException") }) }
        }

        @Test
        fun `A write failure of the outcome logregel is logged, not thrown`() {
            every { mockSpan.end() } answers { LogboekWriteFailureRecorder.record(RuntimeException("postgres down")) }

            val records = captureProcessingHandlerLogs {
                handler.recordFailedOutcome(Logregel(original, "aanleveren", null, null), IllegalStateException("x"))
            }

            assert(records.any {
                it.level == Level.SEVERE && it.message.contains("${original.traceId}:${original.spanId}")
            }) { "expected a SEVERE pointing at the original logregel, got ${records.map { it.message }}" }
            assert(LogboekWriteFailureRecorder.consume() == null) { "failure must be consumed, not left for a later action" }
        }

        @Test
        fun `The write failure names only the logregel whose outcome was lost`() {
            val second = spanContext("00f067aa0ba902b7")
            val out1 = mockk<Span>(relaxed = true)
            val out2 = mockk<Span>(relaxed = true)
            every { mockSpanBuilder.startSpan() } returnsMany listOf(out1, out2)
            every { out2.end() } answers { LogboekWriteFailureRecorder.record(RuntimeException("postgres down")) }

            val records = captureProcessingHandlerLogs {
                handler.recordFailedOutcome(
                    listOf(
                        Logregel(original, "verwerking", null, null),
                        Logregel(second, "verwerking", null, null),
                    ),
                    IllegalStateException("x"),
                )
            }

            val severe = records.single { it.level == Level.SEVERE }
            assert(severe.message.contains("${second.traceId}:${second.spanId}"))
            assert(!severe.message.contains(original.spanId)) { "the stored outcome must not be reported as lost" }
        }

        @Test
        fun `Warns when the logregel has no valid span context`() {
            val records = captureProcessingHandlerLogs {
                handler.recordFailedOutcome(
                    Logregel(SpanContext.getInvalid(), "aanleveren", null, null),
                    IllegalStateException("x"),
                )
            }

            assert(records.any { it.level == Level.WARNING && it.message.contains("unlinked") }) {
                "expected a warning about the unlinked outcome, got ${records.map { it.message }}"
            }
        }

        @Test
        fun `Preserves a write failure an enclosing action left recorded`() {
            val enclosing = RuntimeException("nested logregel not stored")
            LogboekWriteFailureRecorder.record(enclosing)

            handler.recordFailedOutcome(Logregel(original, "aanleveren", null, null), IllegalStateException("x"))

            assert(LogboekWriteFailureRecorder.consume() === enclosing)
        }

        @Test
        fun `An unreadable stacktrace setting neither throws nor loses an enclosing failure`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.log-exception-stacktrace", String::class.java)
            } returns Optional.of("ja")
            val enclosing = RuntimeException("nested logregel not stored")
            LogboekWriteFailureRecorder.record(enclosing)

            val records = captureProcessingHandlerLogs {
                handler.recordFailedOutcome(Logregel(original, "aanleveren", null, null), IllegalStateException("x"))
            }

            assert(records.any { it.level == Level.SEVERE }) { "the lost outcome must be reported" }
            assert(LogboekWriteFailureRecorder.consume() === enclosing) { "the enclosing action keeps its failure" }
        }

        private fun captureProcessingHandlerLogs(block: () -> Unit): List<LogRecord> {
            val records = mutableListOf<LogRecord>()
            val capture = object : Handler() {
                override fun publish(record: LogRecord) { records.add(record) }
                override fun flush() {}
                override fun close() {}
            }
            val logger = Logger.getLogger(ProcessingHandler::class.java.name)
            logger.addHandler(capture)
            try {
                block()
            } finally {
                logger.removeHandler(capture)
            }
            return records
        }
    }

    private fun spanContext(spanId: String): SpanContext = SpanContext.create(
        "0af7651916cd43dd8448eb211c80319c",
        spanId,
        TraceFlags.getSampled(),
        TraceState.getDefault(),
    )

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
