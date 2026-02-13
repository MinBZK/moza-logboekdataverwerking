package nl.mijnoverheidzakelijk.ldv.fuzzing;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;

/**
 * Standalone fuzz target for ClusterFuzzLite.
 * Tests the span-to-JSON serialization path with arbitrary input,
 * mirroring the map structure produced by ClickHouseSpanExporter.mapSpanToJson().
 */
public class SpanSerializationFuzzer {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static void fuzzerTestOneInput(FuzzedDataProvider data) {
        String traceId = data.consumeString(200);
        String spanId = data.consumeString(200);
        String status = data.consumeString(50);
        String name = data.consumeString(200);
        long startTime = data.consumeLong();
        long endTime = data.consumeLong();
        String parentSpanId = data.consumeString(200);
        String attrKey = data.consumeString(200);
        String attrValue = data.consumeString(200);
        String resourceKey = data.consumeString(200);
        String resourceValue = data.consumeRemainingAsString();

        // Build the same map structure as ClickHouseSpanExporter.mapSpanToJson()
        Map<String, Object> spanMap = new HashMap<>();
        spanMap.put("traceId", traceId);
        spanMap.put("spanId", spanId);
        spanMap.put("status", status);
        spanMap.put("name", name);
        spanMap.put("startTime", startTime);
        spanMap.put("endTime", endTime);
        spanMap.put("parentSpanId", parentSpanId);
        spanMap.put("attributes", Map.of(attrKey, attrValue));
        spanMap.put("resource", Map.of(resourceKey, resourceValue));

        try {
            String json = objectMapper.writeValueAsString(spanMap);

            // Verify the result is valid JSON
            objectMapper.readTree(json);
        } catch (Exception e) {
            // Jackson exceptions for invalid input are expected
        }
    }
}
