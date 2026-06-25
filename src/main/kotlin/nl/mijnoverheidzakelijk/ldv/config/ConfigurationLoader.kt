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

    /** Allowed values for the write-failure-policy configuration property. */
    enum class WriteFailurePolicy { FAIL_CLOSED, FAIL_OPEN }

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
     * `SIMPLE` (default) exports synchronously so the application knows the logregel was
     * stored (LDV acknowledgement MUST, and the precondition for [writeFailurePolicy]).
     * `BATCH` is async and cannot enforce per-request acknowledgement. See README.
     */
    val spanProcessor: SpanProcessorMode
        get() {
            val raw = getOptionalString("logboekdataverwerking.span-processor") ?: return SpanProcessorMode.SIMPLE
            return when (raw.lowercase()) {
                "simple" -> SpanProcessorMode.SIMPLE
                "batch" -> SpanProcessorMode.BATCH
                else -> throw IllegalArgumentException(
                    "logboekdataverwerking.span-processor must be 'batch' or 'simple', got: $raw"
                )
            }
        }

    /**
     * `FAIL_CLOSED` (default) fails the verwerking when its logregel was not stored
     * (strict acknowledgement MUST); `FAIL_OPEN` logs and continues. Only enforced on
     * the synchronous [SpanProcessorMode.SIMPLE] path. See README.
     */
    val writeFailurePolicy: WriteFailurePolicy
        get() {
            val raw = getOptionalString("logboekdataverwerking.write-failure-policy")
                ?: return WriteFailurePolicy.FAIL_CLOSED
            return when (raw.lowercase()) {
                "fail-closed", "fail_closed", "failclosed" -> WriteFailurePolicy.FAIL_CLOSED
                "fail-open", "fail_open", "failopen" -> WriteFailurePolicy.FAIL_OPEN
                else -> throw IllegalArgumentException(
                    "logboekdataverwerking.write-failure-policy must be 'fail-closed' or 'fail-open', got: $raw"
                )
            }
        }

    /**
     * Default `false`: stacktraces kunnen persoonsgegevens bevatten, dus standaard
     * opslaan conflicteert met dataminimalisatie (AVG art. 5(1)(c)).
     */
    val logExceptionStacktrace: Boolean
        get() = getOptionalString("logboekdataverwerking.log-exception-stacktrace")?.toBooleanStrictOrNull() ?: false

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
