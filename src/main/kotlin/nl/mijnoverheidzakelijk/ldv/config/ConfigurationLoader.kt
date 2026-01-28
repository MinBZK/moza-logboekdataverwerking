package nl.mijnoverheidzakelijk.ldv.config

import org.apache.commons.configuration2.ex.ConfigurationException
import org.eclipse.microprofile.config.Config
import org.eclipse.microprofile.config.ConfigProvider

/**
 * Utility class providing access to application configuration via MicroProfile Config.
 *
 *
 * This class offers a shared [Config] instance as well as a helper method to
 * resolve typed configuration values by key.
 */
object ConfigurationLoader {
    /**
     * Returns the shared MicroProfile [Config] instance.
     *
     * @return the configuration instance
     * @throws ConfigurationException if configuration cannot be accessed
     */
    @get:Throws(ConfigurationException::class)
    @get:Synchronized
    internal var configProvider: () -> Config = {
        ConfigProvider.getConfig()
    }

    /**
     * Resolves a configuration value by key and converts it to the given type.
     *
     * @param key    the configuration key
     * @param tClass the expected type
     * @param <T>    the generic type of the returned value
     * @return the resolved value
     * @throws ConfigurationException if the value cannot be loaded or converted
    </T> */
    @Throws(ConfigurationException::class)
    fun <T> getValueByKey(key: String, tClass: Class<T>): T {
        return configProvider().getValue<T>(key, tClass)
    }
}
