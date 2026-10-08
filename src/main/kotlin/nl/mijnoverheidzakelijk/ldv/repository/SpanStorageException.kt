package nl.mijnoverheidzakelijk.ldv.repository

/**
 * Raised by a [SpanRepository] when a storage operation fails.
 *
 * Everything on this type itself is safe to log: the message is a fixed text
 * written in this library and the other properties are identifiers the database
 * hands out. The [cause] is the underlying exception (of the driver, or of the JSON
 * serializer) and is NOT safe, since it can echo the rejected statement or row; see
 * [nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.SanitizedWriteFailure].
 *
 * Only this library creates one, which is what keeps the message a fixed text.
 *
 * @property vendorCode the database's own error code, when the backend reports one
 *   outside the SQLState (ClickHouse server error code)
 * @property queryId the id under which the database logged the query, when known
 *   (ClickHouse `system.query_log`)
 */
class SpanStorageException private constructor(
    message: String,
    cause: Throwable?,
    val vendorCode: Int?,
    val queryId: String?,
) : RuntimeException(message, cause) {

    internal companion object {
        /** @param message a fixed text; never interpolate anything into it */
        // Synthetic, so Java code outside this library cannot call it either.
        @JvmSynthetic
        internal fun create(
            message: String,
            cause: Throwable?,
            vendorCode: Int? = null,
            queryId: String? = null,
        ) = SpanStorageException(message, cause, vendorCode, queryId)
    }
}
