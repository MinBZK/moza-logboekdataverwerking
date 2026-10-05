package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

/**
 * Raised when a logregel could not be written to the Logboek and the configured
 * [nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader.WriteFailurePolicy] is
 * `FAIL_CLOSED`. Propagating this prevents a verwerking from being reported as
 * completed-and-logged when its logregel was not actually stored.
 *
 * Its cause is a [SanitizedWriteFailure]: exception types, SQLState and database
 * error codes, never the driver's own exception, whose message can echo the logregel.
 *
 * @property failure the write failure, same instance as [cause]; typed, so a caller
 *   can read e.g. [SanitizedWriteFailure.sqlState] without parsing a message
 */
class LogboekWriteException(message: String, val failure: SanitizedWriteFailure? = null) :
    RuntimeException(message, failure)
