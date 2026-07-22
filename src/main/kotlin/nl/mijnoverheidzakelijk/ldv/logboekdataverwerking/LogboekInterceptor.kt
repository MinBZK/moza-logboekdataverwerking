package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.ContextKey
import io.opentelemetry.context.propagation.TextMapGetter
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader
import nl.mijnoverheidzakelijk.ldv.exporter.LogboekWriteFailureRecorder

import jakarta.inject.Inject
import jakarta.interceptor.AroundInvoke
import jakarta.interceptor.Interceptor
import jakarta.interceptor.InvocationContext
import jakarta.ws.rs.core.Context
import jakarta.ws.rs.core.HttpHeaders
import java.util.logging.Logger

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

    private companion object {
        private val LOGGER: Logger = Logger.getLogger(LogboekInterceptor::class.java.name)

        /** Marks the current context as inside an LDV action, so nested actions parent to it. */
        private val LDV_ACTION: ContextKey<Boolean> = ContextKey.named("ldv-action")
    }

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
     * A nested [Logboek] action parents to the enclosing action; only the outermost
     * action adopts an inbound `traceparent`.
     *
     * The finally block enriches the span via [ProcessingHandler.addLogboekContextToSpan],
     * which warns instead of throws on an incomplete [LogboekContext], so the logregel
     * export can never fail or replace the outcome of the intercepted method (LDV
     * 3.3.2.1). `propagatingFailure = true` on the exception path keeps the ERROR status
     * set above in place and marks per-betrokkene child logregels as ERROR.
     *
     * @param context the invocation context
     * @return the result of the intercepted method
     * @throws Exception propagated from the intercepted method
     */
    @AroundInvoke
    @Throws(Exception::class)
    fun log(context: InvocationContext): Any? {
        val currentContext = io.opentelemetry.context.Context.current()
        // A nested @Logboek action parents to the enclosing local action (LDV: een actie
        // gestart door een andere actie neemt diens span_id op als parent_span_id). Only
        // the outermost action adopts an inbound traceparent: re-extracting it per
        // invocation would re-parent nested actions to the remote caller's span.
        val traceContext = if (currentContext.get(LDV_ACTION) != null) {
            currentContext
        } else {
            W3CTraceContextPropagator.getInstance().extract(currentContext, headers, HttpHeadersGetter())
        }

        val annotation = context.method.getAnnotation(Logboek::class.java)
        // LDV 3.3.2.1: an empty name is auto-filled, it must never cause a runtime error.
        val name = annotation.name.ifEmpty {
            LOGGER.warning("@Logboek name is empty; using method name '${context.method.name}'")
            context.method.name
        }
        val processingActivityId = annotation.processingActivityId

        // Drop any write failure left on this (pooled) thread by an earlier request, so
        // the fail-closed check below only ever reacts to this verwerking's own writes.
        LogboekWriteFailureRecorder.clear()

        val span = handler.startSpan(name, traceContext)
        var caughtException = false

        try {
            traceContext.with(LDV_ACTION, true).makeCurrent().use { _ ->
                span.makeCurrent().use { _ ->
                    return context.proceed()
                }
            }
        } catch (e: Exception) {
            caughtException = true
            span.setStatus(StatusCode.ERROR, e.message ?: "")
            span.setAttribute("exception.type", e.javaClass.name)
            e.message?.let { span.setAttribute("exception.message", it) }
            // Stacktraces are large and can embed persoonsgegevens; only store on opt-in.
            if (ConfigurationLoader.logExceptionStacktrace) {
                span.setAttribute("exception.stacktrace", e.stackTraceToString())
            }
            throw e
        } finally {
            logboekContext.processingActivityId = processingActivityId
            logboekContext.actionName = name
            // On the exception path ERROR is already set; propagatingFailure skips
            // re-applying status from the context (an optimistic OK would override it,
            // OTel precedence: Ok > Error) and marks child logregels ERROR.
            handler.addLogboekContextToSpan(span, logboekContext, propagatingFailure = caughtException)
            span.end()
            // throwOnFailure=false on the exception path: a write failure must not mask
            // the business exception that is already propagating.
            handler.enforceWriteAcknowledgement(throwOnFailure = !caughtException)
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
