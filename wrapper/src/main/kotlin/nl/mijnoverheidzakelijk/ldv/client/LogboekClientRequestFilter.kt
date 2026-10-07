package nl.mijnoverheidzakelijk.ldv.client

import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapSetter
import jakarta.ws.rs.client.ClientRequestContext
import jakarta.ws.rs.client.ClientRequestFilter
import jakarta.ws.rs.ext.Provider

/**
 * Outbound JAX-RS client filter that injects W3C Trace Context headers
 * (`traceparent` and, when present, `tracestate`) into every outgoing request
 * using the current OpenTelemetry [Context].
 *
 * This is the counterpart to the inbound extraction performed by
 * [nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekInterceptor]: when
 * both are in place, an LDV trace continues across organisatie-grenzen and
 * each receiving service can link its own logregels to the same `trace_id`.
 *
 * ## Registration
 *
 * The filter is annotated with `@Provider` but JAX-RS clients do not auto-scan
 * providers. Register it explicitly on each REST client. With Quarkus REST
 * Client / MicroProfile:
 *
 * ```kotlin
 * @Path("/api")
 * @RegisterRestClient
 * @RegisterProvider(LogboekClientRequestFilter::class)
 * interface MyExternalClient { ... }
 * ```
 *
 * Or programmatically:
 *
 * ```kotlin
 * val client = ClientBuilder.newClient().register(LogboekClientRequestFilter::class.java)
 * ```
 *
 * ## Note on the foreign_operation.processor attribute
 *
 * Per the LDV spec, `dpl.core.foreign_operation.processor` identifies the
 * external party in a cross-organisatie verwerking and is set by application
 * code on the outbound side (the URL/identifier of the service being called).
 * This filter only propagates the trace context; application code is still
 * responsible for setting the attribute on the active span when calling out.
 */
@Provider
class LogboekClientRequestFilter : ClientRequestFilter {

    override fun filter(requestContext: ClientRequestContext) {
        W3CTraceContextPropagator.getInstance().inject(
            Context.current(),
            requestContext,
            ClientRequestContextSetter
        )
    }

    private object ClientRequestContextSetter : TextMapSetter<ClientRequestContext> {
        override fun set(carrier: ClientRequestContext?, key: String, value: String) {
            checkNotNull(carrier)
            carrier.headers.add(key, value)
        }
    }
}
