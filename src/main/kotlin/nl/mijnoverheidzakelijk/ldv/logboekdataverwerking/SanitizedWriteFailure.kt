package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import nl.mijnoverheidzakelijk.ldv.repository.SpanStorageException
import java.sql.SQLException

/**
 * A Logboek write failure stripped of everything that can carry the content of a
 * logregel. A database driver can echo the rejected statement or row in its exception
 * message (the PostgreSQL JDBC driver puts the full `INSERT`, attributes and all, in
 * the message of the `BatchUpdateException`), so an exception the exporter handles
 * must reach neither the application log nor the caller. (A JVM `Error` thrown by the
 * exporter is not caught there and propagates as is.)
 *
 * Each exception of the original cause chain becomes one [SanitizedWriteFailure],
 * linked through [cause] and carrying that exception's own stack frames. Kept per
 * link, because none of it can hold logregel content:
 * - the exception [type];
 * - the [sqlState] and [vendorCode] of an [SQLException], and the SQLStates of its
 *   `nextException` chain in [nextSqlStates];
 * - the [vendorCode] and [queryId] a [SpanStorageException] took from the database;
 * - the message, as [detail], of a [SpanStorageException] (a fixed text of this
 *   library) and of a JVM `VirtualMachineError` or `LinkageError`;
 * - the types of its suppressed exceptions in [suppressedTypes].
 *
 * Every other message is dropped, and so are the original exceptions themselves. A
 * chain longer than [MAX_CHAIN_DEPTH] keeps its outermost links and its root cause;
 * [omittedCauses] on the link before the root tells how many were left out.
 *
 * @property type class name of the exception this link stands for
 * @property detail the exception's message, only for the types listed above
 * @property sqlState five-character SQLState, when the exception is an [SQLException]
 *   that has a well-formed one
 * @property vendorCode the database's own error code, when it reports one
 * @property queryId the id under which the database logged the query, when known
 * @property nextSqlStates SQLStates of the `nextException` chain that are not part
 *   of the cause chain
 * @property suppressedTypes class names of the suppressed exceptions
 * @property omittedCauses number of links left out between this one and its [cause]
 */
