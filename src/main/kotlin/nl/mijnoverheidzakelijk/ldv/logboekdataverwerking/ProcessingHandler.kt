package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.trace.samplers.Sampler
import jakarta.annotation.PostConstruct
import jakarta.enterprise.context.ApplicationScoped
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader
import nl.mijnoverheidzakelijk.ldv.exporter.LdvSpanExporter
import nl.mijnoverheidzakelijk.ldv.exporter.LdvSpanFilterProcessor
import nl.mijnoverheidzakelijk.ldv.exporter.LogboekWriteFailureRecorder
import nl.mijnoverheidzakelijk.ldv.repository.ClickHouseRepository
import nl.mijnoverheidzakelijk.ldv.repository.PostgresRepository
import nl.mijnoverheidzakelijk.ldv.repository.SpanRepository
import org.apache.commons.configuration2.ex.ConfigurationException
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Creates and enriches the OpenTelemetry spans used by the Logboek interceptor flow.
 *
 * Uses a dedicated SDK rather than a host-provided one (e.g. quarkus-opentelemetry):
 * the host's sampler would otherwise be able to drop logregels, which the LDV spec
 * forbids (MUST NOT use Log Sampling). See [initOpenTelemetry].
 */
@ApplicationScoped
class ProcessingHandler {

    internal lateinit var openTelemetry: OpenTelemetry

    @PostConstruct
    fun init() {
        openTelemetry = initOpenTelemetry()
    }

    /**
     * Starts a new span with the given name, optionally using an existing parent context.
     *
     * @param name    the span name
     * @param context the parent context may be null
     * @return the started span
     */
    fun startSpan(name: String, context: Context?): Span {
        // Use a dedicated, fixed instrumentation scope so LdvSpanFilterProcessor can
        // reliably route only LDV spans to ClickHouse on a host's shared OpenTelemetry SDK.
        val tracer: Tracer = openTelemetry.getTracer(LdvSpanFilterProcessor.LDV_INSTRUMENTATION_SCOPE)
        if (context != null) {
            return tracer.spanBuilder(name)
                .setParent(context)
                .startSpan()
        }

        return tracer.spanBuilder(name)
            .startSpan()
    }

    /**
     * Adds Logboek context attributes and status to the given span.
     *
     * Validation never breaks the verwerking (LDV 3.3.2.1): a missing or invalid
     * [LogboekContext] field is logged as a warning (with `trace_id:span_id`) and
     * the logregel is exported with whatever attributes are present.
     *
     * The [propagatingException] parameter tells this method that an exception from
     * the intercepted method is already propagating. On that path the span's own
     * status is left untouched (the interceptor has already set ERROR and the
     * `exception.*` attributes on it) and per-betrokkene child logregels get
     * [StatusCode.ERROR] plus the same `exception.*` attributes, so every logregel
     * of the failed verwerking carries the failure detail.
     *
     * An exception announced via [LogboekContext.expectException] is exempt and is
     * treated as if nothing was propagating: the status from the context is applied
     * to the span, and no `exception.*` attributes are set, not on the child
     * logregels either.
     *
     * @param span                 the span to enrich
     * @param logboekContext       the context holding attributes
     * @param propagatingException the exception propagating from the intercepted
     *                             method, or null on the success path
     * @return every logregel this action exports: the action span, followed by one
     *         child per betrokkene when there are several. Feed them to
     *         [recordFailedOutcome] when the verwerking fails after its logregel
     *         was acknowledged.
     */
    @JvmOverloads
    fun addLogboekContextToSpan(
        span: Span,
        logboekContext: LogboekContext,
        propagatingException: Throwable? = null
    ): List<Logregel> {
        val processingActivityId = logboekContext.processingActivityId?.takeIf { it.isNotEmpty() }
        val subjects = logboekContext.effectiveSubjects()

        warnOnIncompleteContext(span, logboekContext, subjects)

        // An exception announced via LogboekContext.expectException does not count as a
        // failure here; identity, so only the announced instance is exempt.
        val unexpected = propagatingException?.takeUnless(logboekContext::isExpected)

        processingActivityId?.let { span.setAttribute("dpl.core.processing_activity_id", it) }
        if (unexpected == null) {
            span.setStatus(logboekContext.status)
        }

        if (subjects.size > 1) {
            // LDV requires a separate logregel per betrokkene; the action span stays
            // subject-less and each betrokkene becomes a child span.
            val parentContext = Context.root().with(span)
            val childName = logboekContext.actionName?.takeIf { it.isNotEmpty() } ?: CHILD_SPAN_NAME
            val childStatus = if (unexpected != null) StatusCode.ERROR else logboekContext.status
            // One stacktrace per actie, shared by all children: rendering it is not cheap.
            val stacktrace = unexpected?.let(::stacktraceForExport)
            val children = subjects.map { subject ->
                val child = startSpan(childName, parentContext)
                processingActivityId?.let { child.setAttribute("dpl.core.processing_activity_id", it) }
                applySubject(child, subject)
                child.setStatus(childStatus)
                unexpected?.let { applyException(child, it, stacktrace) }
                child.end()
                Logregel(child.spanContext, childName, processingActivityId, subject)
            }
            // Action logregel first, so the reading rule holds for every exported logregel.
            val action = Logregel(span.spanContext, spanName(span, logboekContext), processingActivityId, null)
            return listOf(action) + children
        }

        // A single betrokkene, or a half-set pair: apply what is present.
        val subject = subjects.singleOrNull()
            ?: DataSubject(logboekContext.dataSubjectId.orEmpty(), logboekContext.dataSubjectType.orEmpty())
        applySubject(span, subject)
        val presentSubject = subject.takeIf { it.id.isNotEmpty() || it.type.isNotEmpty() }
        return listOf(Logregel(span.spanContext, spanName(span, logboekContext), processingActivityId, presentSubject))
    }

