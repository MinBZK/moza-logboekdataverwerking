package nl.mijnoverheidzakelijk.ldv.repository

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.api.trace.StatusCode
import nl.mijnoverheidzakelijk.ldv.exporter.SpanRow
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Statement

internal class PostgresRepositoryTest {

    private lateinit var mockConnection: Connection
    private lateinit var mockStatement: Statement
    private lateinit var mockPreparedStatement: PreparedStatement

    @BeforeEach
    fun setUp() {
        mockConnection = mockk(relaxed = true)
        mockStatement = mockk(relaxed = true)
        mockPreparedStatement = mockk(relaxed = true)
        every { mockConnection.isValid(any()) } returns true
        every { mockConnection.createStatement() } returns mockStatement
        every { mockConnection.prepareStatement(any()) } returns mockPreparedStatement
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    private fun repository(table: String = "spans", timeout: Int = 5) =
        PostgresRepository(
            table = table,
            objectMapper = ObjectMapper(),
            connectionFactory = { mockConnection },
            connectionValidationTimeoutSeconds = timeout,
        )

    private fun spanRow(
        traceId: String = "myTraceId",
        spanId: String = "mySpanId",
        parentSpanId: String? = "myParentSpanId",
        status: StatusCode = StatusCode.OK,
        attributes: Map<String, String> = mapOf("k1" to "v1"),
        resource: Map<String, String> = mapOf("r1" to "rv1"),
    ) = SpanRow(
        traceId = traceId,
        spanId = spanId,
        status = status,
        name = "myName",
        startTime = 20L,
        endTime = 25L,
        parentSpanId = parentSpanId,
        attributes = attributes,
        resource = resource,
    )

    @Nested
    @DisplayName("construction")
    inner class ConstructionTests {

        @Test
        fun `Accepts valid table name`() {
            repository(table = "valid_table_123")
        }

        @ParameterizedTest(name = "Rejects invalid table name: \"{0}\"")
        @ValueSource(strings = ["'; DROP TABLE spans; --", "123table", "my table", ""])
        fun `Rejects invalid table names`(invalidName: String) {
            assertThrows<IllegalArgumentException> {
                repository(table = invalidName)
            }
        }

        @Test
        fun `Rejects a negative connection validation timeout`() {
            val ex = assertThrows<IllegalArgumentException> {
                repository(timeout = -1)
            }
            assert(ex.message!!.contains("connectionValidationTimeoutSeconds"))
        }

        @Test
        fun `Does not open a connection eagerly`() {
            repository()

            verify(exactly = 0) { mockConnection.isValid(any()) }
            verify(exactly = 0) { mockConnection.createStatement() }
        }
    }

    @Nested
    @DisplayName("ensureSchema")
    inner class EnsureSchemaTests {

        @Test
        fun `Creates table with correct schema`() {
            val sql = slot<String>()
            every { mockStatement.execute(capture(sql)) } returns true

            repository().ensureSchema()

            val ddl = sql.captured
            assert(ddl.contains("CREATE TABLE IF NOT EXISTS spans"))
            assert(ddl.contains("trace_id text"))
            assert(ddl.contains("span_id text"))
            assert(ddl.contains("status text"))
            assert(ddl.contains("\"name\" text"))
            assert(ddl.contains("start_time bigint"))
            assert(ddl.contains("end_time bigint"))
            assert(ddl.contains("parent_span_id text"))
            assert(ddl.contains("attributes jsonb"))
            assert(ddl.contains("resource jsonb"))
            assert(ddl.contains("PRIMARY KEY (trace_id, span_id)"))
        }

        @Test
        fun `Throws RuntimeException and invalidates connection when execute fails`() {
            every { mockStatement.execute(any()) } throws SQLException("Connection failed")

            val exception = assertThrows<RuntimeException> {
                repository().ensureSchema()
            }
            assert(exception.message == "Failed to ensure PostgreSQL schema")
            verify { mockConnection.close() }
        }
    }

    @Nested
    @DisplayName("insert")
    inner class InsertSpansTests {

        @Test
        fun `Binds span fields and commits batch in a transaction`() {
            repository().insert(listOf(spanRow()))

            verify { mockConnection.prepareStatement(match { it.contains("INSERT INTO spans") && it.contains("?::jsonb") }) }
            verify { mockConnection.autoCommit = false }
            verify { mockPreparedStatement.setString(1, "myTraceId") }
            verify { mockPreparedStatement.setString(2, "mySpanId") }
            verify { mockPreparedStatement.setString(3, "OK") }
            verify { mockPreparedStatement.setString(4, "myName") }
            verify { mockPreparedStatement.setLong(5, 20L) }
            verify { mockPreparedStatement.setLong(6, 25L) }
            verify { mockPreparedStatement.setString(7, "myParentSpanId") }
            verify { mockPreparedStatement.setString(8, """{"k1":"v1"}""") }
            verify { mockPreparedStatement.setString(9, """{"r1":"rv1"}""") }
            verify { mockPreparedStatement.addBatch() }
            verify { mockPreparedStatement.executeBatch() }
            verify { mockConnection.commit() }
            verify { mockConnection.autoCommit = true }
        }

        @Test
        fun `Binds the status enum name`() {
            repository().insert(listOf(spanRow(status = StatusCode.ERROR)))

            verify { mockPreparedStatement.setString(3, "ERROR") }
        }

        @Test
        fun `Reports serialization failure distinctly without opening a transaction`() {
            val throwingMapper: ObjectMapper = mockk()
            every { throwingMapper.writeValueAsString(any()) } throws RuntimeException("bad json")
            val repo = PostgresRepository(
                table = "spans",
                objectMapper = throwingMapper,
                connectionFactory = { mockConnection },
                connectionValidationTimeoutSeconds = 5,
            )

            val exception = assertThrows<RuntimeException> {
                repo.insert(listOf(spanRow()))
            }
            assert(exception.message == "Failed to serialize spans for PostgreSQL")
            verify(exactly = 0) { mockConnection.autoCommit = false }
            verify(exactly = 0) { mockConnection.prepareStatement(any()) }
        }

        @Test
        fun `Commits and recycles the connection when restoring autoCommit fails`() {
            // The batch is already committed; a failed autoCommit restore must NOT be
            // reported as an insert failure (that would mislabel committed spans as lost).
            every { mockConnection.autoCommit = true } throws SQLException("restore failed")

            repository().insert(listOf(spanRow()))

            verify { mockConnection.commit() }
            verify { mockConnection.close() } // connection recycled, not reported as failure
        }

        @Test
        fun `Adds one batch entry per span for a multi-row insert`() {
            repository().insert(listOf(spanRow(spanId = "a"), spanRow(spanId = "b")))

            verify(exactly = 2) { mockPreparedStatement.addBatch() }
            verify(exactly = 1) { mockPreparedStatement.executeBatch() }
            verify(exactly = 1) { mockConnection.commit() }
        }

        @Test
        fun `Binds null for a root span without parent`() {
            repository().insert(listOf(spanRow(parentSpanId = null)))

            verify { mockPreparedStatement.setString(7, null) }
        }

        @Test
        fun `Serializes attributes with JSON-significant characters safely`() {
            val tricky = mapOf("key" to """v"with\quote""", "ünïcödé" to "🚀")
            repository().insert(listOf(spanRow(attributes = tricky)))

            val expected = ObjectMapper().writeValueAsString(tricky)
            verify { mockPreparedStatement.setString(8, expected) }
        }

        @Test
        fun `Does nothing for empty list`() {
            repository().insert(emptyList())

            verify(exactly = 0) { mockConnection.prepareStatement(any()) }
        }

        @Test
        fun `Rolls back, drops the connection and throws when executeBatch fails`() {
            every { mockPreparedStatement.executeBatch() } throws SQLException("Insert failed")

            val exception = assertThrows<RuntimeException> {
                repository().insert(listOf(spanRow()))
            }
            assert(exception.message == "Failed to insert into PostgreSQL")
            verify { mockConnection.rollback() }
            verify { mockConnection.close() }
        }

        @Test
        fun `Rolls back and throws when commit fails`() {
            every { mockConnection.commit() } throws SQLException("Commit failed")

            val exception = assertThrows<RuntimeException> {
                repository().insert(listOf(spanRow()))
            }
            assert(exception.message == "Failed to insert into PostgreSQL")
            verify { mockConnection.rollback() }
        }

        @Test
        fun `Keeps the original failure when rollback also fails`() {
            every { mockPreparedStatement.executeBatch() } throws SQLException("Insert failed")
            every { mockConnection.rollback() } throws SQLException("Rollback failed")

            val exception = assertThrows<RuntimeException> {
                repository().insert(listOf(spanRow()))
            }
            assert(exception.message == "Failed to insert into PostgreSQL")
        }
    }

    @Nested
    @DisplayName("connection resilience")
    inner class ConnectionResilienceTests {

        @Test
        fun `Reconnects when the current connection is no longer valid`() {
            val stale: Connection = mockk(relaxed = true)
            every { stale.isValid(any()) } returns false
            val fresh: Connection = mockk(relaxed = true)
            every { fresh.isValid(any()) } returns true
            every { fresh.createStatement() } returns mockStatement
            every { mockStatement.execute(any()) } returns true

            val connections = ArrayDeque(listOf(stale, fresh))
            val repo = PostgresRepository(
                table = "spans",
                objectMapper = ObjectMapper(),
                connectionFactory = { connections.removeFirst() },
                connectionValidationTimeoutSeconds = 5,
            )

            repo.ensureSchema() // opens `stale` (first use)
            repo.ensureSchema() // `stale` is invalid -> close it and reconnect to `fresh`

            verify { stale.close() }
            verify { fresh.createStatement() }
        }

        @Test
        fun `Reconnects via insert when the current connection is stale`() {
            val stale: Connection = mockk(relaxed = true)
            every { stale.isValid(any()) } returns false
            every { stale.prepareStatement(any()) } returns mockk(relaxed = true)
            val fresh: Connection = mockk(relaxed = true)
            every { fresh.isValid(any()) } returns true
            val freshStatement: PreparedStatement = mockk(relaxed = true)
            every { fresh.prepareStatement(any()) } returns freshStatement

            val connections = ArrayDeque(listOf(stale, fresh))
            val repo = PostgresRepository(
                table = "spans",
                objectMapper = ObjectMapper(),
                connectionFactory = { connections.removeFirst() },
                connectionValidationTimeoutSeconds = 5,
            )

            repo.insert(listOf(spanRow())) // opens `stale` (first use)
            repo.insert(listOf(spanRow())) // `stale` is invalid -> reconnect to `fresh`

            verify { stale.close() }
            verify { freshStatement.executeBatch() }
            verify { fresh.commit() }
        }

        @Test
        fun `Recovers on the next insert after an insert failure invalidated the connection`() {
            val broken: Connection = mockk(relaxed = true)
            every { broken.isValid(any()) } returns true
            val brokenStatement: PreparedStatement = mockk(relaxed = true)
            every { broken.prepareStatement(any()) } returns brokenStatement
            every { brokenStatement.executeBatch() } throws SQLException("Insert failed")
            val fresh: Connection = mockk(relaxed = true)
            every { fresh.isValid(any()) } returns true
            val freshStatement: PreparedStatement = mockk(relaxed = true)
            every { fresh.prepareStatement(any()) } returns freshStatement

            val connections = ArrayDeque(listOf(broken, fresh))
            val repo = PostgresRepository(
                table = "spans",
                objectMapper = ObjectMapper(),
                connectionFactory = { connections.removeFirst() },
                connectionValidationTimeoutSeconds = 5,
            )

            // First insert fails: rolls back, closes and drops the broken connection.
            assertThrows<RuntimeException> { repo.insert(listOf(spanRow())) }
            verify { broken.close() }

            // The exporter must not stay wedged: the next insert reconnects and commits.
            repo.insert(listOf(spanRow()))
            verify { freshStatement.executeBatch() }
            verify { fresh.commit() }
        }

        @Test
        fun `Wraps a reconnect failure with a clear message`() {
            var calls = 0
            val repo = PostgresRepository(
                table = "spans",
                objectMapper = ObjectMapper(),
                connectionFactory = {
                    calls++
                    throw SQLException("DB down")
                },
                connectionValidationTimeoutSeconds = 5,
            )

            val exception = assertThrows<RuntimeException> {
                repo.ensureSchema()
            }
            assert(exception.message == "Failed to (re)establish PostgreSQL connection")
            assert(calls == 1)
        }
    }

    @Nested
    @DisplayName("close")
    inner class CloseTests {

        @Test
        fun `Closes the connection`() {
            val repo = repository()
            repo.ensureSchema() // opens the connection

            repo.close()

            verify { mockConnection.close() }
        }

        @Test
        fun `Swallows a close failure so shutdown stays non-throwing`() {
            every { mockConnection.close() } throws SQLException("close failed")
            val repo = repository()
            repo.ensureSchema() // opens the connection

            repo.close() // must not propagate the close failure

            verify { mockConnection.close() }
        }
    }
}
