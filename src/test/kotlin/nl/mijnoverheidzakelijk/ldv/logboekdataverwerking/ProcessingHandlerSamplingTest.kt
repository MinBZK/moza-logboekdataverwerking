package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader
import nl.mijnoverheidzakelijk.ldv.exporter.LdvSpanFilterProcessor
import org.eclipse.microprofile.config.Config
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.Optional

/**
 * Guards the LDV MUST NOT use Log Sampling: the dedicated SDK must record logregels
 * regardless of an inbound `traceparent` sampled-flag.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class ProcessingHandlerSamplingTest {

    companion object {
        private lateinit var mockConfig: Config

        @JvmStatic
        @BeforeAll
        fun setUp() {
            mockConfig = mockk()
            every { mockConfig.getValue("logboekdataverwerking.service-name", String::class.java) } returns "test-service"
            every { mockConfig.getValue("logboekdataverwerking.enabled", Boolean::class.java) } returns false
            every { mockConfig.getOptionalValue("logboekdataverwerking.service-version", String::class.java) } returns Optional.empty()
            every { mockConfig.getOptionalValue("logboekdataverwerking.deployment-environment", String::class.java) } returns Optional.empty()
            ConfigurationLoader.configProvider = { mockConfig }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() = clearAllMocks()
    }

    @Test
    fun `LDV span is sampled even when the inbound parent is not sampled`() {
        val otel = ProcessingHandler.initOpenTelemetry()
        val notSampledParent = SpanContext.createFromRemoteParent(
            "0af7651916cd43dd8448eb211c80319c",
            "b7ad6b7169203331",
            TraceFlags.getDefault(), // sampled-flag 0
            TraceState.getDefault(),
        )
        val parentCtx = Context.root().with(Span.wrap(notSampledParent))

        val span = otel.getTracer(LdvSpanFilterProcessor.LDV_INSTRUMENTATION_SCOPE)
            .spanBuilder("verwerking")
            .setParent(parentCtx)
            .startSpan()
        try {
            assert(span.spanContext.isSampled) { "AlwaysOn must override a non-sampled inbound parent" }
            assert(span.isRecording) { "span must be recording so it is exported to the Logboek" }
            assert(span.spanContext.traceId == "0af7651916cd43dd8448eb211c80319c") { "trace_id must be inherited" }
        } finally {
            span.end()
        }
    }
}