    /** The name the action span is exported under, falling back to the context. */
    private fun spanName(span: Span, logboekContext: LogboekContext): String =
        (span as? ReadableSpan)?.name
            ?: logboekContext.actionName?.takeIf { it.isNotEmpty() }
            ?: CHILD_SPAN_NAME

    /**
     * Warns (with the `trace_id:span_id`, so the incomplete logregel can be found
     * back in the Logboek) for every required LDV field that is missing or invalid.
     * Warns instead of throws: logging must never break the verwerking (LDV 3.3.2.1).
     * A logregel without betrokkene is valid for niet-persoonsgegevens verwerkingen;
     * the warning helps spot forgotten context.
     */
    private fun warnOnIncompleteContext(span: Span, logboekContext: LogboekContext, subjects: List<DataSubject>) {
        val processingActivityId = logboekContext.processingActivityId
        if (processingActivityId.isNullOrEmpty()) {
            warnIncomplete(span, "dpl.core.processing_activity_id is missing")
        } else if (!isAbsoluteUri(processingActivityId)) {
            warnIncomplete(span, "dpl.core.processing_activity_id is not a valid absolute URI: $processingActivityId")
        }
        if (subjects.isEmpty()) {
            if (!logboekContext.dataSubjectId.isNullOrEmpty()) {
                warnIncomplete(span, "dpl.core.data_subject_id_type is missing")
            } else if (!logboekContext.dataSubjectType.isNullOrEmpty()) {
                warnIncomplete(span, "dpl.core.data_subject_id is missing")
            } else {
                warnIncomplete(span, "no betrokkene (dpl.core.data_subject_id/_type) is set")
            }
        } else {
            subjects.forEach {
                if (it.id.isEmpty()) warnIncomplete(span, "dpl.core.data_subject_id is empty for a betrokkene")
                if (it.type.isEmpty()) warnIncomplete(span, "dpl.core.data_subject_id_type is empty for a betrokkene")
            }
        }
    }

    private fun warnIncomplete(span: Span, problem: String) {
        val sc = span.spanContext
        LOGGER.warning("$problem; the logregel is exported with incomplete context [${sc.traceId}:${sc.spanId}]")
    }

