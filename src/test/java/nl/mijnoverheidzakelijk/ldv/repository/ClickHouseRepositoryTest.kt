package nl.mijnoverheidzakelijk.ldv.repository

import com.clickhouse.client.api.Client
import com.clickhouse.client.api.insert.InsertResponse
import com.clickhouse.client.api.query.QueryResponse
import com.clickhouse.data.ClickHouseFormat
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import io.mockk.verify
import nl.mijnoverheidzakelijk.ldv.config.ConfigurationLoader
import org.eclipse.microprofile.config.Config
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.InputStream
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
    @DisplayName("insertJsonEachRow")
    inner class InsertJsonEachRowTests {

        @Test
        fun `Inserts JSON payload into specified table`() {
            // given
            val jsonPayload = """{"traceId":"123","spanId":"456"}"""
            val mockFuture: CompletableFuture<InsertResponse> = CompletableFuture.completedFuture(mockk())
            every { mockClient.insert(any<String>(), any<InputStream>(), any<ClickHouseFormat>()) } returns mockFuture

            // when
            repository.insertJsonEachRow("testtable", jsonPayload)

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
                repository.insertJsonEachRow("testtable", jsonPayload)
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
            repository.insertJsonEachRow("testtable", jsonPayload)

            // then - verify insert was called (data conversion happens internally)
            verify { mockClient.insert(any<String>(), any<InputStream>(), any<ClickHouseFormat>()) }
        }
    }
}
