package nl.mijnoverheidzakelijk.ldv.repository

import nl.mijnoverheidzakelijk.ldv.exporter.SpanRow

/**
 * Backend-neutral storage seam for LDV spans, implemented once per database
 * backend (e.g. [ClickHouseRepository], [PostgresRepository]).
 *
 * The single exporter [nl.mijnoverheidzakelijk.ldv.exporter.LdvSpanExporter]
 * depends only on this interface and on the shared
 * [nl.mijnoverheidzakelijk.ldv.exporter.SpanMapper], so the span→[SpanRow]
 * mapping lives in exactly one place and adding a new backend means writing one
 * implementation of this interface — not a second exporter with its own mapping.
 */
interface SpanRepository {

    /**
     * Ensures the target table exists with the expected schema.
     *
     * @throws RuntimeException if the DDL operation fails
     */
    fun ensureSchema()

    /**
     * Inserts the given rows. Implementations decide their own atomicity and
     * wire format (JSON payload, JDBC batch, …); the caller treats a thrown
     * exception as a failed export of the whole batch.
     *
     * @param rows rows produced by [nl.mijnoverheidzakelijk.ldv.exporter.SpanMapper]
     * @throws RuntimeException if the insert fails
     */
    fun insert(rows: List<SpanRow>)

    /** Releases any underlying resources (connection, client). */
    fun close()
}
