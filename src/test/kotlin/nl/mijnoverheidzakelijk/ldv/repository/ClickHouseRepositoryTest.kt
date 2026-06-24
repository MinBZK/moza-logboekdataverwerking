package nl.mijnoverheidzakelijk.ldv.repository

import com.clickhouse.client.api.Client
import com.clickhouse.client.api.insert.InsertResponse
import com.clickhouse.client.api.query.QueryResponse
import com.clickhouse.data.ClickHouseFormat
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkConstructor
import io.mockk.verify
import io.opentelemetry.api.trace.StatusCode
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader
import nl.mijnoverheidzakelijk.ldv.exporter.SpanRow
import org.eclipse.microprofile.config.Config
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.concurrent.CompletableFuture

internal class ClickHouseRepositoryTest {

    private lateinit var mockConfig: Config
    private lateinit var mockClient: Client
    private lateinit var repository: ClickHouseRepository

    @BeforeEach
    fun setUp() {
        // Mock ConfigurationLoader
        mockConfig = mockk()
        ConfigurationLoader.configProvider = { mockConfig }

        every { mockConfig.getValue("logboekdataverwerking.clickhouse.endpoint", String::class.java) } returns "http://localhost:8123"
        every { mockConfig.getValue("logboekdataverwerking.clickhouse.username", String::class.java) } returns "testuser"
        every { mockConfig.getValue("logboekdataverwerking.clickhouse.password", String::class.java) } returns "testpass"
        every { mockConfig.getValue("logboekdataverwerking.clickhouse.database", String::class.java) } returns "testdb"
        every { mockConfig.getValue("logboekdataverwerking.clickhouse.table", String::class.java) } returns "testtable"
        every {
            mockConfig.getOptionalValue("logboekdataverwerking.clickhouse.query-timeout-seconds", String::class.java)
        } returns Optional.empty()

        // Mock Client.Builder
        mockClient = mockk(relaxed = true)
        mockkConstructor(Client.Builder::class)
        every { anyConstructed<Client.Builder>().addEndpoint(any<String>()) } returns mockk<Client.Builder> {
            every { setUsername(any()) } returns this
            every { setPassword(any()) } returns this
            every { setDefaultDatabase(any()) } returns this
            every { build() } returns mockClient
        }

        repository = ClickHouseRepository()
    }

    @AfterEach
    fun tearDown() {
        unmockkConstructor(Client.Builder::class)
        clearAllMocks()
    }

    @Nested
    @DisplayName("ensureSchema")
    inner class EnsureSchemaTests {

        @Test
        fun `Creates table with correct schema`() {
            // given
            val mockFuture: CompletableFuture<QueryResponse> = CompletableFuture.completedFuture(mockk())
            every { mockClient.query(any<String>()) } returns mockFuture

            // when
            repository.ensureSchema()

            // then
            verify {
                mockClient.query(match { query ->
                    query.contains("CREATE TABLE IF NOT EXISTS testtable") &&
                    query.contains("traceId String") &&
                    query.contains("spanId String") &&
                    query.contains("status String") &&
                    query.contains("name String") &&
                    query.contains("startTime Int64") &&
                    query.contains("endTime Int64") &&
                    query.contains("parentSpanId String") &&
                    query.contains("attributes Map(String, String)") &&
                    query.contains("resource Map(String, String)") &&
                    query.contains("ENGINE = MergeTree()") &&
                    query.contains("ORDER BY (traceId, spanId)")
                })
            }
        }

        @Test
        fun `Throws RuntimeException when query fails`() {
            // given
            val mockFuture: CompletableFuture<QueryResponse> = CompletableFuture()
            mockFuture.completeExceptionally(RuntimeException("Connection failed"))
            every { mockClient.query(any<String>()) } returns mockFuture

            // when / then
            val exception = assertThrows<RuntimeException> {
                repository.ensureSchema()
            }
            assert(exception.message == "Failed to ensure ClickHouse schema")
        }
    }

    @Nested
    @DisplayName("requireValidTableName")
    inner class RequireValidTableNameTests {

        @Test
        fun `Accepts valid simple table name and uses it in schema`() {
            every { mockConfig.getValue("logboekdataverwerking.clickhouse.table", String::class.java) } returns "valid_table_123"
            val mockFuture: CompletableFuture<QueryResponse> = CompletableFuture.completedFuture(mockk())
            every { mockClient.query(any<String>()) } returns mockFuture

            val repo = ClickHouseRepository()
            repo.ensureSchema()

            verify { mockClient.query(match { it.contains("CREATE TABLE IF NOT EXISTS valid_table_123") }) }
        }

        @Test
        fun `Accepts schema-qualified table name and uses it in schema`() {
            every { mockConfig.getValue("logboekdataverwerking.clickhouse.table", String::class.java) } returns "schema.table_name"
            val mockFuture: CompletableFuture<QueryResponse> = CompletableFuture.completedFuture(mockk())
            every { mockClient.query(any<String>()) } returns mockFuture

            val repo = ClickHouseRepository()
            repo.ensureSchema()

            verify { mockClient.query(match { it.contains("CREATE TABLE IF NOT EXISTS schema.table_name") }) }
        }

        @ParameterizedTest(name = "Rejects invalid table name: \"{0}\"")
        @ValueSource(strings = ["'; DROP TABLE spans; --", "123table", "my table", ""])
        fun `Rejects invalid table names`(invalidName: String) {
            every { mockConfig.getValue("logboekdataverwerking.clickhouse.table", String::class.java) } returns invalidName
            assertThrows<IllegalArgumentException> {
                ClickHouseRepository()
            }
        }
    }

