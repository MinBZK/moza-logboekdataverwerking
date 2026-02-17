package nl.mijnoverheidzakelijk.ldv.config

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import org.eclipse.microprofile.config.Config
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("ConfigurationLoader")
internal class ConfigurationLoaderTest {
    private lateinit var mockConfig: Config

    @BeforeEach
    fun setUp() {
        mockConfig = mockk()
        ConfigurationLoader.configProvider = { mockConfig }
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Nested
    @DisplayName("Typed property accessors")
    inner class TypedPropertyAccessorTests {

        @Test
        fun `serviceName returns configured value`() {
            every {
                mockConfig.getValue("logboekdataverwerking.service-name", String::class.java)
            } returns "my-service"

            assert(ConfigurationLoader.serviceName == "my-service")
        }

        @Test
        fun `enabled returns configured boolean`() {
            every {
                mockConfig.getValue("logboekdataverwerking.enabled", Boolean::class.java)
            } returns true

            assert(ConfigurationLoader.enabled)
        }

        @Test
        fun `clickhouseTable returns configured value`() {
            every {
                mockConfig.getValue("logboekdataverwerking.clickhouse.table", String::class.java)
            } returns "myTableName"

            assert(ConfigurationLoader.clickhouseTable == "myTableName")
        }

        @Test
        fun `clickhouseEndpoint returns configured value`() {
            every {
                mockConfig.getValue("logboekdataverwerking.clickhouse.endpoint", String::class.java)
            } returns "http://localhost:8123"

            assert(ConfigurationLoader.clickhouseEndpoint == "http://localhost:8123")
        }

        @Test
        fun `throws exception when config key is missing`() {
            every {
                mockConfig.getValue("logboekdataverwerking.service-name", String::class.java)
            } throws NoSuchElementException("Config key not found")

            assertThrows<NoSuchElementException> {
                ConfigurationLoader.serviceName
            }
        }
    }
}
