package nl.mijnoverheidzakelijk.ldv.exporter

import java.sql.SQLException

/**
 * A Logboek write failure stripped of everything that can carry the content of a
 * logregel. A database driver echoes the rejected statement in its exception message
 * (PostgreSQL's `BatchUpdateException` holds the full `INSERT`, attributes and all), so
 * the original exception must reach neither the application log nor the caller.
 *
 * What remains is enough to diagnose: the exception types of the cause chain, the
 * SQLState of each [SQLException], and the stack frames of the original failure. The
 * original itself, its messages and its suppressed exceptions are dropped.
 */
class SanitizedWriteFailure private constructor(message: String) :
    RuntimeException(message, null, false, true) {

    companion object {
        // Bounds the rendered chain; also what ends a cyclic one.
        private const val MAX_CHAIN_DEPTH = 10

        /** Returns [failure] itself when it is already sanitized. */
        fun of(failure: Throwable): SanitizedWriteFailure {
            if (failure is SanitizedWriteFailure) return failure
            val chain = generateSequence(failure) { it.cause }
                .take(MAX_CHAIN_DEPTH)
                .toList()
                .let { links -> links.filterIndexed { i, link -> links.subList(0, i).none { it === link } } }
            val sanitized = SanitizedWriteFailure(chain.joinToString(" <- ", transform = ::describe))
            sanitized.stackTrace = failure.stackTrace
            return sanitized
        }

        private fun describe(t: Throwable): String {
            val sqlState = (t as? SQLException)?.sqlState
            return if (sqlState == null) t.javaClass.name else "${t.javaClass.name} [SQLState $sqlState]"
        }
    }
}
