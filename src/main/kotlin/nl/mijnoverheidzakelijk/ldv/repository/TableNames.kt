package nl.mijnoverheidzakelijk.ldv.repository

/**
 * Shared validation for configured table names, used by every [SpanRepository]
 * implementation so the SQL-injection guard cannot drift between backends.
 *
 * Table names come from configuration and are interpolated into DDL/insert
 * statements (they cannot be passed as bind parameters), so they are restricted
 * to a conservative identifier pattern that also allows a schema-qualified
 * `schema.table` form. All row *data* is still bound via parameters / serialized
 * JSON, never interpolated.
 */
internal object TableNames {

    private val TABLE_NAME_PATTERN = Regex("^[a-zA-Z_][a-zA-Z0-9_.]*$")

    /** @throws IllegalArgumentException if [table] is not a valid identifier. */
    fun requireValid(table: String) {
        require(TABLE_NAME_PATTERN.matches(table)) {
            "Invalid table name: $table"
        }
    }
}