    private fun applySubject(span: Span, subject: DataSubject) {
        if (subject.id.isNotEmpty()) span.setAttribute("dpl.core.data_subject_id", subject.id)
        if (subject.type.isNotEmpty()) span.setAttribute("dpl.core.data_subject_id_type", subject.type)
    }

    /** The `exception.*` attributes the interceptor sets on a failed action span. */
    private fun applyException(span: Span, e: Throwable, stacktrace: String?) {
        span.setAttribute("exception.type", e.javaClass.name)
        e.message?.let { span.setAttribute("exception.message", it) }
        stacktrace?.let { span.setAttribute("exception.stacktrace", it) }
    }

    /** Stacktraces are large and can embed persoonsgegevens; only rendered on opt-in. */
    private fun stacktraceForExport(e: Throwable): String? =
        e.takeIf { ConfigurationLoader.logExceptionStacktrace }?.stackTraceToString()

    /**
     * Always consumes the recorded write failure so none lingers on a pooled thread.
     * When [throwOnFailure] and policy is `FAIL_CLOSED`, rethrows it so a verwerking
     * does not count as logged when its logregel was not stored.
     *
     * Called by the outermost `@Logboek` action only: nested actions leave their
     * failure recorded, so the check runs at the request boundary where business
     * code cannot swallow it.
     */
    @JvmOverloads
    fun enforceWriteAcknowledgement(throwOnFailure: Boolean = true) {
        val failure = LogboekWriteFailureRecorder.consume() ?: return
        if (throwOnFailure && ConfigurationLoader.writeFailurePolicy == ConfigurationLoader.WriteFailurePolicy.FAIL_CLOSED) {
            throw LogboekWriteException("Logregel kon niet in het Logboek worden opgeslagen", failure)
        }
    }

    /**
     * Records the failed outcome of a verwerking whose logregel was written and
     * acknowledged before the verwerking itself ran (write-first under `simple` +
     * `fail-closed`). That logregel is final and carries `UNSET`, which the standard
     * reads as "afgerond zonder systeemfout", so the failure becomes a separate ERROR
     * logregel with the original one as parent. Reading rule (MOZa-afspraak, not yet
     * in the standard): a logregel without ERROR child succeeded.
     *
     * Each [Logregel] gets one outcome logregel with the same name,
     * verwerkingsactiviteit and betrokkene, so at inzage the outcome is found from
     * the row that carries the betrokkene, and from the action row as well.
     * [OUTCOME_ATTRIBUTE_KEY] tells an outcome logregel apart from per-betrokkene
     * child logregels, which share the parent.
     *
     * Needs no `@Logboek` action and no request: the parent comes from the
     * [Logregel], not from the current context.
     *
     * Catches every Throwable, so it can never mask the failure being recorded, and
     * leaves no write failure of its own on the thread; one an enclosing action
     * recorded is kept for its fail-closed check.
     * The logregels whose outcome was lost are returned and named in one SEVERE
     * line: without an ERROR child they read as succeeded, so the Logboek
     * under-reports. Only `span-processor=simple` exports on this thread, so under
     * `batch` the returned list is always empty.
     *
     * @param logregels the acknowledged logregels of the failed verwerking
     * @param exception the failure
     * @return the logregels whose outcome logregel was not stored; empty when every
     *         outcome logregel was acknowledged
     */
    fun recordFailedOutcome(logregels: Collection<Logregel>, exception: Throwable): List<Logregel> {
        if (logregels.isEmpty()) return emptyList()
        val pending = LogboekWriteFailureRecorder.consume()
        val lost = mutableListOf<Logregel>()
        var cause: Throwable? = null
        try {
            val stacktrace = stacktraceForExport(exception)
            for (logregel in logregels) {
                // Per logregel, so one failure does not cost the others their outcome.
                val failure = try {
                    writeFailedOutcome(logregel, exception, stacktrace)
                } catch (e: Throwable) {
                    e
                }
                if (failure != null) {
                    lost += logregel
                    if (cause == null) cause = failure
                }
            }
        } catch (e: Throwable) {
            // Recording an outcome must never break the verwerking (LDV 3.3.2.1). Nothing
            // was written, so report every logregel as lost: over-reporting is the safe side.
            lost.clear()
            lost.addAll(logregels)
            cause = e
        } finally {
            // Whatever this method recorded stays out of the enclosing action's check.
            LogboekWriteFailureRecorder.clear()
            pending?.let(LogboekWriteFailureRecorder::record)
        }
        if (lost.isNotEmpty()) reportLostOutcomes(lost, cause)
        return lost
    }

