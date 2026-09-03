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

        /**
         * Marks the current context as inside an LDV action, so nested actions parent
         * to it. Carries the owning thread id: parenting follows the trace context
         * (also across threads), but fail-closed enforcement follows the thread,
         * because the write-failure recorder is thread-bound.
         */
        private val LDV_ACTION: ContextKey<Long> = ContextKey.named("ldv-action")
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
     * An exception the caller announced with [LogboekContext.expectException] is rethrown
     * unchanged, but its logregel keeps the status from the context and carries no
     * `exception.*` attributes. The outermost action consumes the announcement, so a
     * reused exception instance cannot stay announced for later actions on the same
     * request. A failed write of an announced logregel still falls under fail-closed:
     * the announced exception travels as suppressed on the [LogboekWriteException].
     * Only an unexpected propagating exception suppresses the throw, because a write
     * failure must not mask it.
     *
     * A nested [Logboek] action parents to the enclosing action; only the outermost
     * action adopts an inbound `traceparent`. The outermost action on each thread
     * also owns the fail-closed acknowledgement: a nested action on the same thread
     * leaves a write failure recorded instead of throwing, so the
     * [LogboekWriteException] surfaces at the boundary and cannot be caught away or
     * mistaken for a functional failure by business code in between. An action on
     * another thread (with a propagated context) enforces on its own thread, because
     * the failure recorder is thread-bound and would otherwise never be consumed.
     *
     * The finally block enriches the span via [ProcessingHandler.addLogboekContextToSpan],
     * which warns instead of throws on an incomplete [LogboekContext], so the logregel
     * export can never fail or replace the outcome of the intercepted method (LDV
     * 3.3.2.1). Passing the caught exception on the exception path keeps the ERROR
     * status set above in place and marks per-betrokkene child logregels as ERROR
     * with the same `exception.*` attributes.
     *
     * @param context the invocation context
     * @return the result of the intercepted method
     * @throws Exception propagated from the intercepted method
     */
    @AroundInvoke
    @Throws(Exception::class)
    fun log(context: InvocationContext): Any? {
        val currentContext = io.opentelemetry.context.Context.current()
        val markerThreadId = currentContext.get(LDV_ACTION)
        // Parenting follows the trace: any enclosing action in the context, also one
        // on another thread whose context was propagated, is the parent. Enforcement
        // follows the thread (nestedOnThread): the recorder is thread-bound, so an
        // action on a different thread must own the fail-closed check itself, or its
        // write failure would be recorded where no boundary ever consumes it.
        val nestedInTrace = markerThreadId != null
        val nestedOnThread = markerThreadId == Thread.currentThread().threadId()
        // A nested @Logboek action parents to the enclosing local action (LDV: een actie
        // gestart door een andere actie neemt diens span_id op als parent_span_id). Only
        // the outermost action adopts an inbound traceparent: re-extracting it per
        // invocation would re-parent nested actions to the remote caller's span.
        val traceContext = if (nestedInTrace) {
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

        // The outermost action on this thread owns the recorder: it drops any failure
        // left on this (pooled) thread by an earlier request, so the fail-closed check
        // only reacts to this request's own writes. A nested action must not clear, or
        // it would wipe a failure recorded by an earlier sibling action.
        if (!nestedOnThread) {
            LogboekWriteFailureRecorder.clear()
        }

        val span = handler.startSpan(name, traceContext)
        var caughtException: Exception? = null

        try {
            traceContext.with(LDV_ACTION, Thread.currentThread().threadId()).makeCurrent().use { _ ->
                span.makeCurrent().use { _ ->
                    return context.proceed()
                }
            }
        } catch (e: Exception) {
            caughtException = e
            // An exception announced via LogboekContext.expectException gets no ERROR and
            // no exception.* attributes.
            if (!logboekContext.isExpected(e)) {
                span.setStatus(StatusCode.ERROR, e.message ?: "")
                span.setAttribute("exception.type", e.javaClass.name)
                e.message?.let { span.setAttribute("exception.message", it) }
                // Stacktraces are large and can embed persoonsgegevens; only store on opt-in.
                if (ConfigurationLoader.logExceptionStacktrace) {
                    span.setAttribute("exception.stacktrace", e.stackTraceToString())
                }
            }
            throw e
        } finally {
            logboekContext.processingActivityId = processingActivityId
            logboekContext.actionName = name
            // Passing the exception along skips re-applying status from the context (an
            // optimistic OK would override the ERROR set above, OTel precedence: Ok > Error)
            // and marks child logregels ERROR with the exception attributes. An announced
            // exception is filtered out there, not here.
            handler.addLogboekContextToSpan(span, logboekContext, propagatingException = caughtException)
            span.end()
            // Fail-closed is enforced once per thread, by the outermost action on it,
            // above all business code: a same-thread nested action leaves its write
            // failure recorded, so business code in between cannot catch the
            // LogboekWriteException away (silently defeating the guarantee) or mistake
            // it for a functional failure of the nested action.
            if (!nestedOnThread) {
                // An announced exception is an expected outcome, so its logregel still
                // falls under fail-closed; only an unexpected propagating failure may
                // not be masked by a write failure. The outermost action consumes the
                // announcement so a reused exception instance cannot stay announced
                // for later actions on this request.
                val announced = caughtException != null && logboekContext.isExpected(caughtException)
                logboekContext.clearExpectedException()
                try {
                    handler.enforceWriteAcknowledgement(throwOnFailure = caughtException == null || announced)
                } catch (writeFailure: LogboekWriteException) {
                    // Keep the announced outcome visible on the failure that replaces it.
                    caughtException?.let(writeFailure::addSuppressed)
                    throw writeFailure
                }
            }
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
