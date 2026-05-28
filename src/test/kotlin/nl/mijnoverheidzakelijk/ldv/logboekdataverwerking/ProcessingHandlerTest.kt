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
import jakarta.enterprise.inject.Instance
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader
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

        // Mock CDI Instance to provide the mock OpenTelemetry
        val mockInstance = mockk<Instance<OpenTelemetry>>()
        every { mockInstance.isResolvable } returns true
        every { mockInstance.get() } returns mockOpenTelemetry

        // Create handler and inject mock via reflection
        handler = ProcessingHandler()
        val field = ProcessingHandler::class.java.getDeclaredField("openTelemetryInstance")
        field.isAccessible = true
        field.set(handler, mockInstance)
        handler.init()
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
        fun `StartSpan uses correct service name for tracer`() {
            // when
            handler.startSpan("any-span", null)

            // then
            verify { mockOpenTelemetry.getTracer(ProcessingHandler.serviceName) }
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
        fun `setStatus=false applies attributes but does not touch span status`() {
            // given
            val logboekContext = LogboekContext().apply {
                processingActivityId = "https://register.example.org/activiteiten/activity-123"
                dataSubjectId = "subject-456"
                dataSubjectType = "BSN"
                status = StatusCode.OK
            }

            // when
            handler.addLogboekContextToSpan(mockSpan, logboekContext, setStatus = false)

            // then
            verify { mockSpan.setAttribute("dpl.core.processing_activity_id", "https://register.example.org/activiteiten/activity-123") }
            verify { mockSpan.setAttribute("dpl.core.data_subject_id", "subject-456") }
            verify { mockSpan.setAttribute("dpl.core.data_subject_id_type", "BSN") }
            verify(inverse = true) { mockSpan.setStatus(any<StatusCode>()) }
            verify(inverse = true) { mockSpan.setStatus(any<StatusCode>(), any<String>()) }
        }
    }
}