    /**
     * Writes the ERROR logregel for one [logregel].
     *
     * @return the write failure when the outcome logregel was not stored, else null
     */
    private fun writeFailedOutcome(logregel: Logregel, exception: Throwable, stacktrace: String?): Throwable? {
        val ids = "${logregel.spanContext.traceId}:${logregel.spanContext.spanId}"
        if (!logregel.spanContext.isValid) {
            // Span.wrap of an invalid context yields no parent, so the outcome starts its own trace.
            LOGGER.warning(
                "Logregel '${logregel.name}' has no valid span context [$ids]; " +
                    "its outcome logregel is written unlinked, as the root of a new trace"
            )
        }
        val span = startSpan(logregel.name, Context.root().with(Span.wrap(logregel.spanContext)))
        span.setAttribute(OUTCOME_ATTRIBUTE_KEY, OUTCOME_FAILED)
        logregel.processingActivityId?.let { span.setAttribute("dpl.core.processing_activity_id", it) }
        logregel.subject?.let { applySubject(span, it) }
        span.setStatus(StatusCode.ERROR, exception.message ?: "")
        applyException(span, exception, stacktrace)
        span.end()
        // Only the synchronous exporter relays write failures to this thread; BATCH reports none.
        return LogboekWriteFailureRecorder.consume()
    }

    /** One bounded SEVERE for the logregels that stay behind without their ERROR logregel. */
    private fun reportLostOutcomes(lost: List<Logregel>, cause: Throwable?) {
        val ids = lost.joinToString(
            limit = LdvSpanExporter.DEFAULT_MAX_LOGGED_SPAN_IDS,
            truncated = "…",
        ) { "${it.spanContext.traceId}:${it.spanContext.spanId}" }
        val message = "Failed to record the outcome of ${lost.size} logregel(s); " +
            "they stay without ERROR logregel in the Logboek [$ids]"
        if (cause == null) LOGGER.severe(message) else LOGGER.log(Level.SEVERE, message, cause)
    }

    /**
     * Single-logregel form of [recordFailedOutcome].
     *
     * @return the logregel when its outcome logregel was not stored, else empty
     */
    fun recordFailedOutcome(logregel: Logregel, exception: Throwable): List<Logregel> =
        recordFailedOutcome(listOf(logregel), exception)

    /**
     * @return true if [value] parses as an absolute URI per the LDV standard's
     *         requirement for `dpl.core.processing_activity_id`
     */
    private fun isAbsoluteUri(value: String): Boolean {
        return try {
            java.net.URI(value).isAbsolute
        } catch (e: java.net.URISyntaxException) {
            false
        }
    }

