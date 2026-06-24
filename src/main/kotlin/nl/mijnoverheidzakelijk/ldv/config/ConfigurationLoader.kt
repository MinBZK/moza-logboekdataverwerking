package nl.mijnoverheidzakelijk.ldv.config

import org.apache.commons.configuration2.ex.ConfigurationException
import org.eclipse.microprofile.config.Config
import org.eclipse.microprofile.config.ConfigProvider

/**
 * Utility class providing access to application configuration via MicroProfile Config.
 *
 * This class offers typed property accessors for all configuration values,
 * encapsulating property keys and types in one place.
 */
object ConfigurationLoader {
    /** Allowed values for the span-processor configuration property. */
    enum class SpanProcessorMode { BATCH, SIMPLE }

    /** Supported database backends for span storage. */
    enum class Dbms { CLICKHOUSE, POSTGRESQL }

    /** Default for [postgresqlConnectionValidationTimeoutSeconds] when unset. */
    const val DEFAULT_POSTGRESQL_CONNECTION_VALIDATION_TIMEOUT_SECONDS: Int = 5

    /**
     * Provider for the MicroProfile [Config] instance.
     * Can be replaced in tests to provide mock configuration.
     */
    @get:Throws(ConfigurationException::class)
    @get:Synchronized
    internal var configProvider: () -> Config = {
        ConfigProvider.getConfig()
    }

    /** The service name used for OpenTelemetry spans. */
    val serviceName: String
        get() = getValue("logboekdataverwerking.service-name", String::class.java)

    /** Whether the logboek data processing is enabled. */
    val enabled: Boolean
        get() = getValue("logboekdataverwerking.enabled", Boolean::class.java)

    /**
     * Optional service version, exposed as the OpenTelemetry resource attribute
     * `service.version`. When unset or blank, the attribute is not emitted.
     */
    val serviceVersion: String?
        get() = getOptionalString("logboekdataverwerking.service-version")

    /**
     * Optional deployment environment (e.g. "prod", "staging"), exposed as the
     * OpenTelemetry resource attribute `deployment.environment`. When unset or
     * blank, the attribute is not emitted.
     */
    val deploymentEnvironment: String?
        get() = getOptionalString("logboekdataverwerking.deployment-environment")

    /**
     * Span processor selection. `BATCH` (default) is asynchronous and high-throughput;
     * `SIMPLE` is synchronous and waits for the exporter to acknowledge each span,
     * matching the LDV spec's MUST that the application knows the log record was
     * stored. See README for the trade-off.
     */
    val spanProcessor: SpanProcessorMode
        get() {
            val raw = getOptionalString("logboekdataverwerking.span-processor") ?: return SpanProcessorMode.BATCH
            return when (raw.lowercase()) {
                "simple" -> SpanProcessorMode.SIMPLE
                "batch" -> SpanProcessorMode.BATCH
                else -> throw IllegalArgumentException(
                    "logboekdataverwerking.span-processor must be 'batch' or 'simple', got: $raw"
                )
            }
        }

    /**
     * Selects which database backend stores the spans. `clickhouse` (default) is
     * the production backend; `postgresql` is a lighter-weight alternative meant
     * for development. ClickHouse is a fairly heavy tool to run locally, so this
     * lets developers use PostgreSQL instead.
     */
    val dbms: Dbms
        get() {
            val raw = getOptionalString("logboekdataverwerking.dbms") ?: return Dbms.CLICKHOUSE
            return when (raw.lowercase()) {
                "clickhouse" -> Dbms.CLICKHOUSE
                "postgresql", "postgres" -> Dbms.POSTGRESQL
                else -> throw IllegalArgumentException(
                    "logboekdataverwerking.dbms must be 'clickhouse' or 'postgresql', got: $raw"
                )
            }
        }

    /** The ClickHouse server endpoint URL. */
    val clickhouseEndpoint: String
        get() = getValue("logboekdataverwerking.clickhouse.endpoint", String::class.java)

    /** The ClickHouse username for authentication. */
    val clickhouseUsername: String
        get() = getValue("logboekdataverwerking.clickhouse.username", String::class.java)

    /** The ClickHouse password for authentication. */
    val clickhousePassword: String
        get() = getValue("logboekdataverwerking.clickhouse.password", String::class.java)

    /** The ClickHouse database name. */
    val clickhouseDatabase: String
        get() = getValue("logboekdataverwerking.clickhouse.database", String::class.java)

    /** The ClickHouse table name for storing spans. */
    val clickhouseTable: String
        get() = getValue("logboekdataverwerking.clickhouse.table", String::class.java)

