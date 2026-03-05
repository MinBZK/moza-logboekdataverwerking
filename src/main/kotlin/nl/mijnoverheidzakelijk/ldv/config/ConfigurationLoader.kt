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
     * Resolves a configuration value by key and converts it to the given type.
     * Use the typed property accessors above instead of calling this directly.
     */
    @Throws(ConfigurationException::class)
    private fun <T> getValue(key: String, tClass: Class<T>): T {
        return configProvider().getValue(key, tClass)
    }
}
