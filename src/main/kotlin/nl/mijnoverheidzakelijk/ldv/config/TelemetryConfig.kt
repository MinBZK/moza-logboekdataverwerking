package nl.mijnoverheidzakelijk.ldv.config

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.resources.Resource
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import nl.mijnoverheidzakelijk.ldv.exporter.ClickHouseSpanExporter
import nl.mijnoverheidzakelijk.ldv.exporter.DummySpanExporter
import org.apache.commons.configuration2.ex.ConfigurationException

/**
 * Configures and provides a singleton [OpenTelemetry] instance for the application.
 * 
 * 
 * The configuration sets the service.name resource attribute and registers a
 * [BatchSpanProcessor] that exports spans to ClickHouse via
 * [ClickHouseSpanExporter].
 */
object TelemetryConfig {
    /**
     * Initializes and returns the global [OpenTelemetry] instance.
     * Subsequent calls return the already initialized instance.
     * 
     * @param serviceName the service name to be set on spans as a resource attribute
     * @return the initialized [OpenTelemetry] instance
     * @throws ConfigurationException if exporter configuration cannot be read
     */
    @Synchronized
    @Throws(ConfigurationException::class)
    fun initOpenTelemetry(serviceName: String): OpenTelemetry {
        val resource = Resource.getDefault().merge(
            Resource.create(
                Attributes.of<String>(
                    AttributeKey.stringKey("service.name"), serviceName
                )
            )
        )

        val exporter =
            if (ConfigurationLoader.getValueByKey("logboekdataverwerking.enabled", Boolean::class.java))
                ClickHouseSpanExporter()
            else
                DummySpanExporter()

        val tracerProvider = SdkTracerProvider.builder()
            .setResource(resource)
            .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
            .build()

        val openTelemetrySdk = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .buildAndRegisterGlobal()

        Runtime.getRuntime().addShutdownHook(Thread { openTelemetrySdk.close() })

        return openTelemetrySdk
    }
}
