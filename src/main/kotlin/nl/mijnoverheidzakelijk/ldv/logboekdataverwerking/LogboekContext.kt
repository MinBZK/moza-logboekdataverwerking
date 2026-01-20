package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import io.opentelemetry.api.trace.StatusCode
import jakarta.enterprise.context.RequestScoped

/**
 * Request-scoped holder for LogboekDataverwerking-related context data that will be attached to spans.
 * This includes identifiers for the processing activity, data subject, and the span status.
 */
@RequestScoped
class LogboekContext {
    var processingActivityId: String? = null
    var dataSubjectId: String? = null
    var dataSubjectType: String? = null
    var status: StatusCode? = null
}
