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
 *   can read it without parsing a message. The outermost link is the repository's own
 *   exception; a SQLState sits further down, so look for it along
 *   [SanitizedWriteFailure.chain]
 */
class LogboekWriteException(message: String, val failure: SanitizedWriteFailure? = null) :
    RuntimeException(message, failure)
