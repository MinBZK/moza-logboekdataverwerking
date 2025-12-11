package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking;

import io.opentelemetry.api.trace.StatusCode;
import jakarta.enterprise.context.RequestScoped;

/**
 * Request-scoped holder for LogboekDataverwerking-related context data that will be attached to spans.
 * <p>
 * This includes identifiers for the processing activity, data subject, and the span status.
 */
@RequestScoped
public class LogboekContext {

    private String ProcessingActivityId;
    private String DataSubjectId;
    private String DataSubjectType;
    private StatusCode status;

    /**
     * @return the processing activity identifier
     */
    public String getProcessingActivityId() {
        return ProcessingActivityId;
    }

    /**
     * Sets the processing activity identifier.
     *
     * @param processingActivityId the processing activity id
     */
    public void setProcessingActivityId(String processingActivityId) {
        ProcessingActivityId = processingActivityId;
    }

    /**
     * @return the data subject identifier
     */
    public String getDataSubjectId() {
        return DataSubjectId;
    }

    /**
     * Sets the data subject identifier.
     *
     * @param dataSubjectId id of the data subject
     */
    public void setDataSubjectId(String dataSubjectId) {
        DataSubjectId = dataSubjectId;
    }

    /**
     * @return the type of data subject identifier
     */
    public String getDataSubjectType() {
        return DataSubjectType;
    }

    /**
     * Sets the type of the data subject identifier (e.g., BSN, UUID).
     *
     * @param dataSubjectType the identifier type
     */
    public void setDataSubjectType(String dataSubjectType) {
        DataSubjectType = dataSubjectType;
    }

    /**
     * @return the span status to apply
     */
    public StatusCode getStatus() {
        return status;
    }

    /**
     * Sets the span status to apply.
     *
     * @param status the {@link StatusCode}
     */
    public void setStatus(StatusCode status) {
        this.status = status;
    }

}