class SanitizedWriteFailure private constructor(
    val type: String,
    val detail: String?,
    val sqlState: String?,
    val vendorCode: Int?,
    val queryId: String?,
    val nextSqlStates: List<String>,
    val suppressedTypes: List<String>,
    val omittedCauses: Int,
    override val cause: SanitizedWriteFailure?,
) : RuntimeException(
    render(type, detail, sqlState, vendorCode, queryId, nextSqlStates, suppressedTypes, omittedCauses),
    // Passing the cause explicitly, even when null, is what makes initCause throw;
    // enableSuppression=false is what makes addSuppressed a no-op. Both keep an
    // unsanitized exception from being attached afterwards.
    cause,
    false,
    true,
) {

    /** This link and its causes, outermost first. */
    val chain: List<SanitizedWriteFailure>
        get() = generateSequence(this) { it.cause }.toList()

    // The frames are those of the original exception, set by [of]; skip the stack walk.
    override fun fillInStackTrace(): Throwable = this

    companion object {
        /** Upper bound on the links kept from one cause chain. */
        const val MAX_CHAIN_DEPTH = 10

        // Bounds the walk itself, for a chain that never ends without repeating a link.
        private const val MAX_WALK = 1_000
        private const val MAX_LISTED = 10
        private val SQL_STATE = Regex("^[0-9A-Z]{5}$")
        private val QUERY_ID = Regex("^[0-9A-Za-z_-]{1,64}$")

        /**
         * Builds the sanitized form of [failure]; returns [failure] itself when it is
         * already sanitized. Never throws: an exception that cannot be read (a getter
         * that throws, a null stack trace) is reduced to its type alone.
         */
        @JvmStatic
        fun of(failure: Throwable): SanitizedWriteFailure {
            if (failure is SanitizedWriteFailure) return failure
            return try {
                sanitize(failure)
            } catch (e: Exception) {
                link(failure.javaClass.name)
            }
        }

        private fun sanitize(failure: Throwable): SanitizedWriteFailure {
            val links = ArrayList<Throwable>()
            var current: Throwable? = failure
            while (current != null && current !is SanitizedWriteFailure &&
                links.size < MAX_WALK && links.none { it === current }
            ) {
                links += current
                current = current.cause
            }
            // A chain that ends in a sanitized failure continues with it as is.
            var inner = current as? SanitizedWriteFailure

            val omitted = (links.size - MAX_CHAIN_DEPTH).coerceAtLeast(0)
            val kept = if (omitted == 0) links else links.take(MAX_CHAIN_DEPTH - 1) + links.last()
            for (i in kept.indices.reversed()) {
                val original = kept[i]
                val sql = original as? SQLException
                val storage = original as? SpanStorageException
                inner = link(
                    type = original.javaClass.name,
                    detail = safeDetail(original),
                    sqlState = sql?.sqlState?.takeIf(SQL_STATE::matches),
                    vendorCode = sql?.errorCode?.takeIf { it != 0 } ?: storage?.vendorCode,
                    queryId = storage?.queryId?.takeIf(QUERY_ID::matches),
                    nextSqlStates = sql?.let { nextSqlStates(it, links) }.orEmpty(),
                    suppressedTypes = original.suppressed.take(MAX_LISTED).map { it.javaClass.name },
                    omittedCauses = if (i == kept.size - 2) omitted else 0,
                    cause = inner,
                ).also { it.stackTrace = original.stackTrace }
            }
            return checkNotNull(inner)
        }

        private fun link(
            type: String,
            detail: String? = null,
            sqlState: String? = null,
            vendorCode: Int? = null,
            queryId: String? = null,
            nextSqlStates: List<String> = emptyList(),
            suppressedTypes: List<String> = emptyList(),
            omittedCauses: Int = 0,
            cause: SanitizedWriteFailure? = null,
        ) = SanitizedWriteFailure(
            type, detail, sqlState, vendorCode, queryId,
            java.util.List.copyOf(nextSqlStates), java.util.List.copyOf(suppressedTypes), omittedCauses, cause,
        )

        /** The message, for the types whose message cannot hold logregel content. */
        private fun safeDetail(t: Throwable): String? = when (t) {
            is SpanStorageException, is VirtualMachineError, is LinkageError -> t.message
            else -> null
        }

        /** SQLStates that hang off `nextException` only; a driver may also chain them as cause. */
        private fun nextSqlStates(sql: SQLException, links: List<Throwable>): List<String> =
            generateSequence(sql.nextException) { it.nextException }
                .take(MAX_LISTED)
                .filter { next -> links.none { it === next } }
                .mapNotNull { it.sqlState?.takeIf(SQL_STATE::matches) }
                .distinct()
                .toList()

        private fun render(
            type: String,
            detail: String?,
            sqlState: String?,
            vendorCode: Int?,
            queryId: String?,
            nextSqlStates: List<String>,
            suppressedTypes: List<String>,
            omittedCauses: Int,
        ): String {
            val codes = listOfNotNull(
                sqlState?.let { "SQLState $it" },
                vendorCode?.let { "code $it" },
                queryId?.let { "queryId $it" },
                nextSqlStates.takeIf { it.isNotEmpty() }?.let { "next SQLStates ${it.joinToString(" ")}" },
            )
            return buildString {
                append(type)
                if (codes.isNotEmpty()) append(codes.joinToString(", ", " [", "]"))
                if (detail != null) append(": ").append(detail)
                if (suppressedTypes.isNotEmpty()) append(suppressedTypes.joinToString(", ", " (suppressed: ", ")"))
                if (omittedCauses > 0) append(" (… ").append(omittedCauses).append(" cause(s) omitted)")
            }
        }
    }
}
