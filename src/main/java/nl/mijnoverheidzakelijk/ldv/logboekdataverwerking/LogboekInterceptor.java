package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.data.SpanData;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;

/**
 * CDI interceptor that surrounds methods annotated with {@link Logboek} and creates
 * an OpenTelemetry span.
 * <p>
 * It extracts an existing trace context from inbound HTTP headers
 * (if present) using the W3C Trace Context format and enriches the span with Logboek
 * attributes before ending it.
 */
@Logboek
@Interceptor
public class LogboekInterceptor {

    @Inject
    LogboekContext logboekContext;

    @Context
    HttpHeaders headers;

    @Inject
    ProcessingHandler handler;


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
    public Object log(InvocationContext context) throws Exception {

        var propagatorInstance = W3CTraceContextPropagator.getInstance();
        io.opentelemetry.context.Context traceContext = propagatorInstance.extract(
                io.opentelemetry.context.Context.current(), 
                headers, 
                new HttpHeadersGetter()
        );

        Logboek annotation = context.getMethod().getAnnotation(Logboek.class);
        String name = annotation.name();
        String processingActivityId = annotation.processingActivityId();

        Span span = handler.startSpan(name, traceContext);

        try (var ignored = span.makeCurrent()) {
            return context.proceed();
        } catch (IllegalArgumentException | IllegalStateException e) {
            span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR);
            throw e;
        }
        finally {
            SpanData spanData = ((ReadableSpan) span).toSpanData();

            if (headers.getHeaderString("traceparent") != null) {
                span.setAttribute("dpl.core.foreign_operation.span_id", spanData.getParentSpanId());

                //todo hoe krijgen we de url, bijv. header. Hier is het team van LDV nog mee bezig.
                //todo How do we get the url, ex. header. This is still being worked on by the LDV team.
                span.setAttribute("dpl.core.foreign_operation.processor", headers.getHeaderString("traceparent-processor"));
            }

            logboekContext.setProcessingActivityId(processingActivityId);
            handler.addLogboekContextToSpan(span, logboekContext);
            span.end();
        }
    }


    /**
     * Extracts header values for the OpenTelemetry propagator from {@link HttpHeaders}.
     */
    private static class HttpHeadersGetter implements TextMapGetter<HttpHeaders> {

        /**
         * @param httpHeaders the httpHeaders object
         * @return iterable of header names
         */
        @Override
        public Iterable<String> keys(HttpHeaders httpHeaders) {
            return httpHeaders.getRequestHeaders().keySet();
        }


        /**
         * @param httpHeaders the httpHeaders object
         * @param key header name
         * @return the value for the header, or null if absent
         */
        @Override
        public String get(HttpHeaders httpHeaders, String key) {
            assert httpHeaders != null;
            return httpHeaders.getHeaderString(key);
        }
    }
}


