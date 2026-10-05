package nl.mijnoverheidzakelijk.ldv.repository

/**
 * Raised by a [SpanRepository] when a storage operation fails.
 *
 * Everything on this type itself is safe to log: the message is a fixed text
 * written in this library and the other properties are identifiers the database
 * hands out. The [cause] is the driver's exception and is NOT safe, since a driver
 * can echo the rejected statement or row; see
 * [nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.SanitizedWriteFailure].
 *
 * @property vendorCode the database's own error code, when the backend reports one
 *   outside the SQLState (ClickHouse server error code)
 * @property queryId the id under which the database logged the query, when known
 *   (ClickHouse `system.query_log`)
 */
class SpanStorageException internal constructor(
    message: String,
    cause: Throwable?,
    val vendorCode: Int? = null,
    val queryId: String? = null,
) : RuntimeException(message, cause)
