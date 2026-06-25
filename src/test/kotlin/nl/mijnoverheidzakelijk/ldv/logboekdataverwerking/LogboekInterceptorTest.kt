package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.context.Scope
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
            verify { mockHandler.addLogboekContextToSpan(mockSpan, mockLogboekContext, true) }
            verify { mockSpan.end() }
            assert(result == "result")
            assert(mockLogboekContext.processingActivityId == "https://register.example.org/activiteiten/activity-123")
        }

        @Test
        fun `Throws when span name is empty`() {
            // given
            val mockMethod = getEmptyNameMethod()
            every { mockInvocationContext.method } returns mockMethod

            // when / then
            assertThrows<IllegalArgumentException> {
                interceptor.log(mockInvocationContext)
            }
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
            // setStatus = false: the interceptor must not let addLogboekContextToSpan
            // re-apply status from LogboekContext on the exception path, otherwise an
            // optimistic OK written by user code before the throw would mask the ERROR.
            verify { mockHandler.addLogboekContextToSpan(mockSpan, mockLogboekContext, false) }
            verify { mockSpan.end() }
        }

        @Test
        fun `Exception preserves ERROR even when LogboekContext status was set to OK`() {
            // given
            val mockMethod = getAnnotatedMethod()
            mockLogboekContext.status = StatusCode.OK
            every { mockInvocationContext.method } returns mockMethod
            every { mockInvocationContext.proceed() } throws RuntimeException("kaboom")

            // when / then
            assertThrows<RuntimeException> { interceptor.log(mockInvocationContext) }

            verify { mockSpan.setStatus(StatusCode.ERROR, "kaboom") }
            verify { mockHandler.addLogboekContextToSpan(mockSpan, mockLogboekContext, false) }
            // setStatus(OK) from addLogboekContextToSpan would otherwise be locked-in by OTel
            // and prevent the ERROR set in the catch from sticking.
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
