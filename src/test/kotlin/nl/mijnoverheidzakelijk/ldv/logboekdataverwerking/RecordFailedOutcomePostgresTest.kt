package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader
import nl.mijnoverheidzakelijk.ldv.exporter.LogboekWriteFailureRecorder
import org.eclipse.microprofile.config.Config
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.Optional

/**
 * Runs the write-first pattern against a real PostgreSQL (Zonky embedded, a child
 * process of the test JVM): acknowledged logregel first, outcome logregel after.
 * Asserts what an inzage reads back: parent_span_id, status and attributes.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class RecordFailedOutcomePostgresTest {

    private data class Row(
        val traceId: String,
        val spanId: String,
        val parentSpanId: String?,
        val status: String,
        val name: String,
        val attributes: Map<String, String>,
    )

    companion object {
        private const val TABLE = "logboek"
        private const val ACTIVITY = "https://register.example.org/activiteiten/aanleveren"
        private val objectMapper = ObjectMapper()
        private lateinit var postgres: EmbeddedPostgres
        private lateinit var handler: ProcessingHandler

        @JvmStatic
        @BeforeAll
        fun setUp() {
            postgres = EmbeddedPostgres.start()
            val cfg = mockk<Config>()
            // Catch-all first; specific stubs below take precedence.
            every { cfg.getOptionalValue(any<String>(), String::class.java) } returns Optional.empty()
            every { cfg.getValue("logboekdataverwerking.service-name", String::class.java) } returns "test-service"
            every { cfg.getValue("logboekdataverwerking.enabled", Boolean::class.java) } returns true
            every { cfg.getOptionalValue("logboekdataverwerking.dbms", String::class.java) } returns Optional.of("postgresql")
            every { cfg.getValue("logboekdataverwerking.postgresql.url", String::class.java) } returns
                postgres.getJdbcUrl("postgres", "postgres")
            every { cfg.getValue("logboekdataverwerking.postgresql.username", String::class.java) } returns "postgres"
            every { cfg.getValue("logboekdataverwerking.postgresql.password", String::class.java) } returns "postgres"
            every { cfg.getValue("logboekdataverwerking.postgresql.table", String::class.java) } returns TABLE
            ConfigurationLoader.configProvider = { cfg }
            // Defaults simple + fail-closed: the export runs on this thread.
            handler = ProcessingHandler().apply { init() }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            (handler.openTelemetry as OpenTelemetrySdk).close()
            postgres.close()
            clearAllMocks()
        }
    }

    @BeforeEach
    fun emptyTable() {
        sql("TRUNCATE $TABLE")
        LogboekWriteFailureRecorder.clear()
    }

    @Test
    fun `Outcome logregel is an ERROR child of the acknowledged logregel with the same LDV fields`() {
        val span = handler.startSpan("aanleveren", null)
        val logregels = handler.addLogboekContextToSpan(span, singleSubjectContext())
        span.end()
        handler.enforceWriteAcknowledgement()

        val lost = handler.recordFailedOutcome(logregels, IllegalStateException("levering mislukt"))

        assert(lost.isEmpty()) { "the outcome logregel was acknowledged, got $lost" }
        val stored = rows()
        assert(stored.size == 2) { "expected the acknowledged logregel plus one outcome logregel, got $stored" }
        val original = stored.single { it.spanId == span.spanContext.spanId }
        val outcome = stored.single { it.spanId != span.spanContext.spanId }
        assert(original.status == "UNSET" && original.parentSpanId == null)
        assert(ProcessingHandler.OUTCOME_ATTRIBUTE_KEY !in original.attributes)
        assert(outcome.traceId == original.traceId)
        assert(outcome.parentSpanId == original.spanId)
        assert(outcome.status == "ERROR")
        assert(outcome.name == "aanleveren")
        assert(outcome.attributes["dpl.core.processing_activity_id"] == ACTIVITY)
        assert(outcome.attributes["dpl.core.data_subject_id"] == "subject-1")
        assert(outcome.attributes["dpl.core.data_subject_id_type"] == "KVK")
        assert(outcome.attributes[ProcessingHandler.OUTCOME_ATTRIBUTE_KEY] == ProcessingHandler.OUTCOME_FAILED)
        assert(outcome.attributes["exception.type"] == "java.lang.IllegalStateException")
        assert(outcome.attributes["exception.message"] == "levering mislukt")
        assert("exception.stacktrace" !in outcome.attributes) { "stacktrace is opt-in" }
    }

    @Test
    fun `Multiple betrokkenen get an outcome logregel each, and so does the actie-regel`() {
        val span = handler.startSpan("publiceren", null)
        val context = LogboekContext().apply {
            processingActivityId = ACTIVITY
            actionName = "publiceren"
            addSubject("subject-1", "KVK")
            addSubject("subject-2", "KVK")
        }
        val logregels = handler.addLogboekContextToSpan(span, context)
        span.end()
        handler.enforceWriteAcknowledgement()
        assert(rows().size == 3) { "actie plus one logregel per betrokkene" }

        handler.recordFailedOutcome(logregels, IllegalStateException("publicatie mislukt"))

        val stored = rows()
        assert(stored.size == 6) { "expected 3 plus one outcome logregel per logregel, got ${stored.size}" }
        val outcomes = stored.filter {
            it.attributes[ProcessingHandler.OUTCOME_ATTRIBUTE_KEY] == ProcessingHandler.OUTCOME_FAILED
        }
        assert(outcomes.size == 3)
        outcomes.forEach { assert(it.status == "ERROR" && it.name == "publiceren") }

        // The reading rule must also hold for a query that starts at the actie-regel.
        val actieOutcome = outcomes.single { it.parentSpanId == span.spanContext.spanId }
        assert("dpl.core.data_subject_id" !in actieOutcome.attributes) { "the actie-regel carries no betrokkene" }

        val byId = stored.associateBy { it.spanId }
        (outcomes - actieOutcome).forEach { outcome ->
            val betrokkeneRegel = byId.getValue(checkNotNull(outcome.parentSpanId))
            assert(betrokkeneRegel.parentSpanId == span.spanContext.spanId)
            assert(ProcessingHandler.OUTCOME_ATTRIBUTE_KEY !in betrokkeneRegel.attributes)
            assert(outcome.attributes["dpl.core.data_subject_id"] == betrokkeneRegel.attributes["dpl.core.data_subject_id"])
        }
    }

    @Test
    fun `A write failure of the outcome logregel is logged and not thrown`() {
        val span = handler.startSpan("aanleveren", null)
        val logregels = handler.addLogboekContextToSpan(span, singleSubjectContext())
        span.end()
        handler.enforceWriteAcknowledgement()

        sql("ALTER TABLE $TABLE RENAME TO ${TABLE}_weg")
        val lost = try {
            handler.recordFailedOutcome(logregels, IllegalStateException("levering mislukt"))
        } finally {
            sql("ALTER TABLE ${TABLE}_weg RENAME TO $TABLE")
        }

        assert(lost == logregels) { "the caller must learn that the Logboek now under-reports, got $lost" }
        assert(LogboekWriteFailureRecorder.consume() == null) { "the outcome write failure must not linger for a later action" }
        assert(rows().size == 1)
    }

    private fun singleSubjectContext() = LogboekContext().apply {
        processingActivityId = ACTIVITY
        dataSubjectId = "subject-1"
        dataSubjectType = "KVK"
    }

    private fun sql(statement: String) {
        postgres.postgresDatabase.connection.use { it.createStatement().execute(statement) }
    }

    private fun rows(): List<Row> = postgres.postgresDatabase.connection.use { conn ->
        conn.createStatement().executeQuery(
            "SELECT trace_id, span_id, parent_span_id, status, \"name\", attributes::text FROM $TABLE",
        ).use { rs ->
            generateSequence { if (rs.next()) rs else null }.map {
                Row(
                    traceId = it.getString(1),
                    spanId = it.getString(2),
                    parentSpanId = it.getString(3),
                    status = it.getString(4),
                    name = it.getString(5),
                    attributes = objectMapper.readValue(it.getString(6), object : TypeReference<Map<String, String>>() {}),
                )
            }.toList()
        }
    }
}
