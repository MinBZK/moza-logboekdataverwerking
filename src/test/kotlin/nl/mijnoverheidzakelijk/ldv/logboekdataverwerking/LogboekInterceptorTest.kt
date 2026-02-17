package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Scope
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.data.SpanData
import jakarta.interceptor.InvocationContext
import jakarta.ws.rs.core.HttpHeaders
import jakarta.ws.rs.core.MultivaluedHashMap
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader
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

/**
 * Interface combining Span and ReadableSpan for testing purposes.
 * This allows us to mock both interfaces in a single mock object.
 */
private interface TestableSpan : Span, ReadableSpan

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class LogboekInterceptorTest {

    private lateinit var interceptor: LogboekInterceptor
    private lateinit var mockLogboekContext: LogboekContext
    private lateinit var mockHeaders: HttpHeaders
    private lateinit var mockHandler: ProcessingHandler
    private lateinit var mockInvocationContext: InvocationContext
    private lateinit var mockSpan: TestableSpan
    private lateinit var mockScope: Scope
    private lateinit var mockSpanData: SpanData

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
        mockSpanData = mockk()

        // Inject mocks via reflection
        setPrivateField(interceptor, "logboekContext", mockLogboekContext)
        setPrivateField(interceptor, "headers", mockHeaders)
        setPrivateField(interceptor, "handler", mockHandler)

        // Set up common mock behaviors
        every { mockHandler.startSpan(any(), any()) } returns mockSpan
        every { mockSpan.makeCurrent() } returns mockScope
        every { mockSpan.toSpanData() } returns mockSpanData
        every { mockSpanData.parentSpanId } returns "parent-span-id"
        every { mockHeaders.requestHeaders } returns MultivaluedHashMap()
        every { mockHeaders.getHeaderString(any()) } returns null
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
        @Logboek(name = "test-span", processingActivityId = "activity-123")
        fun testMethod() {}
    }

    private fun getAnnotatedMethod(): Method {
        return AnnotatedMethods::class.java.getDeclaredMethod("testMethod")
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
            verify { mockHandler.addLogboekContextToSpan(mockSpan, mockLogboekContext) }
            verify { mockSpan.end() }
            assert(result == "result")
            assert(mockLogboekContext.processingActivityId == "activity-123")
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
            verify { mockSpan.setStatus(StatusCode.ERROR) }
            verify { mockHandler.addLogboekContextToSpan(mockSpan, mockLogboekContext) }
            verify { mockSpan.end() }
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
    @DisplayName("Trace context propagation")
    inner class TraceContextPropagationTests {

        @Test
        fun `sets foreign operation attributes when traceparent header present`() {
            // given
            val mockMethod = getAnnotatedMethod()
            every { mockInvocationContext.method } returns mockMethod
            every { mockInvocationContext.proceed() } returns "result"
            every { mockHeaders.getHeaderString("traceparent") } returns "00-trace-id-span-id-01"
            every { mockHeaders.getHeaderString("traceparent-processor") } returns "http://processor.example.com"

            // when
            interceptor.log(mockInvocationContext)

            // then
            verify { mockSpan.setAttribute("dpl.core.foreign_operation.span_id", "parent-span-id") }
            verify { mockSpan.setAttribute("dpl.core.foreign_operation.processor", "http://processor.example.com") }
        }

        @Test
        fun `Does not set foreign operation attributes when traceparent header absent`() {
            // given
            val mockMethod = getAnnotatedMethod()
            every { mockInvocationContext.method } returns mockMethod
            every { mockInvocationContext.proceed() } returns "result"
            every { mockHeaders.getHeaderString("traceparent") } returns null

            // when
            interceptor.log(mockInvocationContext)

            // then
            verify(inverse = true) { mockSpan.setAttribute("dpl.core.foreign_operation.span_id", any<String>()) }
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
