package nl.mijnoverheidzakelijk.ldv.exporter

/**
 * Per-thread relay of the most recent Logboek write failure, so the interceptor can
 * enforce fail-closed after `span.end()`. Only works on the synchronous (SIMPLE) path
 * where export runs on the request thread; under BATCH it degrades to log-only.
 */
object LogboekWriteFailureRecorder {
    private val failure = ThreadLocal<Throwable?>()

    /** Records an export failure for the current thread. */
    fun record(t: Throwable) = failure.set(t)

    /** Returns and clears any failure recorded for the current thread. */
    fun consume(): Throwable? {
        val v = failure.get()
        failure.remove()
        return v
    }

    /** Clears any stale failure left on a (pooled) thread by an earlier request. */
    fun clear() = failure.remove()
}