    companion object {
        private val LOGGER: Logger = Logger.getLogger(ProcessingHandler::class.java.name)

        /** Name for per-betrokkene logregels when the action carries no human-readable name. */
        internal const val CHILD_SPAN_NAME: String = "verwerking-betrokkene"

        /**
         * Marks an outcome logregel written by [recordFailedOutcome]. Per-betrokkene
         * child logregels share the parent but never carry this attribute.
         */
        const val OUTCOME_ATTRIBUTE_KEY: String = "moza.ldv.uitkomst"

        /** Value of [OUTCOME_ATTRIBUTE_KEY] for a failed verwerking. */
        const val OUTCOME_FAILED: String = "mislukt"

        val serviceName: String by lazy { ConfigurationLoader.serviceName }

        /**
         * [Sampler.alwaysOn] so an inbound `traceparent` sampled-flag of `0` cannot drop
         * logregels (LDV MUST NOT sample). Not registered globally, so it coexists with a
         * host-provided OpenTelemetry.
         *
         * @throws ConfigurationException if exporter configuration cannot be read
         */
        @Throws(ConfigurationException::class)
        internal fun initOpenTelemetry(): OpenTelemetry {
            // Fail loud here: read again from the interceptor's finally, where throwing
            // would replace the verwerking's own exception.
            val logExceptionStacktrace = ConfigurationLoader.logExceptionStacktrace
            LOGGER.info(
                "Initializing LDV OpenTelemetry for service: $serviceName " +
                    "(exception stacktraces: $logExceptionStacktrace)"
            )

            val resource = Resource.getDefault().merge(Resource.create(buildResourceAttributes()))

            val tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(buildLdvSpanProcessor())
                .build()

            val openTelemetrySdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .build()

            Runtime.getRuntime().addShutdownHook(Thread { openTelemetrySdk.close() })

            return openTelemetrySdk
        }

        /**
         * Builds the LDV span-export pipeline. When LDV is enabled: the database
         * exporter selected via `logboekdataverwerking.dbms` (ClickHouse or
         * PostgreSQL) wrapped in the configured [SpanProcessor], then wrapped in
         * [LdvSpanFilterProcessor] so only LDV spans are exported. When disabled it
         * returns a no-op processor, so the dedicated SDK does no work.
         *
         * @throws IllegalStateException if `enabled` but the selected backend's config is incomplete
         * @throws IllegalArgumentException if `logboekdataverwerking.dbms` is set to an unsupported value
         */
        internal fun buildLdvSpanProcessor(): SpanProcessor {
            if (!ConfigurationLoader.enabled) {
                // Disabled: contribute nothing (no exporter, no worker thread).
                return SpanProcessor.composite(emptyList())
            }

            // Fail-loud on startup if the selected backend is misconfigured,
            // instead of silently dropping spans at first export. The backend
            // choice is just which SpanRepository the shared LdvSpanExporter uses.
            val repository: SpanRepository = when (ConfigurationLoader.dbms) {
                ConfigurationLoader.Dbms.CLICKHOUSE -> {
                    ConfigurationLoader.validateClickhouseConfig()
                    ClickHouseRepository()
                }
                ConfigurationLoader.Dbms.POSTGRESQL -> {
                    ConfigurationLoader.validatePostgresqlConfig()
                    PostgresRepository()
                }
            }
            val mode = ConfigurationLoader.spanProcessor
            // Read unconditionally, so an invalid write-failure-policy value fails
            // loud here at startup instead of at the first write failure.
            val writeFailurePolicy = ConfigurationLoader.writeFailurePolicy
            if (mode == ConfigurationLoader.SpanProcessorMode.BATCH &&
                writeFailurePolicy == ConfigurationLoader.WriteFailurePolicy.FAIL_CLOSED
            ) {
                LOGGER.warning(
                    "write-failure-policy=fail-closed has no effect under span-processor=batch: " +
                        "spans are exported on a background thread, so write failures degrade to log-only"
                )
            }
            val exporter: SpanExporter = LdvSpanExporter(
                repository,
                relayWriteFailures = mode == ConfigurationLoader.SpanProcessorMode.SIMPLE,
            )

            val delegate: SpanProcessor = when (mode) {
                ConfigurationLoader.SpanProcessorMode.SIMPLE -> SimpleSpanProcessor.create(exporter)
                ConfigurationLoader.SpanProcessorMode.BATCH -> BatchSpanProcessor.builder(exporter).build()
            }

            return LdvSpanFilterProcessor(delegate)
        }

        private fun buildResourceAttributes(): Attributes {
            val builder = Attributes.builder()
            builder.put(AttributeKey.stringKey("service.name"), serviceName)
            ConfigurationLoader.serviceVersion?.let {
                builder.put(AttributeKey.stringKey("service.version"), it)
            }
            ConfigurationLoader.deploymentEnvironment?.let {
                builder.put(AttributeKey.stringKey("deployment.environment"), it)
            }
            return builder.build()
        }
    }
}
