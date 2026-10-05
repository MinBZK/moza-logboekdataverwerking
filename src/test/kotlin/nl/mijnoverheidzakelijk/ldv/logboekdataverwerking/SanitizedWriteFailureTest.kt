package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import nl.mijnoverheidzakelijk.ldv.repository.SpanStorageException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException

internal class SanitizedWriteFailureTest {

    @Test
    fun `Every exception of the cause chain becomes a link that names its type`() {
        val failure = SanitizedWriteFailure.of(RuntimeException("outer", IllegalStateException("inner")))

        assert(failure.chain.map { it.type } == listOf("java.lang.RuntimeException", "java.lang.IllegalStateException"))
        assert(failure.message == "java.lang.RuntimeException") { failure.message ?: "" }
        assert(failure.cause?.message == "java.lang.IllegalStateException")
    }

    @Test
    fun `Each link keeps the stack frames of its own exception`() {
        val driver = driverFailure()
        val wrapper = RuntimeException("wrapped", driver)

        val failure = SanitizedWriteFailure.of(wrapper)

        assert(failure.stackTrace.contentEquals(wrapper.stackTrace))
        val cause = checkNotNull(failure.cause)
        assert(cause.stackTrace.contentEquals(driver.stackTrace)) { "the frames of the cause must survive" }
        assert(cause.stackTrace.any { it.methodName == "driverFailure" })
    }

    @Test
    fun `Keeps the SQLState and the vendor code and drops the message`() {
        val failure = SanitizedWriteFailure.of(SQLException("INSERT ... $BSN was aborted", "23514", 7))

        assert(failure.message == "java.sql.SQLException [SQLState 23514, code 7]") { failure.message ?: "" }
        assert(failure.sqlState == "23514")
        assert(failure.vendorCode == 7)
        assert(failure.detail == null)
    }

    @Test
    fun `An SQLException without SQLState or vendor code renders as its type alone`() {
        val failure = SanitizedWriteFailure.of(SQLException("PostgreSQL batch insert reported a failed row"))

        assert(failure.message == "java.sql.SQLException") { failure.message ?: "" }
        assert(failure.sqlState == null && failure.vendorCode == null)
    }

    @Test
    fun `A malformed SQLState is dropped`() {
        val failure = SanitizedWriteFailure.of(SQLException("x", "subject $BSN"))

        assert(failure.sqlState == null)
        assert(!failure.stackTraceToString().contains(BSN))
    }

    @Test
    fun `Keeps the SQLStates that only hang off nextException`() {
        val first = SQLException("row 1 $BSN", "23505")
        first.setNextException(SQLException("row 2 $BSN", "40001"))

        val failure = SanitizedWriteFailure.of(first)

        assert(failure.nextSqlStates == listOf("40001")) { "got ${failure.nextSqlStates}" }
        assert(failure.message == "java.sql.SQLException [SQLState 23505, next SQLStates 40001]") { failure.message ?: "" }
    }

    @Test
    fun `A nextException that is also the cause is listed once, as the cause`() {
        val next = SQLException("detail", "23514")
        val batch = SQLException("batch", "23514", next)
        batch.setNextException(next)

        val failure = SanitizedWriteFailure.of(batch)

        assert(failure.nextSqlStates.isEmpty()) { "got ${failure.nextSqlStates}" }
        assert(failure.cause?.sqlState == "23514")
    }

    @Test
    fun `Keeps the fixed message, error code and query id of a storage exception`() {
        val failure = SanitizedWriteFailure.of(
            SpanStorageException.create("Failed to insert into ClickHouse", IllegalStateException(BSN), 60, "abc-123"),
        )

        assert(
            failure.message == "nl.mijnoverheidzakelijk.ldv.repository.SpanStorageException " +
                "[code 60, queryId abc-123]: Failed to insert into ClickHouse",
        ) { failure.message ?: "" }
        assert(failure.vendorCode == 60 && failure.queryId == "abc-123")
        assert(!failure.stackTraceToString().contains(BSN))
    }

