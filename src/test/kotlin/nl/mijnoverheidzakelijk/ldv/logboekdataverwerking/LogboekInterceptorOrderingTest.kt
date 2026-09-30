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
 * and the schema and refuses every insert, so under `simple` + `fail-closed` every
 * logregel write fails and the fail-closed check throws.
 */
@QuarkusTest
class LogboekInterceptorOrderingTest {

    @Inject
    lateinit var probe: TransactionProbe

    @BeforeEach
    fun reset() = probe.reset()

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
}
