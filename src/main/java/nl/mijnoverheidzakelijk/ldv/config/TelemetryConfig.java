package nl.mijnoverheidzakelijk.ldv.config;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import nl.mijnoverheidzakelijk.ldv.exporter.ClickHouseSpanExporter;
import org.apache.commons.configuration2.ex.ConfigurationException;

/**
 * Configures and provides a singleton {@link OpenTelemetry} instance for the application.
 * <p>
 * The configuration sets the service.name resource attribute and registers a
 * {@link BatchSpanProcessor} that exports spans to ClickHouse via
 * {@link ClickHouseSpanExporter}.
 */
public final class TelemetryConfig {
    private static OpenTelemetry instance;
    private static boolean initialized = false;
    
    private TelemetryConfig() {
    }

    /**
     * Initializes and returns the global {@link OpenTelemetry} instance.
     * Subsequent calls return the already initialized instance.
     *
     * @param serviceName the service name to be set on spans as a resource attribute
     * @return the initialized {@link OpenTelemetry} instance
     * @throws ConfigurationException if exporter configuration cannot be read
     */
    public static synchronized OpenTelemetry initOpenTelemetry(String serviceName) throws ConfigurationException {
        if (initialized) {
            return instance;
        }

        Resource resource = Resource.getDefault().merge(Resource.create(Attributes.of(
                AttributeKey.stringKey("service.name"), serviceName
        )));

        ClickHouseSpanExporter exporter = new ClickHouseSpanExporter();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .build();

        OpenTelemetrySdk openTelemetrySdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();
        Runtime.getRuntime().addShutdownHook(new Thread(openTelemetrySdk::close));
        
        instance = openTelemetrySdk;
        initialized = true;
        return openTelemetrySdk;
    }
}