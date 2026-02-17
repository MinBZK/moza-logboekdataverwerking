package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.sdk.trace.ReadableSpan
import jakarta.inject.Inject
import jakarta.interceptor.AroundInvoke
import jakarta.interceptor.Interceptor
import jakarta.interceptor.InvocationContext
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders

/**
 * CDI interceptor that surrounds methods annotated with [Logboek] and creates
 * an OpenTelemetry span.
 * 
 * 
 * It extracts an existing trace context from inbound HTTP headers
 * (if present) using the W3C Trace Context format and enriches the span with Logboek
 * attributes before ending it.
 */
@Logboek
@Interceptor
class LogboekInterceptor {
    @Inject
    private lateinit var logboekContext: LogboekContext

    @Context
    private lateinit var headers: HttpHeaders

    @Inject
    private lateinit var handler: ProcessingHandler

    /**
     * Starts a span, proceeds with the intercepted invocation, and finalizes the span
     * with any Logboek context attributes. If an exception occurs, the span status is
     * marked with StatusCode error and the exception is rethrown.
     * 
     * @param context the invocation context
     * @return the result of the intercepted method
     * @throws Exception propagated from the intercepted method
     */
    @AroundInvoke
    @Throws(Exception::class)
    fun log(context: InvocationContext): Any? {
        val propagatorInstance = W3CTraceContextPropagator.getInstance()
        val traceContext = propagatorInstance.extract(
            io.opentelemetry.context.Context.current(),
            headers,
            HttpHeadersGetter()
        )

        val annotation = context.method.getAnnotation(Logboek::class.java)
        val name = annotation.name
        val processingActivityId = annotation.processingActivityId

        val span = handler.startSpan(name, traceContext)

        try {
            span.makeCurrent().use { _ ->
                return context.proceed()
            }
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR)
            throw e
        } finally {
            val spanData = (span as ReadableSpan).toSpanData()

            if (headers.getHeaderString("traceparent") != null) {
                span.setAttribute("dpl.core.foreign_operation.span_id", spanData.parentSpanId)

                //todo hoe krijgen we de url, bijv. header. Hier is het team van LDV nog mee bezig.
                //todo How do we get the url, ex. header. This is still being worked on by the LDV team.
                span.setAttribute(
                    "dpl.core.foreign_operation.processor",
                    headers.getHeaderString("traceparent-processor")
                )
            }

            logboekContext.processingActivityId = processingActivityId
            handler.addLogboekContextToSpan(span, logboekContext)
            span.end()
        }
    }

    /**
     * Extracts header values for the OpenTelemetry propagator from [HttpHeaders].
     */
    private class HttpHeadersGetter : TextMapGetter<HttpHeaders> {
        /**
         * @param httpHeaders the httpHeaders object
         * @return iterable of header names
         */
        override fun keys(httpHeaders: HttpHeaders): Iterable<String> {
            return httpHeaders.requestHeaders.keys
        }

        /**
         * @param httpHeaders the httpHeaders object
         * @param key header name
         * @return the value for the header, or null if absent
         */
        override fun get(httpHeaders: HttpHeaders?, key: String): String? {
            checkNotNull(httpHeaders)
            return httpHeaders.getHeaderString(key)
        }
    }
}
