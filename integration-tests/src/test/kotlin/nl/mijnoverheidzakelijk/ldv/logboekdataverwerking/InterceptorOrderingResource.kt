package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import jakarta.enterprise.context.ApplicationScoped
import jakarta.inject.Inject
import jakarta.transaction.Status
import jakarta.transaction.Synchronization
import jakarta.transaction.TransactionSynchronizationRegistry
import jakarta.transaction.Transactional
import jakarta.ws.rs.POST
import jakarta.ws.rs.Path

/** What the transaction around the intercepted method did, read by [LogboekInterceptorOrderingTest]. */
@ApplicationScoped
class TransactionProbe {
    @Volatile
    var activeDuringMethod: Boolean? = null

    /** A `jakarta.transaction.Status` value, set by the synchronization after completion. */
    @Volatile
    var completionStatus: Int? = null

    fun reset() {
        activeDuringMethod = null
        completionStatus = null
    }
}

/** A `@Logboek` mutation inside `@Transactional`, as consumers write their controller methods. */
@Path("/interceptor-ordering")
@ApplicationScoped
class InterceptorOrderingResource {

    @Inject
    lateinit var registry: TransactionSynchronizationRegistry

    @Inject
    lateinit var probe: TransactionProbe

    @Inject
    lateinit var logboekContext: LogboekContext

    @POST
    @Path("/mutatie")
    @Transactional
    @Logboek(name = "mutatie", processingActivityId = "https://example.org/verwerkingsactiviteiten/test")
    fun mutatie(): String {
        logboekContext.dataSubjectId = "123456789"
        logboekContext.dataSubjectType = "bsn"
        probe.activeDuringMethod = registry.transactionStatus == Status.STATUS_ACTIVE
        registry.registerInterposedSynchronization(object : Synchronization {
            override fun beforeCompletion() = Unit

            override fun afterCompletion(status: Int) {
                probe.completionStatus = status
            }
        })
        return "ok"
    }
}
