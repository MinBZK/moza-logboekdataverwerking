package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

/**
 * Raised when a logregel could not be written to the Logboek and the configured
 * [nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader.WriteFailurePolicy] is
 * `FAIL_CLOSED`. Propagating this prevents a verwerking from being reported as
 * completed-and-logged when its logregel was not actually stored.
 */
class LogboekWriteException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
