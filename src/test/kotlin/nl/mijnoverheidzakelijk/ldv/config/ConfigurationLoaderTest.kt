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
import java.util.Optional

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

    @Nested
    @DisplayName("Optional resource attributes")
    inner class OptionalResourceAttributeTests {

        @Test
        fun `serviceVersion returns null when key absent`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.service-version", String::class.java)
            } returns Optional.empty()

            assert(ConfigurationLoader.serviceVersion == null)
        }

        @Test
        fun `serviceVersion returns null when value blank`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.service-version", String::class.java)
            } returns Optional.of("   ")

            assert(ConfigurationLoader.serviceVersion == null)
        }

        @Test
        fun `serviceVersion returns configured value when present`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.service-version", String::class.java)
            } returns Optional.of("1.4.2")

            assert(ConfigurationLoader.serviceVersion == "1.4.2")
        }

        @Test
        fun `deploymentEnvironment returns null when absent`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.deployment-environment", String::class.java)
            } returns Optional.empty()

            assert(ConfigurationLoader.deploymentEnvironment == null)
        }

        @Test
        fun `deploymentEnvironment returns configured value when present`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.deployment-environment", String::class.java)
            } returns Optional.of("production")

            assert(ConfigurationLoader.deploymentEnvironment == "production")
        }
    }

    @Nested
    @DisplayName("Span processor mode")
    inner class SpanProcessorTests {

        @Test
        fun `defaults to BATCH when key absent`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.span-processor", String::class.java)
            } returns Optional.empty()

            assert(ConfigurationLoader.spanProcessor == ConfigurationLoader.SpanProcessorMode.BATCH)
        }

        @Test
        fun `parses simple case-insensitively`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.span-processor", String::class.java)
            } returns Optional.of("SIMPLE")

            assert(ConfigurationLoader.spanProcessor == ConfigurationLoader.SpanProcessorMode.SIMPLE)
        }

        @Test
        fun `parses batch explicitly`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.span-processor", String::class.java)
            } returns Optional.of("batch")

            assert(ConfigurationLoader.spanProcessor == ConfigurationLoader.SpanProcessorMode.BATCH)
        }

        @Test
        fun `throws on unrecognised value`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.span-processor", String::class.java)
            } returns Optional.of("kafka")

            assertThrows<IllegalArgumentException> { ConfigurationLoader.spanProcessor }
        }
    }

    @Nested
    @DisplayName("validateClickhouseConfig")
    inner class ValidateClickhouseConfigTests {

        private fun stubClickhouse(
            endpoint: String? = "http://localhost:8123",
            username: String? = "user",
            password: String? = "pwd",
            database: String? = "db",
            table: String? = "tbl",
        ) {
            mapOf(
                "logboekdataverwerking.clickhouse.endpoint" to endpoint,
                "logboekdataverwerking.clickhouse.username" to username,
                "logboekdataverwerking.clickhouse.password" to password,
                "logboekdataverwerking.clickhouse.database" to database,
                "logboekdataverwerking.clickhouse.table" to table,
            ).forEach { (key, value) ->
                if (value == null) {
                    every {
                        mockConfig.getValue(key, String::class.java)
                    } throws NoSuchElementException("missing: $key")
                } else {
                    every {
                        mockConfig.getValue(key, String::class.java)
                    } returns value
                }
            }
        }

        @Test
        fun `passes when all keys are set and non-blank`() {
            stubClickhouse()
            ConfigurationLoader.validateClickhouseConfig()
        }

        @Test
        fun `throws when a key is missing`() {
            stubClickhouse(database = null)

            val ex = assertThrows<IllegalStateException> {
                ConfigurationLoader.validateClickhouseConfig()
            }
            assert(ex.message!!.contains("logboekdataverwerking.clickhouse.database"))
        }

        @Test
        fun `throws when a key is blank`() {
            stubClickhouse(table = "   ")

            val ex = assertThrows<IllegalStateException> {
                ConfigurationLoader.validateClickhouseConfig()
            }
            assert(ex.message!!.contains("logboekdataverwerking.clickhouse.table"))
        }

        @Test
        fun `error message lists every missing key`() {
            stubClickhouse(endpoint = "", username = null)

            val ex = assertThrows<IllegalStateException> {
                ConfigurationLoader.validateClickhouseConfig()
            }
            assert(ex.message!!.contains("logboekdataverwerking.clickhouse.endpoint"))
            assert(ex.message!!.contains("logboekdataverwerking.clickhouse.username"))
        }
    }
}
