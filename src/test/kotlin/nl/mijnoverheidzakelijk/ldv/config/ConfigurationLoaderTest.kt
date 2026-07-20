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
        fun `defaults to SIMPLE when key absent`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.span-processor", String::class.java)
            } returns Optional.empty()

            assert(ConfigurationLoader.spanProcessor == ConfigurationLoader.SpanProcessorMode.SIMPLE)
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
    @DisplayName("Write failure policy")
    inner class WriteFailurePolicyTests {

        @Test
        fun `defaults to FAIL_CLOSED when key absent`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.write-failure-policy", String::class.java)
            } returns Optional.empty()

            assert(ConfigurationLoader.writeFailurePolicy == ConfigurationLoader.WriteFailurePolicy.FAIL_CLOSED)
        }

        @Test
        fun `parses fail-open case-insensitively`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.write-failure-policy", String::class.java)
            } returns Optional.of("FAIL-OPEN")

            assert(ConfigurationLoader.writeFailurePolicy == ConfigurationLoader.WriteFailurePolicy.FAIL_OPEN)
        }

        @Test
        fun `parses fail-closed explicitly`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.write-failure-policy", String::class.java)
            } returns Optional.of("fail-closed")

            assert(ConfigurationLoader.writeFailurePolicy == ConfigurationLoader.WriteFailurePolicy.FAIL_CLOSED)
        }

        @Test
        fun `throws on unrecognised value`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.write-failure-policy", String::class.java)
            } returns Optional.of("retry-forever")

            assertThrows<IllegalArgumentException> { ConfigurationLoader.writeFailurePolicy }
        }
    }

    @Nested
    @DisplayName("Log exception stacktrace flag")
    inner class LogExceptionStacktraceTests {

        @Test
        fun `defaults to false when key absent`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.log-exception-stacktrace", String::class.java)
            } returns Optional.empty()

            assert(!ConfigurationLoader.logExceptionStacktrace)
        }

        @Test
        fun `is true when set to true`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.log-exception-stacktrace", String::class.java)
            } returns Optional.of("true")

            assert(ConfigurationLoader.logExceptionStacktrace)
        }

        @Test
        fun `defaults to false on non-boolean value`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.log-exception-stacktrace", String::class.java)
            } returns Optional.of("yes")

            assert(!ConfigurationLoader.logExceptionStacktrace)
        }
    }

    @Nested
    @DisplayName("Dbms selection")
    inner class DbmsTests {

        @Test
        fun `defaults to CLICKHOUSE when key absent`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.dbms", String::class.java)
            } returns Optional.empty()

            assert(ConfigurationLoader.dbms == ConfigurationLoader.Dbms.CLICKHOUSE)
        }

        @Test
        fun `parses clickhouse case-insensitively`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.dbms", String::class.java)
            } returns Optional.of("ClickHouse")

            assert(ConfigurationLoader.dbms == ConfigurationLoader.Dbms.CLICKHOUSE)
        }

        @Test
        fun `parses postgresql`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.dbms", String::class.java)
            } returns Optional.of("postgresql")

            assert(ConfigurationLoader.dbms == ConfigurationLoader.Dbms.POSTGRESQL)
        }

        @Test
        fun `parses postgres alias`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.dbms", String::class.java)
            } returns Optional.of("postgres")

            assert(ConfigurationLoader.dbms == ConfigurationLoader.Dbms.POSTGRESQL)
        }

        @Test
        fun `throws on unrecognised value`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.dbms", String::class.java)
            } returns Optional.of("mysql")

            assertThrows<IllegalArgumentException> { ConfigurationLoader.dbms }
        }
    }

    @Nested
    @DisplayName("postgresqlConnectionValidationTimeoutSeconds")
    inner class PostgresqlConnectionValidationTimeoutTests {

        @Test
        fun `defaults when key absent`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.postgresql.connection-validation-timeout-seconds", String::class.java)
            } returns Optional.empty()

            assert(
                ConfigurationLoader.postgresqlConnectionValidationTimeoutSeconds ==
                    ConfigurationLoader.DEFAULT_POSTGRESQL_CONNECTION_VALIDATION_TIMEOUT_SECONDS
            )
        }

        @Test
        fun `defaults when value blank`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.postgresql.connection-validation-timeout-seconds", String::class.java)
            } returns Optional.of("  ")

            assert(
                ConfigurationLoader.postgresqlConnectionValidationTimeoutSeconds ==
                    ConfigurationLoader.DEFAULT_POSTGRESQL_CONNECTION_VALIDATION_TIMEOUT_SECONDS
            )
        }

        @Test
        fun `returns configured value`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.postgresql.connection-validation-timeout-seconds", String::class.java)
            } returns Optional.of("12")

            assert(ConfigurationLoader.postgresqlConnectionValidationTimeoutSeconds == 12)
        }

        @Test
        fun `throws a contextual error on non-numeric value`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.postgresql.connection-validation-timeout-seconds", String::class.java)
            } returns Optional.of("soon")

            val ex = assertThrows<IllegalArgumentException> {
                ConfigurationLoader.postgresqlConnectionValidationTimeoutSeconds
            }
            assert(ex.message!!.contains("connection-validation-timeout-seconds"))
        }
    }

    @Nested
    @DisplayName("clickhouseQueryTimeoutSeconds")
    inner class ClickhouseQueryTimeoutTests {

        @Test
        fun `defaults when key absent`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.clickhouse.query-timeout-seconds", String::class.java)
            } returns Optional.empty()

            assert(
                ConfigurationLoader.clickhouseQueryTimeoutSeconds ==
                    ConfigurationLoader.DEFAULT_CLICKHOUSE_QUERY_TIMEOUT_SECONDS
            )
        }

        @Test
        fun `defaults when value blank`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.clickhouse.query-timeout-seconds", String::class.java)
            } returns Optional.of("  ")

            assert(
                ConfigurationLoader.clickhouseQueryTimeoutSeconds ==
                    ConfigurationLoader.DEFAULT_CLICKHOUSE_QUERY_TIMEOUT_SECONDS
            )
        }

        @Test
        fun `returns configured value`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.clickhouse.query-timeout-seconds", String::class.java)
            } returns Optional.of("45")

            assert(ConfigurationLoader.clickhouseQueryTimeoutSeconds == 45)
        }

        @Test
        fun `throws a contextual error on non-numeric value`() {
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.clickhouse.query-timeout-seconds", String::class.java)
            } returns Optional.of("soon")

            val ex = assertThrows<IllegalArgumentException> {
                ConfigurationLoader.clickhouseQueryTimeoutSeconds
            }
            assert(ex.message!!.contains("query-timeout-seconds"))
        }
    }

    @Nested
    @DisplayName("validatePostgresqlConfig")
    inner class ValidatePostgresqlConfigTests {

        private fun stubPostgresql(
            url: String? = "jdbc:postgresql://localhost:5432/ldv_logging",
            username: String? = "user",
            password: String? = "pwd",
            table: String? = "spans",
            timeout: String? = null,
        ) {
            mapOf(
                "logboekdataverwerking.postgresql.url" to url,
                "logboekdataverwerking.postgresql.username" to username,
                "logboekdataverwerking.postgresql.password" to password,
                "logboekdataverwerking.postgresql.table" to table,
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
            every {
                mockConfig.getOptionalValue("logboekdataverwerking.postgresql.connection-validation-timeout-seconds", String::class.java)
            } returns Optional.ofNullable(timeout)
        }

        @Test
        fun `passes when all keys are set and non-blank`() {
            stubPostgresql()
            ConfigurationLoader.validatePostgresqlConfig()
        }

        @Test
        fun `throws when a key is missing`() {
            stubPostgresql(url = null)

            val ex = assertThrows<IllegalStateException> {
                ConfigurationLoader.validatePostgresqlConfig()
            }
            assert(ex.message!!.contains("logboekdataverwerking.postgresql.url"))
        }

        @Test
        fun `throws when a key is blank`() {
            stubPostgresql(table = "   ")

            val ex = assertThrows<IllegalStateException> {
                ConfigurationLoader.validatePostgresqlConfig()
            }
            assert(ex.message!!.contains("logboekdataverwerking.postgresql.table"))
        }

        @Test
        fun `throws when the connection-validation timeout is not an integer`() {
            stubPostgresql(timeout = "soon")

            val ex = assertThrows<IllegalArgumentException> {
                ConfigurationLoader.validatePostgresqlConfig()
            }
            assert(ex.message!!.contains("connection-validation-timeout-seconds"))
        }

        @Test
        fun `throws when the connection-validation timeout is negative`() {
            stubPostgresql(timeout = "-1")

            val ex = assertThrows<IllegalArgumentException> {
                ConfigurationLoader.validatePostgresqlConfig()
            }
            assert(ex.message!!.contains("connection-validation-timeout-seconds"))
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
