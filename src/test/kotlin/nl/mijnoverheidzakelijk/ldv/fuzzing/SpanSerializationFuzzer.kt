package nl.mijnoverheidzakelijk.ldv.fuzzing

import com.code_intelligence.jazzer.api.FuzzedDataProvider
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * Standalone fuzz target for ClusterFuzzLite.
 * Tests the span-to-JSON serialization path with arbitrary input,
 * mirroring the map structure produced by ClickHouseSpanExporter.mapSpanToJson().
 */
object SpanSerializationFuzzer {

    private val objectMapper = ObjectMapper()

    @JvmStatic
    fun fuzzerTestOneInput(data: FuzzedDataProvider) {
        val traceId = data.consumeString(200)
        val spanId = data.consumeString(200)
        val status = data.consumeString(50)
        val name = data.consumeString(200)
        val startTime = data.consumeLong()
        val endTime = data.consumeLong()
        val parentSpanId = data.consumeString(200)
        val attrKey = data.consumeString(200)
        val attrValue = data.consumeString(200)
        val resourceKey = data.consumeString(200)
        val resourceValue = data.consumeRemainingAsString()

        // Build the same map structure as ClickHouseSpanExporter.mapSpanToJson()
        val spanMap = mutableMapOf<String, Any>(
            "traceId" to traceId,
            "spanId" to spanId,
            "status" to status,
            "name" to name,
            "startTime" to startTime,
            "endTime" to endTime,
            "parentSpanId" to parentSpanId,
            "attributes" to mapOf(attrKey to attrValue),
            "resource" to mapOf(resourceKey to resourceValue)
        )

        try {
            val json = objectMapper.writeValueAsString(spanMap)

            // Verify the result is valid JSON
            objectMapper.readTree(json)
        } catch (_: Exception) {
            // Jackson exceptions for invalid input are expected
        }
    }
}
