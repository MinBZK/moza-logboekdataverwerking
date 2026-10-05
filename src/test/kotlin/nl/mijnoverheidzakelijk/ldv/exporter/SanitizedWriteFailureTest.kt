package nl.mijnoverheidzakelijk.ldv.exporter

import org.junit.jupiter.api.Test
import java.sql.SQLException

internal class SanitizedWriteFailureTest {

    @Test
    fun `Names every exception type in the cause chain, outermost first`() {
        val failure = SanitizedWriteFailure.of(RuntimeException("outer", IllegalStateException("inner")))

        assert(failure.message == "java.lang.RuntimeException <- java.lang.IllegalStateException") { failure.message ?: "" }
    }

    @Test
    fun `Keeps the SQLState and drops the message`() {
        val failure = SanitizedWriteFailure.of(SQLException("INSERT ... 999993653 was aborted", "23514"))

        assert(failure.message == "java.sql.SQLException [SQLState 23514]") { failure.message ?: "" }
    }

    @Test
    fun `Carries neither the original exception nor its suppressed ones`() {
        val original = RuntimeException("999993653")
        original.addSuppressed(IllegalStateException("999993653"))

        val failure = SanitizedWriteFailure.of(original)

        assert(failure.cause == null)
        assert(failure.suppressed.isEmpty())
        assert(!failure.stackTraceToString().contains("999993653"))
    }

    @Test
    fun `Keeps the stack frames of the original failure`() {
        val original = RuntimeException("x")

        val failure = SanitizedWriteFailure.of(original)

        assert(failure.stackTrace.contentEquals(original.stackTrace))
    }

    @Test
    fun `An already sanitized failure is returned as is`() {
        val failure = SanitizedWriteFailure.of(RuntimeException("x"))

        assert(SanitizedWriteFailure.of(failure) === failure)
    }

    @Test
    fun `A cyclic cause chain ends`() {
        val a = RuntimeException("a")
        val b = IllegalStateException("b", a)
        a.initCause(b)

        val failure = SanitizedWriteFailure.of(a)

        assert(failure.message == "java.lang.RuntimeException <- java.lang.IllegalStateException") { failure.message ?: "" }
    }
}
