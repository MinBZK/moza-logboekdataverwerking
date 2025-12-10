package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import jakarta.enterprise.context.ApplicationScoped;
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader;
import nl.mijnoverheidzakelijk.ldv.config.TelemetryConfig;
import org.apache.commons.configuration2.ex.ConfigurationException;

@ApplicationScoped
public class ProcessingHandler {

    private final Tracer tracer;

    public ProcessingHandler() {
        try {
            String serviceName = ConfigurationLoader.getValueByKey("logboekdataverwerking.service-name", String.class);

            OpenTelemetry openTelemetry = TelemetryConfig.initOpenTelemetry(serviceName);
            this.tracer = openTelemetry.getTracer(serviceName);
        } catch (ConfigurationException e) {
            throw new RuntimeException("Failed to initialize ProcessingHandler", e);
        }
    }

    public Span startSpan(String name, Context context) {

        if (context != null) {
            return tracer.spanBuilder(name)
                    .setParent(context)
                    .startSpan();
        }

        return tracer.spanBuilder(name)
                .startSpan();
    }

    public void addLogboekContextToSpan(Span span, LogboekContext logboekContext) {
        span.setAttribute("dpl.core.processing_activity_id", logboekContext.getProcessingActivityId());
        span.setAttribute("dpl.core.data_subject_id", logboekContext.getDataSubjectId());
        span.setAttribute("dpl.core.data_subject_id_type", logboekContext.getDataSubjectType());
        span.setStatus(logboekContext.getStatus());
    }
}