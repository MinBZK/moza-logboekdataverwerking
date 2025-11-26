package nl.rijksoverheid.moz.logboekdataverwerking;

import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.TextMapGetter;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;

import io.opentelemetry.sdk.trace.ReadableSpan;

@Logboek
@Interceptor
public class LogboekInterceptor {

    @Inject
    LogboekContext logboekContext;

    @Context
    HttpHeaders headers;

    @Inject
    ProcessingHandler handler;

    @AroundInvoke
    public Object log(InvocationContext invocationContext) throws Exception {
        // Propagater om trace informatie uit HTTP headers op te halen
        var propagatorInstance = W3CTraceContextPropagator.getInstance();
        // Voer de propagater uit op de headers. Als er geen informatie uit
        // de headers te behalen is, dan wordt er automatisch een nieuwe
        // trace context aangemaakt. Anders wordt dat gedaan op basis van
        // de headers met de implementatie van `HttpHeadersGetter`.
        var traceContext = propagatorInstance.extract(
                io.opentelemetry.context.Context.current(), 
                headers, 
                new HttpHeadersGetter()
        );

        // Deze annotation moet op de service method
        Logboek annotation = invocationContext.getMethod().getAnnotation(Logboek.class);
        if (annotation == null) {
            throw new IllegalArgumentException("Logboek annotation mist op service method");
        }

        // Start een span binnen deze trace. Dit zorgt er onder andere voor dat de `start_time`
        // goed staat. De andere attributen worden toegevoegd nadat de service method
        // is uitgevoerd, omdat in de service method de `LogboekContext` verder wordt gevuld
        var span = handler.startSpan(annotation.name(), traceContext);

        // Voer alle acties uit als onderdeel van deze span. Omdat de span `AutoCloseable`
        // is, zal automatisch de context weer terug worden gezet naar de originele trace
        // context wanneer deze interceptor klaar is.
        try (var scoped = span.makeCurrent()) {
            // Voer de business logica van de service method uit.
            return invocationContext.proceed();
        } catch (Exception e){
            // Overwrite de status van de service method en zet deze altijd op `Error`.
            span.setStatus(StatusCode.ERROR);
            // Zet alle attributen als onderdeel van de foutafhandeling.
            span.setAttribute("exception.message", e.getMessage());
            span.setAttribute("exception.type", String.valueOf(e.getClass()));
            // Rethrow de exceptie zodat die correct wordt terug gegeven door de service.
            throw e;
        } finally {
            // Elke span is altijd ook readable. Dit is nodig om er ook nog weer informatie
            // uit te halen, zodat we ook dingen kunnen toevoegen aan de span.
            var spanData = ((ReadableSpan) span).toSpanData();

            // Check of we extern zijn aangeroepen en alleen dan moeten we de attributen zetten
            if (headers.getHeaderString("traceparent") != null) {
                span.setAttribute("dpl.core.foreign_operation.span_id", spanData.getParentSpanId());

                // TODO: Haal de URL van de externe applicatie op. Dit hangt af van het applicatielandschap.
                // Dit kan bijvoorbeeld worden gehaald uit een FSC contract, op basis van bestaande
                // Digikoppeling informatie of moet worden bepaald op basis van API keys.
                span.setAttribute("dpl.core.foreign_operation.processor", headers.getHeaderString("traceparent-processor"));
            }

            // Zet de `processing_activity_id` op basis van de annotation. Deze is statisch
            // en hoort niet te veranderen tussen verschillende calls.
            logboekContext.setProcessingActivityId(annotation.processingActivityId());
            // Zet overige attributen op de context.
            logboekContext.addLogboekContextToSpan(span);
            // Sluit deze span af en populate daarmee de `end_time`.
            span.end();
        }
    }

    private static class HttpHeadersGetter implements TextMapGetter<HttpHeaders> {
        @Override
        public Iterable<String> keys(HttpHeaders httpHeaders) {
            return httpHeaders.getRequestHeaders().keySet();
        }

        @Override
        public String get(HttpHeaders httpHeaders, String key) {
            return httpHeaders.getHeaderString(key);
        }
    }
}