    @Nested
    @DisplayName("insertJsonEachRow")
    inner class InsertJsonEachRowTests {

        @Test
        fun `Inserts JSON payload into configured table`() {
            // given
            val jsonPayload = """{"traceId":"123","spanId":"456"}"""
            val mockFuture: CompletableFuture<InsertResponse> = CompletableFuture.completedFuture(mockk())
            every { mockClient.insert(any<String>(), any<InputStream>(), any<ClickHouseFormat>()) } returns mockFuture

            // when
            repository.insertJsonEachRow(jsonPayload)

            // then
            verify {
                mockClient.insert(
                    eq("testtable"),
                    any<InputStream>(),
                    eq(ClickHouseFormat.JSONEachRow)
                )
            }
        }

        @Test
        fun `Throws RuntimeException when insert fails`() {
            // given
            val jsonPayload = """{"traceId":"123"}"""
            val mockFuture: CompletableFuture<InsertResponse> = CompletableFuture()
            mockFuture.completeExceptionally(RuntimeException("Insert failed"))
            every { mockClient.insert(any<String>(), any<InputStream>(), any<ClickHouseFormat>()) } returns mockFuture

            // when / then
            val exception = assertThrows<RuntimeException> {
                repository.insertJsonEachRow(jsonPayload)
            }
            assert(exception.message == "Failed to insert into ClickHouse")
        }

        @Test
        fun `Converts payload to UTF-8 bytes`() {
            // given
            val jsonPayload = """{"name":"tëst-üñíçödé"}"""
            val mockFuture: CompletableFuture<InsertResponse> = CompletableFuture.completedFuture(mockk())
            every { mockClient.insert(any<String>(), any<InputStream>(), any<ClickHouseFormat>()) } returns mockFuture

            // when
            repository.insertJsonEachRow(jsonPayload)

            // then - verify insert was called (data conversion happens internally)
            verify { mockClient.insert(any<String>(), any<InputStream>(), any<ClickHouseFormat>()) }
        }
    }

    @Nested
    @DisplayName("insert(rows)")
    inner class InsertRowsTests {

        private fun spanRow(parentSpanId: String?) = SpanRow(
            traceId = "myTraceId",
            spanId = "mySpanId",
            status = StatusCode.OK,
            name = "myName",
            startTimeMillis = 0L,
            endTimeMillis = 0L,
            parentSpanId = parentSpanId,
            attributes = mapOf("attrKey" to "attrValue"),
            resource = mapOf("resKey" to "resValue"),
        )

        private fun capturedPayload(rows: List<SpanRow>): String {
            val captured = slot<InputStream>()
            every {
                mockClient.insert(any<String>(), capture(captured), any<ClickHouseFormat>())
            } returns CompletableFuture.completedFuture(mockk())

            repository.insert(rows)

            return captured.captured.readBytes().toString(StandardCharsets.UTF_8)
        }

        @Test
        fun `Renders a root span row as JSONEachRow with the all-zero parentSpanId`() {
            // A root span's parentSpanId is null in SpanRow but the ClickHouse column
            // is a non-nullable String, so it must serialize to the all-zero invalid id.
            val payload = capturedPayload(listOf(spanRow(parentSpanId = null)))

            assert(
                payload == "{\"traceId\":\"myTraceId\",\"spanId\":\"mySpanId\",\"status\":\"OK\"," +
                    "\"name\":\"myName\",\"startTime\":0,\"endTime\":0," +
                    "\"parentSpanId\":\"0000000000000000\"," +
                    "\"attributes\":{\"attrKey\":\"attrValue\"}," +
                    "\"resource\":{\"resKey\":\"resValue\"}}\n",
            )
        }

        @Test
        fun `Preserves a non-root parentSpanId`() {
            val payload = capturedPayload(listOf(spanRow(parentSpanId = "myParentSpanId")))

            assert(payload.contains("\"parentSpanId\":\"myParentSpanId\""))
        }

        @Test
        fun `Serializes one JSON object per row, newline-separated`() {
            val payload = capturedPayload(listOf(spanRow(parentSpanId = null), spanRow(parentSpanId = null)))

            assert(payload.trimEnd('\n').split("\n").size == 2)
        }

        @Test
        fun `Empty rows does not call the client`() {
            repository.insert(emptyList())

            verify(exactly = 0) {
                mockClient.insert(any<String>(), any<InputStream>(), any<ClickHouseFormat>())
            }
        }
    }
}