    @Test
    fun `A malformed query id is dropped`() {
        val failure = SanitizedWriteFailure.of(SpanStorageException.create("Failed to insert into ClickHouse", null, 60, "id $BSN"))

        assert(failure.queryId == null)
        assert(!failure.stackTraceToString().contains(BSN))
    }

    @Test
    fun `Keeps the message of a JVM error`() {
        assert(SanitizedWriteFailure.of(OutOfMemoryError("Java heap space")).detail == "Java heap space")
        assert(SanitizedWriteFailure.of(NoClassDefFoundError("org/postgresql/Driver")).detail == "org/postgresql/Driver")
    }

    @Test
    fun `Drops the message of a JVM error that quotes the exception behind it`() {
        val initializer = ExceptionInInitializerError("Exception java.lang.IllegalStateException: $BSN")
        val bootstrap = BootstrapMethodError(IllegalStateException(BSN))

        assert(SanitizedWriteFailure.of(initializer).detail == null)
        assert(!SanitizedWriteFailure.of(bootstrap).stackTraceToString().contains(BSN))
    }

    @Test
    fun `Carries neither the original exception nor its suppressed ones, only their types`() {
        val original = RuntimeException(BSN)
        original.addSuppressed(IllegalStateException(BSN))

        val failure = SanitizedWriteFailure.of(original)

        assert(failure.cause == null)
        assert(failure.suppressed.isEmpty())
        assert(failure.suppressedTypes == listOf("java.lang.IllegalStateException"))
        assert(failure.message == "java.lang.RuntimeException (suppressed: java.lang.IllegalStateException)")
        assert(!failure.stackTraceToString().contains(BSN))
    }

    @Test
    fun `Nothing can be attached after construction`() {
        val failure = SanitizedWriteFailure.of(RuntimeException("x"))

        failure.addSuppressed(RuntimeException(BSN))
        assertThrows<IllegalStateException> { failure.initCause(RuntimeException(BSN)) }

        assert(failure.suppressed.isEmpty())
        assert(failure.cause == null)
    }

    @Test
    fun `An already sanitized failure is returned as is`() {
        val failure = SanitizedWriteFailure.of(RuntimeException("x"))

        assert(SanitizedWriteFailure.of(failure) === failure)
    }

    @Test
    fun `A chain that ends in a sanitized failure continues with it`() {
        val inner = SanitizedWriteFailure.of(SQLException("x", "08006"))

        val failure = SanitizedWriteFailure.of(RuntimeException("outer", inner))

        assert(failure.cause === inner)
    }

    @Test
    fun `A cyclic cause chain ends`() {
        val a = RuntimeException("a")
        val b = IllegalStateException("b", a)
        a.initCause(b)

        val failure = SanitizedWriteFailure.of(a)

        assert(failure.chain.map { it.type } == listOf("java.lang.RuntimeException", "java.lang.IllegalStateException"))
    }

    @Test
    fun `A long chain keeps its root cause and tells how many links were left out`() {
        var chain: Throwable = IllegalArgumentException("root")
        repeat(14) { chain = RuntimeException("level $it", chain) }

        val links = SanitizedWriteFailure.of(chain).chain

        assert(links.size == SanitizedWriteFailure.MAX_CHAIN_DEPTH) { "got ${links.size}" }
        assert(links.last().type == "java.lang.IllegalArgumentException") { "the root cause is kept" }
        assert(links[links.size - 2].omittedCauses == 5) { "got ${links.map { it.omittedCauses }}" }
        assert(links[links.size - 2].message == "java.lang.RuntimeException (… 5 cause(s) omitted)")
    }

    @Test
    fun `An exception that cannot be read is reduced to its type instead of throwing`() {
        val failure = SanitizedWriteFailure.of(Unreadable())

        assert(failure.type == Unreadable::class.java.name)
        assert(!failure.stackTraceToString().contains(BSN))
    }

    private fun driverFailure() = SQLException("INSERT ... $BSN", "23514")

    private class Unreadable : RuntimeException(BSN) {
        override val cause: Throwable? get() = error("cause of $BSN cannot be read")
    }

    private companion object {
        const val BSN = "999993653"
    }
}
