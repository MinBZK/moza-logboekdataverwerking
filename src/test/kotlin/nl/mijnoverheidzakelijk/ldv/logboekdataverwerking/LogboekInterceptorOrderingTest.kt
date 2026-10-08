package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import io.quarkus.test.junit.QuarkusTest
import io.restassured.RestAssured.given
import jakarta.inject.Inject
import jakarta.transaction.Status
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Runs the interceptor in a real Quarkus container next to `@Transactional`, so the
 * interceptor order is the one consumers get. The config in `src/test/resources`
 * selects [RefusingLogboekDriver] as PostgreSQL backend, which accepts the connection
 * and the schema. Whether it refuses the insert is switched per test through
 * [RefusingLogboekDriverRegistration]: refused, every logregel write fails under
 * `simple` + `fail-closed` and the fail-closed check throws; accepted, the transaction
 * must still commit, which guards against an interceptor that rolls everything back.
 */
@QuarkusTest
class LogboekInterceptorOrderingTest {

    @Inject
    lateinit var probe: TransactionProbe

    @Inject
    lateinit var driver: RefusingLogboekDriverRegistration

    @BeforeEach
    fun reset() {
        probe.reset()
        driver.refuseInserts = true
    }

    @Test
    fun `a mutation whose logregel cannot be stored rolls back instead of committing`() {
        given().post("/interceptor-ordering/mutatie").then().statusCode(500)

        assertEquals(true, probe.activeDuringMethod, "the method must run inside the transaction")
        assertEquals(
            Status.STATUS_ROLLEDBACK,
            probe.completionStatus,
            "the acknowledgement must come before the commit, so a failed write rolls the mutation back",
        )
    }

    @Test
    fun `a mutation whose logregel is stored commits`() {
        driver.refuseInserts = false

        given().post("/interceptor-ordering/mutatie").then().statusCode(200)

        assertEquals(true, probe.activeDuringMethod, "the method must run inside the transaction")
        assertEquals(
            Status.STATUS_COMMITTED,
            probe.completionStatus,
            "a stored logregel must leave the transaction alone, so the mutation commits",
        )
    }
}
