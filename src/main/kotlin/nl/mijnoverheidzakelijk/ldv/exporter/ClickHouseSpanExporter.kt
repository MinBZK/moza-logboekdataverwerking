package nl.mijnoverheidzakelijk.ldv.exporter

import com.fasterxml.jackson.databind.ObjectMapper
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SpanExporter
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader
import nl.mijnoverheidzakelijk.ldv.repository.ClickHouseRepository
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger

/**
 * OpenTelemetry [SpanExporter] that converts spans to JSON and writes them
 * to a ClickHouse table.
 * 
 * The exporter is enabled based on the configuration key
 * `logboekdataverwerking.enabled`. When enabled, it ensures the ClickHouse schema
 * exists and inserts exported spans into the configured table.
 */
class ClickHouseSpanExporter (
        private val repository: ClickHouseRepository = ClickHouseRepository(),
        private val tableName: String = ConfigurationLoader.clickhouseTable,
        private val objectMapper: ObjectMapper = ObjectMapper()
    ) : SpanExporter {

    /**
     * Creates a new exporter instance using configuration values provided via
     * [ConfigurationLoader].
     * 
     * @throws org.apache.commons.configuration2.ex.ConfigurationException if configuration cannot be read
     */
    init {
        repository.ensureSchema()
    }

    /**
     * Exports a collection of spans to ClickHouse. Spans are serialized to JSON.
     * 
     * @param spans the spans to export
     * @return success or failure result code
     */
    override fun export(spans: MutableCollection<SpanData>): CompletableResultCode {
        val payload = StringBuilder()

        try {
            for (span in spans) {
                val spanMap = mapSpanToJson(span)
                val mappedString = objectMapper.writeValueAsString(spanMap)
                payload.append(mappedString).append("\n")
            }

            if (payload.isNotEmpty()) {
                repository.insertJsonEachRow(tableName, payload.toString())
            }
        } catch (e: Exception) {
            LOGGER.log(Level.SEVERE, "Failed to insert spans into ClickHouse", e)
            return CompletableResultCode.ofFailure()
        }

        return CompletableResultCode.ofSuccess()
    }

    /**
     * Maps [SpanData] to a JSON-compatible map structure.
     * 
     * @param span the span to map
     * @return a map representing the span suitable for JSON serialization
     */
    private fun mapSpanToJson(span: SpanData): Map<String, Any> {
        return mapOf(
            "traceId" to span.traceId,
            "spanId" to span.spanId,
            "status" to span.status.statusCode.name,
            "name" to span.name,
            "startTime" to TimeUnit.NANOSECONDS.toMillis(span.startEpochNanos),
            "endTime" to TimeUnit.NANOSECONDS.toMillis(span.endEpochNanos),
            "parentSpanId" to span.parentSpanId,
            "attributes" to span.attributes.asMap().entries.associate { it.key.key to it.value.toString() },
            "resource" to span.resource.attributes.asMap().entries.associate { it.key.key to it.value.toString() }
        )
    }

    /**
     * No-op flush for this exporter. Returns success.
     * 
     * @return success result code
     */
    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    /**
     * Shuts down the exporter. No resources to free, returns success.
     * 
     * @return success result code
     */
    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()

    companion object {
        private val LOGGER: Logger = Logger.getLogger(ClickHouseSpanExporter::class.java.getName())
    }
}
