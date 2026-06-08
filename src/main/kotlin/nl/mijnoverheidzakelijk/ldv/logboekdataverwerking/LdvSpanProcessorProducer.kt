package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import io.opentelemetry.sdk.trace.SpanProcessor
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Produces
import jakarta.inject.Singleton

/**
 * Contributes the LDV ClickHouse span-export pipeline as a CDI [SpanProcessor]
 * bean.
 *
 * When the host application uses an OpenTelemetry integration that collects
 * `SpanProcessor` beans (e.g. quarkus-opentelemetry), this processor is added to
 * the host's existing SDK. That keeps a single OpenTelemetry instance, the
 * package never builds a second SDK when the host already provides one, see
 * [ProcessingHandler.init], while still routing LDV spans to ClickHouse.
 *
 * In a standalone (non-container) setup nothing consumes this bean;
 * [ProcessingHandler] builds its own SDK with the same processor via
 * [ProcessingHandler.buildLdvSpanProcessor] instead.
 */
@ApplicationScoped
class LdvSpanProcessorProducer {

    @Produces
    @Singleton
    fun ldvClickHouseSpanProcessor(): SpanProcessor = ProcessingHandler.buildLdvSpanProcessor()
}
