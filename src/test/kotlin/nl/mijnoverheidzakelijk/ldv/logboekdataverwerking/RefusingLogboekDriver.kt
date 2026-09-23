package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import io.quarkus.runtime.StartupEvent
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.event.Observes
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.DriverPropertyInfo
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Statement
import java.util.Properties
import java.util.logging.Logger

/**
 * Registers [RefusingLogboekDriver] from inside the Quarkus class loader. A
 * `META-INF/services/java.sql.Driver` entry does not work here: `DriverManager` only
 * hands out a driver whose class the calling class loader can see, and the test
 * class loader is not the one that loads the repository.
 *
 * Registering on startup is early enough because `ProcessingHandler` initialises
 * lazily, on the first intercepted call; its `@PostConstruct` builds the exporter,
 * which opens the connection. An eager `ProcessingHandler` would need the driver
 * registered before bean initialisation instead.
 */
@ApplicationScoped
class RefusingLogboekDriverRegistration {
    fun register(@Observes event: StartupEvent) {
        DriverManager.registerDriver(RefusingLogboekDriver())
    }
}

/**
 * JDBC driver for the Quarkus tests: connects, accepts the schema, and refuses every
 * insert with an [SQLException]. That is the "Logboek weigert de schrijfactie" case
 * without a database process.
 */
class RefusingLogboekDriver : Driver {

    companion object {
        const val URL_PREFIX: String = "jdbc:logboek-weigert:"
        const val REFUSAL: String = "Logboek weigert de schrijfactie"
    }

    override fun acceptsURL(url: String): Boolean = url.startsWith(URL_PREFIX)

    override fun connect(url: String, info: Properties?): Connection? {
        if (!acceptsURL(url)) return null
        return stub(Connection::class.java) { method, _ ->
            when (method.name) {
                "isValid" -> true
                "createStatement" -> stub(Statement::class.java) { m, _ -> if (m.name == "execute") true else default(m) }
                "prepareStatement" -> stub(PreparedStatement::class.java) { m, _ ->
                    if (m.name == "executeBatch") throw SQLException(REFUSAL) else default(m)
                }
                else -> default(method)
            }
        }
    }

    override fun getMajorVersion(): Int = 1

    override fun getMinorVersion(): Int = 0

    override fun getPropertyInfo(url: String?, info: Properties?): Array<DriverPropertyInfo> = emptyArray()

    override fun jdbcCompliant(): Boolean = false

    override fun getParentLogger(): Logger = Logger.getLogger(RefusingLogboekDriver::class.java.name)

    private fun <T> stub(type: Class<T>, handler: (Method, Array<Any?>?) -> Any?): T =
        type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.get(0)
                "toString" -> "${type.simpleName}(${URL_PREFIX})"
                else -> handler(method, args)
            }
        })

    /** Zero value for a primitive return type, null otherwise, so setters and closers just pass. */
    private fun default(method: Method): Any? = when (method.returnType) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        else -> null
    }
}
