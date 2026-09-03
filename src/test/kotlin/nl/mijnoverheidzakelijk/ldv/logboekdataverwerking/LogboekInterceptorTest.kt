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
import io.opentelemetry.context.Scope
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanProcessor
import jakarta.interceptor.InvocationContext
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MultivaluedHashMap
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader
import nl.mijnoverheidzakelijk.ldv.exporter.LogboekWriteFailureRecorder
import org.eclipse.microprofile.config.Config
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.Optional

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class LogboekInterceptorTest {

    private lateinit var interceptor: LogboekInterceptor
    private lateinit var mockLogboekContext: LogboekContext
    private lateinit var mockHeaders: HttpHeaders
    private lateinit var mockHandler: ProcessingHandler
    private lateinit var mockInvocationContext: InvocationContext
    private lateinit var mockSpan: Span
    private lateinit var mockScope: Scope

    companion object {
        private lateinit var mockConfig: Config

        @JvmStatic
        @BeforeAll
        fun setUpClass() {
            // Mock ConfigurationLoader BEFORE ProcessingHandler is loaded
            mockConfig = mockk()
            every { mockConfig.getValue("logboekdataverwerking.service-name", String::class.java) } returns "test-service"
            every { mockConfig.getValue("logboekdataverwerking.enabled", Boolean::class.java) } returns false
            ConfigurationLoader.configProvider = { mockConfig }
        }

        @JvmStatic
        @AfterAll
        fun tearDownClass() {
            clearAllMocks()
        }
    }

    @BeforeEach
    fun setUp() {
        interceptor = LogboekInterceptor()

        // Create mocks
        mockLogboekContext = LogboekContext()
        mockHeaders = mockk()
        mockHandler = mockk(relaxed = true)
        mockInvocationContext = mockk()
        mockSpan = mockk(relaxed = true)
        mockScope = mockk(relaxed = true)

        // Inject mocks via reflection
        setPrivateField(interceptor, "logboekContext", mockLogboekContext)
        setPrivateField(interceptor, "headers", mockHeaders)
        setPrivateField(interceptor, "handler", mockHandler)

        // Set up common mock behaviors
        every { mockHandler.startSpan(any(), any()) } returns mockSpan
        every { mockSpan.makeCurrent() } returns mockScope
        every { mockHeaders.requestHeaders } returns MultivaluedHashMap()
        every { mockHeaders.getHeaderString(any()) } returns null

        // Re-stub each test: clearAllMocks() in tearDown wipes it, and the interceptor
        // reads this on the exception path. Default off.
        every {
            mockConfig.getOptionalValue("logboekdataverwerking.log-exception-stacktrace", String::class.java)
        } returns Optional.empty()
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    private fun setPrivateField(target: Any, fieldName: String, value: Any?) {
        val field: Field = target.javaClass.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }

    /**
     * Helper class with annotated methods for testing.
     * We use real methods instead of mocking java.lang.reflect.Method
     * because MockK cannot mock Method on JDK 25+.
     */
    private class AnnotatedMethods {
        @Logboek(name = "test-span", processingActivityId = "https://register.example.org/activiteiten/activity-123")
        fun testMethod() {}

        @Logboek(processingActivityId = "https://register.example.org/activiteiten/activity-123")
        fun emptyNameMethod() {}
    }

    private fun getAnnotatedMethod(): Method {
        return AnnotatedMethods::class.java.getDeclaredMethod("testMethod")
    }

    private fun getEmptyNameMethod(): Method {
        return AnnotatedMethods::class.java.getDeclaredMethod("emptyNameMethod")
    }

    @Nested
    @DisplayName("log interceptor method")
    inner class LogMethodTests {

        @Test
        fun `Successful invocation creates span and enriches with context`() {
            // given
            val mockMethod = getAnnotatedMethod()
            every { mockInvocationContext.method } returns mockMethod
            every { mockInvocationContext.proceed() } returns "result"

            // when
            val result = interceptor.log(mockInvocationContext)

            // then
            verify { mockHandler.startSpan("test-span", any()) }
            verify { mockSpan.makeCurrent() }
            verify { mockInvocationContext.proceed() }
            verify { mockHandler.addLogboekContextToSpan(mockSpan, mockLogboekContext, null) }
            verify { mockSpan.end() }
            assert(result == "result")
            assert(mockLogboekContext.processingActivityId == "https://register.example.org/activiteiten/activity-123")
        }

        @Test
        fun `Empty span name falls back to the method name`() {
            // given
            val mockMethod = getEmptyNameMethod()
            every { mockInvocationContext.method } returns mockMethod
            every { mockInvocationContext.proceed() } returns "result"

            // when
            interceptor.log(mockInvocationContext)

            // then: LDV 3.3.2.1, an empty name is auto-filled, never a runtime error
            verify { mockHandler.startSpan("emptyNameMethod", any()) }
        }

        @Test
        fun `Exception sets error status and rethrows`() {
            // given
            val mockMethod = getAnnotatedMethod()
            val testException = RuntimeException("Test exception")
            every { mockInvocationContext.method } returns mockMethod
            every { mockInvocationContext.proceed() } throws testException

            // when / then
            val thrown = assertThrows<RuntimeException> {
                interceptor.log(mockInvocationContext)
            }

            assert(thrown == testException)
            verify { mockSpan.setStatus(StatusCode.ERROR, "Test exception") }
            // The propagating exception is passed along: status must not be re-applied
            // from LogboekContext (an optimistic OK written by user code before the throw
            // would mask the ERROR) and an incomplete LogboekContext (e.g. because the
            // method body never ran) must not throw here and replace testException.
            verify { mockHandler.addLogboekContextToSpan(mockSpan, mockLogboekContext, testException) }
            verify { mockSpan.end() }
        }

        @Test
        fun `Exception preserves ERROR even when LogboekContext status was set to OK`() {
            // given
            val mockMethod = getAnnotatedMethod()
            mockLogboekContext.status = StatusCode.OK
            val kaboom = RuntimeException("kaboom")
            every { mockInvocationContext.method } returns mockMethod
            every { mockInvocationContext.proceed() } throws kaboom

            // when / then
            assertThrows<RuntimeException> { interceptor.log(mockInvocationContext) }

            verify { mockSpan.setStatus(StatusCode.ERROR, "kaboom") }
            verify { mockHandler.addLogboekContextToSpan(mockSpan, mockLogboekContext, kaboom) }
            // Without the propagating exception, setStatus(OK) from addLogboekContextToSpan
            // would be locked-in by OTel and prevent the ERROR set in the catch from sticking.
        }

        @Test
        fun `Announced exception gets no error status and no exception attributes`() {
            // given
            val mockMethod = getAnnotatedMethod()
            val nietGevonden = RuntimeException("niet gevonden")
            every { mockInvocationContext.method } returns mockMethod
            every { mockInvocationContext.proceed() } answers {
                mockLogboekContext.expectException(nietGevonden)
                throw nietGevonden
            }

            // when / then
            val thrown = assertThrows<RuntimeException> { interceptor.log(mockInvocationContext) }

            assert(thrown === nietGevonden) { "The exception must reach the caller unchanged" }
            assert(mockLogboekContext.status == StatusCode.UNSET) { "Default status is UNSET" }
            verify(exactly = 0) { mockSpan.setStatus(StatusCode.ERROR, any<String>()) }
            verify(exactly = 0) { mockSpan.setAttribute("exception.type", any<String>()) }
            // The exception is still handed to the handler, which filters it on identity.
            verify { mockHandler.addLogboekContextToSpan(mockSpan, mockLogboekContext, nietGevonden) }
            verify { mockSpan.end() }
        }

        @Test
        fun `Announced exception with an explicit status keeps that status`() {
            // given
            val mockMethod = getAnnotatedMethod()
            val geannuleerd = RuntimeException("geannuleerd")
            every { mockInvocationContext.method } returns mockMethod
            every { mockInvocationContext.proceed() } answers {
                mockLogboekContext.expectException(geannuleerd, StatusCode.OK)
                throw geannuleerd
            }

            // when / then
            assertThrows<RuntimeException> { interceptor.log(mockInvocationContext) }

            assert(mockLogboekContext.status == StatusCode.OK)
            verify(exactly = 0) { mockSpan.setStatus(StatusCode.ERROR, any<String>()) }
        }

        @Test
        fun `Another exception after an announcement is still an error`() {
            // given: the announced exception is caught inside the action, something else fails
            val mockMethod = getAnnotatedMethod()
            val kaboom = RuntimeException("kaboom")
            every { mockInvocationContext.method } returns mockMethod
            every { mockInvocationContext.proceed() } answers {
                mockLogboekContext.expectException(RuntimeException("niet gevonden"))
                throw kaboom
            }

            // when / then
            assertThrows<RuntimeException> { interceptor.log(mockInvocationContext) }

            verify { mockSpan.setStatus(StatusCode.ERROR, "kaboom") }
            verify { mockSpan.setAttribute("exception.type", RuntimeException::class.java.name) }
            verify { mockHandler.addLogboekContextToSpan(mockSpan, mockLogboekContext, kaboom) }
        }

        @Test
        fun `Announcement survives from a nested action into the enclosing action`() {
            // Real handler + real SDK so the nesting uses real context propagation: the
            // announced exception propagates through both actions and neither logregel
            // may become ERROR.
            val ended = mutableListOf<ReadableSpan>()
            val provider = SdkTracerProvider.builder()
                .addSpanProcessor(object : SpanProcessor {
                    override fun onStart(parentContext: io.opentelemetry.context.Context, span: ReadWriteSpan) {}
                    override fun isStartRequired(): Boolean = false
                    override fun onEnd(span: ReadableSpan) {
                        ended.add(span)
                    }
                    override fun isEndRequired(): Boolean = true
                })
                .build()
            val sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build()
            val realHandler = ProcessingHandler()
            setPrivateField(realHandler, "openTelemetry", sdk)
            setPrivateField(interceptor, "handler", realHandler)

            val outerCall = mockk<InvocationContext>()
            val innerCall = mockk<InvocationContext>()
            every { outerCall.method } returns getAnnotatedMethod()
            every { innerCall.method } returns getAnnotatedMethod()
            val nietGevonden = RuntimeException("niet gevonden")
            every { innerCall.proceed() } answers {
                mockLogboekContext.expectException(nietGevonden)
                throw nietGevonden
            }
            every { outerCall.proceed() } answers { interceptor.log(innerCall) }

            // when / then
            val thrown = assertThrows<RuntimeException> { interceptor.log(outerCall) }
            sdk.close()

            assert(thrown === nietGevonden) { "The exception must reach the caller unchanged" }
            assert(ended.size == 2)
            assert(ended.none { it.toSpanData().status.statusCode == StatusCode.ERROR }) {
                "An announced exception may not produce ERROR on any nesting level"
            }
        }

        @Test
        fun `A reused exception instance is not announced for a later action`() {
            // given: an announced action completes; the outermost action consumes the
            // announcement, so the same (e.g. cached) instance thrown later is a failure
            val mockMethod = getAnnotatedMethod()
            val nietGevonden = RuntimeException("niet gevonden")
            every { mockInvocationContext.method } returns mockMethod
            every { mockInvocationContext.proceed() } answers {
                mockLogboekContext.expectException(nietGevonden)
                throw nietGevonden
            }
            assertThrows<RuntimeException> { interceptor.log(mockInvocationContext) }
            assert(mockLogboekContext.expectedException == null) {
                "The outermost action must consume the announcement"
            }

            // when: a later action on the same request throws the same instance
            every { mockInvocationContext.proceed() } throws nietGevonden
            assertThrows<RuntimeException> { interceptor.log(mockInvocationContext) }

            // then
            verify { mockSpan.setStatus(StatusCode.ERROR, "niet gevonden") }
        }

        @Test
        fun `Announced exception still enforces fail-closed with the announcement as suppressed`() {
            // given: the verwerking ends in an expected outcome, but its logregel write
            // failed; the outcome may not silently count as logged
            val mockMethod = getAnnotatedMethod()
            val nietGevonden = RuntimeException("niet gevonden")
            every { mockInvocationContext.method } returns mockMethod
            every { mockInvocationContext.proceed() } answers {
                mockLogboekContext.expectException(nietGevonden)
                throw nietGevonden
            }
            every { mockHandler.enforceWriteAcknowledgement(throwOnFailure = true) } throws
                LogboekWriteException("Logregel kon niet in het Logboek worden opgeslagen")

            // when / then
            val thrown = assertThrows<LogboekWriteException> { interceptor.log(mockInvocationContext) }

            verify { mockHandler.enforceWriteAcknowledgement(throwOnFailure = true) }
            assert(thrown.suppressed.any { it === nietGevonden }) {
                "The announced outcome must travel as suppressed on the write failure"
            }
        }

        @Test
        fun `Exception with null message uses empty description`() {
            // given
            val mockMethod = getAnnotatedMethod()
            every { mockInvocationContext.method } returns mockMethod
            every { mockInvocationContext.proceed() } throws RuntimeException()

            // when / then
            assertThrows<RuntimeException> { interceptor.log(mockInvocationContext) }

            verify { mockSpan.setStatus(StatusCode.ERROR, "") }
        }

        @Test
        fun `Span is always ended even when exception occurs`() {
            // given
            val mockMethod = getAnnotatedMethod()
            val expectedException = RuntimeException("Test exception message")
            every { mockInvocationContext.method } returns mockMethod
            every { mockInvocationContext.proceed() } throws expectedException

            // when
            val thrown = assertThrows<RuntimeException> {
                interceptor.log(mockInvocationContext)
            }

            // then
            assert(thrown === expectedException) { "Expected the original exception to be rethrown" }
            assert(thrown.message == "Test exception message") { "Exception message should be preserved" }
            verify { mockSpan.end() }
        }

        @Test
        fun `Success path enforces write acknowledgement (throwing) after ending span`() {
            // given
            val mockMethod = getAnnotatedMethod()
            every { mockInvocationContext.method } returns mockMethod
            every { mockInvocationContext.proceed() } returns "result"

            // when
            interceptor.log(mockInvocationContext)

            // then
            verify { mockSpan.end() }
            verify { mockHandler.enforceWriteAcknowledgement(throwOnFailure = true) }
        }

        @Test
        fun `Exception path records type and message but enforces without throwing`() {
            // given
            val mockMethod = getAnnotatedMethod()
            every { mockInvocationContext.method } returns mockMethod
            every { mockInvocationContext.proceed() } throws IllegalStateException("boom")

            // when / then
            assertThrows<IllegalStateException> { interceptor.log(mockInvocationContext) }

            verify { mockSpan.setAttribute("exception.type", "java.lang.IllegalStateException") }
            verify { mockSpan.setAttribute("exception.message", "boom") }
            // throwOnFailure=false so a write failure cannot mask the business exception.
            verify { mockHandler.enforceWriteAcknowledgement(throwOnFailure = false) }
            // Stacktrace off by default (dataminimalisatie).
            verify(inverse = true) { mockSpan.setAttribute("exception.stacktrace", any<String>()) }
        }

        @Test
        fun `Stacktrace is recorded only when explicitly enabled`() {
            // given
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.log-exception-stacktrace", String::class.java)
            } returns Optional.of("true")
            val mockMethod = getAnnotatedMethod()
            every { mockInvocationContext.method } returns mockMethod
            every { mockInvocationContext.proceed() } throws IllegalStateException("boom")

            // when / then
            assertThrows<IllegalStateException> { interceptor.log(mockInvocationContext) }

            verify { mockSpan.setAttribute("exception.stacktrace", any<String>()) }
        }

        @Test
        fun `Original exception reaches the caller when context was never populated`() {
            // Real ProcessingHandler (with a mocked OpenTelemetry) instead of the relaxed
            // mock, so the enrichment in the finally block actually runs its validation.
            val realHandler = ProcessingHandler()
            val otel = mockk<OpenTelemetry>()
            val tracer = mockk<Tracer>()
            val spanBuilder = mockk<SpanBuilder>()
            every { otel.getTracer(any()) } returns tracer
            every { tracer.spanBuilder(any()) } returns spanBuilder
            every { spanBuilder.setParent(any()) } returns spanBuilder
            every { spanBuilder.startSpan() } returns mockSpan
            setPrivateField(realHandler, "openTelemetry", otel)
            setPrivateField(interceptor, "handler", realHandler)

            val mockMethod = getAnnotatedMethod()
            every { mockInvocationContext.method } returns mockMethod
            val original = IllegalStateException("business failure")
            every { mockInvocationContext.proceed() } throws original

            // when / then: the never-populated LogboekContext must not produce an
            // IllegalArgumentException that replaces the business exception.
            val thrown = assertThrows<IllegalStateException> { interceptor.log(mockInvocationContext) }
            assert(thrown === original)
            verify { mockSpan.end() }
        }

        @Test
        fun `Nested action parents to the enclosing action, not the inbound traceparent`() {
            // Real handler + real SDK so spans get real ids and context propagation.
            val ended = mutableListOf<ReadableSpan>()
            val provider = SdkTracerProvider.builder()
                .addSpanProcessor(object : SpanProcessor {
                    override fun onStart(parentContext: io.opentelemetry.context.Context, span: ReadWriteSpan) {}
                    override fun isStartRequired(): Boolean = false
                    override fun onEnd(span: ReadableSpan) {
                        ended.add(span)
                    }
                    override fun isEndRequired(): Boolean = true
                })
                .build()
            val sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build()
            val realHandler = ProcessingHandler()
            setPrivateField(realHandler, "openTelemetry", sdk)
            setPrivateField(interceptor, "handler", realHandler)
            every { mockHeaders.getHeaderString("traceparent") } returns
                "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"

            val outerCall = mockk<InvocationContext>()
            val innerCall = mockk<InvocationContext>()
            every { outerCall.method } returns getAnnotatedMethod()
            every { innerCall.method } returns getAnnotatedMethod()
            every { innerCall.proceed() } returns "inner"
            every { outerCall.proceed() } answers { interceptor.log(innerCall) }

            interceptor.log(outerCall)
            sdk.close()

            assert(ended.size == 2)
            val inner = ended[0]
            val outer = ended[1]
            assert(outer.spanContext.traceId == "0af7651916cd43dd8448eb211c80319c")
            assert(outer.parentSpanContext.spanId == "b7ad6b7169203331")
            assert(inner.spanContext.traceId == outer.spanContext.traceId)
            // The nested action's parent is the enclosing local action, not the remote caller.
            assert(inner.parentSpanContext.spanId == outer.spanContext.spanId)
        }

        @Test
        fun `Nested write failure is enforced at the outermost action, not inside business code`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.write-failure-policy", String::class.java)
            } returns Optional.of("fail-closed")
            // Real handler + real SDK so nesting uses real context propagation.
            val sdk = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder().build()).build()
            val realHandler = ProcessingHandler()
            setPrivateField(realHandler, "openTelemetry", sdk)
            setPrivateField(interceptor, "handler", realHandler)

            val outerCall = mockk<InvocationContext>()
            val innerCall = mockk<InvocationContext>()
            every { outerCall.method } returns getAnnotatedMethod()
            every { innerCall.method } returns getAnnotatedMethod()
            every { innerCall.proceed() } answers {
                // Simulates the exporter recording a failed logregel write for the
                // nested action on this thread.
                LogboekWriteFailureRecorder.record(RuntimeException("clickhouse down"))
                "inner"
            }
            var innerResult: Any? = null
            var outerBusinessCompleted = false
            every { outerCall.proceed() } answers {
                innerResult = interceptor.log(innerCall)
                outerBusinessCompleted = true
                "outer"
            }

            // when / then: the failure surfaces only at the request boundary.
            assertThrows<LogboekWriteException> { interceptor.log(outerCall) }

            // The nested action returned normally and the outer business code ran to
            // completion; nothing threw inside business code where a catch-block could
            // have swallowed the guarantee.
            assert(innerResult == "inner")
            assert(outerBusinessCompleted)
        }

        @Test
        fun `Nested action does not wipe a failure recorded by an earlier sibling`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.write-failure-policy", String::class.java)
            } returns Optional.of("fail-closed")
            val sdk = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder().build()).build()
            val realHandler = ProcessingHandler()
            setPrivateField(realHandler, "openTelemetry", sdk)
            setPrivateField(interceptor, "handler", realHandler)

            val outerCall = mockk<InvocationContext>()
            val failingSibling = mockk<InvocationContext>()
            val succeedingSibling = mockk<InvocationContext>()
            every { outerCall.method } returns getAnnotatedMethod()
            every { failingSibling.method } returns getAnnotatedMethod()
            every { succeedingSibling.method } returns getAnnotatedMethod()
            every { failingSibling.proceed() } answers {
                LogboekWriteFailureRecorder.record(RuntimeException("clickhouse down"))
                "first"
            }
            every { succeedingSibling.proceed() } returns "second"
            every { outerCall.proceed() } answers {
                interceptor.log(failingSibling)
                // A second sibling must not clear the recorder on entry, or the first
                // sibling's failure would be lost and the request would report success.
                interceptor.log(succeedingSibling)
                "outer"
            }

            assertThrows<LogboekWriteException> { interceptor.log(outerCall) }
        }

        @Test
        fun `Action on another thread with propagated context enforces on its own thread`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.write-failure-policy", String::class.java)
            } returns Optional.of("fail-closed")
            val sdk = OpenTelemetrySdk.builder().setTracerProvider(SdkTracerProvider.builder().build()).build()
            val realHandler = ProcessingHandler()
            setPrivateField(realHandler, "openTelemetry", sdk)
            setPrivateField(interceptor, "handler", realHandler)

            val outerCall = mockk<InvocationContext>()
            val innerCall = mockk<InvocationContext>()
            every { outerCall.method } returns getAnnotatedMethod()
            every { innerCall.method } returns getAnnotatedMethod()
            every { innerCall.proceed() } answers {
                LogboekWriteFailureRecorder.record(RuntimeException("clickhouse down"))
                "inner"
            }
            var onWorkerThread: Throwable? = null
            every { outerCall.proceed() } answers {
                // Propagates the OTel context to a worker thread, like context-propagating
                // executors do. The recorder is thread-bound, so the worker must enforce
                // fail-closed itself; deferring to the outer action would silently lose
                // the failure.
                val propagated = io.opentelemetry.context.Context.current()
                val worker = Thread {
                    propagated.makeCurrent().use { _ ->
                        onWorkerThread = runCatching { interceptor.log(innerCall) }.exceptionOrNull()
                    }
                }
                worker.start()
                worker.join()
                "outer"
            }

            // The outer action completes normally: the failure was enforced on the
            // worker thread, not deferred to a boundary that cannot see it.
            val result = interceptor.log(outerCall)

            assert(result == "outer")
            assert(onWorkerThread is LogboekWriteException)
        }

        @Test
        fun `Returns null when method returns null`() {
            // given
            val mockMethod = getAnnotatedMethod()
            every { mockInvocationContext.method } returns mockMethod
            every { mockInvocationContext.proceed() } returns null

            // when
            val result = interceptor.log(mockInvocationContext)

            // then
            assert(result == null)
        }
    }

    @Nested
    @DisplayName("foreign_operation.processor handling")
    inner class ForeignOperationProcessorTests {

        // The earlier inbound-side handling of dpl.core.foreign_operation.processor was
        // semantically inverted (the spec defines it for the outbound side) and pulled
        // its URL from a non-standard `traceparent-processor` header. The interceptor
        // no longer sets the attribute; application code is responsible for setting it
        // on the outbound side when calling another organisatie.

        @Test
        fun `Does not set foreign_operation processor even when traceparent header is present`() {
            // given
            val mockMethod = getAnnotatedMethod()
            every { mockInvocationContext.method } returns mockMethod
            every { mockInvocationContext.proceed() } returns "result"
            every { mockHeaders.getHeaderString("traceparent") } returns "00-trace-id-span-id-01"
            every { mockHeaders.getHeaderString("traceparent-processor") } returns "http://processor.example.com"

            // when
            interceptor.log(mockInvocationContext)

            // then
            verify(inverse = true) { mockSpan.setAttribute("dpl.core.foreign_operation.processor", any<String>()) }
        }

        @Test
        fun `Does not set foreign_operation processor when traceparent header absent`() {
            // given
            val mockMethod = getAnnotatedMethod()
            every { mockInvocationContext.method } returns mockMethod
            every { mockInvocationContext.proceed() } returns "result"
            every { mockHeaders.getHeaderString("traceparent") } returns null

            // when
            interceptor.log(mockInvocationContext)

            // then
            verify(inverse = true) { mockSpan.setAttribute("dpl.core.foreign_operation.processor", any<String>()) }
        }
    }

    @Nested
    @DisplayName("HttpHeadersGetter inner class")
    inner class HttpHeadersGetterTests {

        @Test
        fun `Keys returns header names from HttpHeaders`() {
            // given
            val headerMap = MultivaluedHashMap<String, String>()
            headerMap["traceparent"] = listOf("value1")
            headerMap["tracestate"] = listOf("value2")
            every { mockHeaders.requestHeaders } returns headerMap

            // Access the inner class via reflection
            val getterClass = Class.forName("nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekInterceptor\$HttpHeadersGetter")
            val getter = getterClass.getDeclaredConstructor().newInstance()
            val keysMethod = getterClass.getMethod("keys", HttpHeaders::class.java)

            // when
            val result = keysMethod.invoke(getter, mockHeaders) as Iterable<*>

            // then
            val keyList = result.map { it as String }
            assert(keyList.contains("traceparent"))
            assert(keyList.contains("tracestate"))
        }

        @Test
        fun `Get returns header value for given key`() {
            // given
            every { mockHeaders.getHeaderString("traceparent") } returns "test-value"

            // Access the inner class via reflection
            val getterClass = Class.forName("nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekInterceptor\$HttpHeadersGetter")
            val getter = getterClass.getDeclaredConstructor().newInstance()
            val getMethod = getterClass.getMethod("get", HttpHeaders::class.java, String::class.java)

            // when
            val value = getMethod.invoke(getter, mockHeaders, "traceparent") as String?

            // then
            assert(value == "test-value")
        }

        @Test
        fun `Get returns null for absent header`() {
            // given
            every { mockHeaders.getHeaderString("missing") } returns null

            // Access the inner class via reflection
            val getterClass = Class.forName("nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekInterceptor\$HttpHeadersGetter")
            val getter = getterClass.getDeclaredConstructor().newInstance()
            val getMethod = getterClass.getMethod("get", HttpHeaders::class.java, String::class.java)

            // when
            val value = getMethod.invoke(getter, mockHeaders, "missing") as String?

            // then
            assert(value == null)
        }

        @Test
        fun `Get throws exception when httpHeaders is null`() {
            // Access the inner class via reflection
            val getterClass = Class.forName("nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekInterceptor\$HttpHeadersGetter")
            val getter = getterClass.getDeclaredConstructor().newInstance()
            val getMethod = getterClass.getMethod("get", HttpHeaders::class.java, String::class.java)

            // when / then
            assertThrows<java.lang.reflect.InvocationTargetException> {
                getMethod.invoke(getter, null, "key")
            }
        }
    }
}
