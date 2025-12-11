package nl.mijnoverheidzakelijk.ldv.config;

import org.apache.commons.configuration2.ex.ConfigurationException;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Utility class providing access to application configuration via MicroProfile Config.
 * <p>
 * This class offers a shared {@link Config} instance as well as a helper method to
 * resolve typed configuration values by key.
 */
public class ConfigurationLoader {

    private static final Config configuration = ConfigProvider.getConfig();
    
    private ConfigurationLoader() {
    }
    
    /**
     * Returns the shared MicroProfile {@link Config} instance.
     *
     * @return the configuration instance
     * @throws ConfigurationException if configuration cannot be accessed
     */
    public static synchronized Config getConfiguration() throws ConfigurationException {
            return configuration;
    }

    /**
     * Resolves a configuration value by key and converts it to the given type.
     *
     * @param key    the configuration key
     * @param tClass the expected type
     * @param <T>    the generic type of the returned value
     * @return the resolved value
     * @throws ConfigurationException if the value cannot be loaded or converted
     */
    public static <T> T getValueByKey(String key, Class<T> tClass) throws ConfigurationException {
        return configuration.getValue(key, tClass);
    }
}
