package nl.mijnoverheidzakelijk.ldv.config

import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import org.eclipse.microprofile.config.Config
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

internal class ConfigurationLoaderRefactoredTest {
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

    @Test
    fun `returns value from config`() {
        every {
            mockConfig.getValue("logboekdataverwerking.clickhouse.table", String::class.java)
        } returns "myTableName"

        val result =
            ConfigurationLoader.getValueByKey("logboekdataverwerking.clickhouse.table", String::class.java)

        assert("myTableName" == result)
    }

    @Test
    fun `throws exception when key missing`() {
        every {
            mockConfig.getValue("missing", String::class.java)
        } throws NoSuchElementException()

        assertThrows<NoSuchElementException> {
            ConfigurationLoader.getValueByKey("missing", String::class.java)
        }
    }
}
