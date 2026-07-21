package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.propagation.TextMapGetter

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
     * marked with StatusCode error and the exception is rethrown. The exception path
     * sets ERROR directly on the span and prevents the finally-block from overwriting
     * it via the (possibly stale) status field on [LogboekContext].
     *
     * The finally block also relaxes [LogboekContext] validation (`requireCompleteContext =
     * false`) whenever an exception from the intercepted method is already propagating.
     * Otherwise, a method that failed before it could populate [LogboekContext] (e.g. a
     * bean-validation error on its parameters) would have that exception replaced by an
     * IllegalArgumentException thrown from this finally block.
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
        require(name.isNotEmpty()) { "Span name is required by the LDV standard" }
        val processingActivityId = annotation.processingActivityId

        val span = handler.startSpan(name, traceContext)
        var caughtException = false

        try {
            span.makeCurrent().use { _ ->
                return context.proceed()
            }
        } catch (e: Exception) {
            caughtException = true
            span.setStatus(StatusCode.ERROR, e.message ?: "")
            throw e
        } finally {
            logboekContext.processingActivityId = processingActivityId
            // On the exception path ERROR is already set; skip re-applying status. An
            // optimistic OK would override it (OTel precedence: Ok > Error) and drop the error.
            // requireCompleteContext is also relaxed on that path, so an incomplete context
            // (never populated because the method body didn't run) can't throw here and
            // replace the exception already propagating from the catch block above.
            handler.addLogboekContextToSpan(
                span,
                logboekContext,
                setStatus = !caughtException,
                requireCompleteContext = !caughtException
            )
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