    /** The PostgreSQL JDBC URL (e.g. `jdbc:postgresql://localhost:5432/ldv_logging`). */
    val postgresqlUrl: String
        get() = getValue("logboekdataverwerking.postgresql.url", String::class.java)

    /** The PostgreSQL username for authentication. */
    val postgresqlUsername: String
        get() = getValue("logboekdataverwerking.postgresql.username", String::class.java)

    /** The PostgreSQL password for authentication. */
    val postgresqlPassword: String
        get() = getValue("logboekdataverwerking.postgresql.password", String::class.java)

    /** The PostgreSQL table name for storing spans. */
    val postgresqlTable: String
        get() = getValue("logboekdataverwerking.postgresql.table", String::class.java)

    /**
     * Timeout in seconds for PostgreSQL connection liveness checks
     * ([java.sql.Connection.isValid]). Optional; defaults to
     * [DEFAULT_POSTGRESQL_CONNECTION_VALIDATION_TIMEOUT_SECONDS] when unset.
     */
    val postgresqlConnectionValidationTimeoutSeconds: Int
        get() {
            val key = "logboekdataverwerking.postgresql.connection-validation-timeout-seconds"
            val raw = getOptionalString(key) ?: return DEFAULT_POSTGRESQL_CONNECTION_VALIDATION_TIMEOUT_SECONDS
            return raw.toIntOrNull()
                ?: throw IllegalArgumentException("$key must be an integer, got: $raw")
        }

    /**
     * Validates that all required ClickHouse properties are present and non-blank.
     * Intended to be called at startup when [enabled] is true, so misconfiguration
     * surfaces immediately instead of failing on the first export.
     *
     * @throws IllegalStateException with a message listing missing or blank keys.
     */
    fun validateClickhouseConfig() {
        val keys = listOf(
            "logboekdataverwerking.clickhouse.endpoint",
            "logboekdataverwerking.clickhouse.username",
            "logboekdataverwerking.clickhouse.password",
            "logboekdataverwerking.clickhouse.database",
            "logboekdataverwerking.clickhouse.table",
        )
        val missing = keys.filter { key ->
            val value = try {
                configProvider().getValue(key, String::class.java)
            } catch (_: NoSuchElementException) {
                null
            }
            value.isNullOrBlank()
        }
        check(missing.isEmpty()) {
            "logboekdataverwerking.enabled=true but the following required config keys are missing or blank: $missing"
        }
    }

    /**
     * Validates that all required PostgreSQL properties are present and non-blank.
     * Intended to be called at startup when [enabled] is true and [dbms] is
     * `POSTGRESQL`, so misconfiguration surfaces immediately instead of failing
     * on the first export. The optional connection-validation timeout, if set, is
     * also resolved here so a non-integer or negative value fails loud at startup
     * rather than at exporter construction.
     *
     * @throws IllegalStateException with a message listing missing or blank keys.
     * @throws IllegalArgumentException if the connection-validation timeout is not a
     *         non-negative integer.
     */
    fun validatePostgresqlConfig() {
        val keys = listOf(
            "logboekdataverwerking.postgresql.url",
            "logboekdataverwerking.postgresql.username",
            "logboekdataverwerking.postgresql.password",
            "logboekdataverwerking.postgresql.table",
        )
        val missing = keys.filter { key ->
            val value = try {
                configProvider().getValue(key, String::class.java)
            } catch (_: NoSuchElementException) {
                null
            }
            value.isNullOrBlank()
        }
        check(missing.isEmpty()) {
            "logboekdataverwerking.enabled=true but the following required config keys are missing or blank: $missing"
        }
        // Resolve (and thereby validate) the optional timeout: throws on a non-integer.
        require(postgresqlConnectionValidationTimeoutSeconds >= 0) {
            "logboekdataverwerking.postgresql.connection-validation-timeout-seconds must be >= 0, " +
                "was $postgresqlConnectionValidationTimeoutSeconds"
        }
    }

    /**
     * Resolves a configuration value by key and converts it to the given type.
     * Use the typed property accessors above instead of calling this directly.
     */
    @Throws(ConfigurationException::class)
    private fun <T> getValue(key: String, tClass: Class<T>): T {
        return configProvider().getValue(key, tClass)
    }

    /**
     * Resolves an optional String configuration value. Returns null when the key
     * is absent or blank, instead of throwing.
     */
    private fun getOptionalString(key: String): String? {
        val optional = configProvider().getOptionalValue(key, String::class.java)
        if (!optional.isPresent) return null
        val value = optional.get()
        return if (value.isBlank()) null else value
    }
}
