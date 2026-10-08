package nl.mijnoverheidzakelijk.ldv.exporter

import nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.SanitizedWriteFailure

/**
 * Per-thread relay of the most recent Logboek write failure, so the interceptor can
 * enforce fail-closed after `span.end()`. Only works on the synchronous (SIMPLE) path
 * where export runs on the request thread; under BATCH it degrades to log-only.
 *
 * What is recorded becomes the cause of
 * [nl.mijnoverheidzakelijk.ldv.logboekdataverwerking.LogboekWriteException] and so
 * reaches the caller, which is why only a [SanitizedWriteFailure] can be recorded.
 */
object LogboekWriteFailureRecorder {
    private val failure = ThreadLocal<SanitizedWriteFailure?>()

    /** Records an export failure for the current thread. */
    fun record(failure: SanitizedWriteFailure) = this.failure.set(failure)

    /** Returns and clears any failure recorded for the current thread. */
    fun consume(): SanitizedWriteFailure? {
        val v = failure.get()
        failure.remove()
        return v
    }

    /** Clears any stale failure left on a (pooled) thread by an earlier request. */
    fun clear() = failure.remove()
}
