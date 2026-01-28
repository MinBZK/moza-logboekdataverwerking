package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import io.opentelemetry.api.trace.StatusCode
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit‑tests voor [LogboekContext].
 *
 * Omdat de klasse enkel data‑velden bevat, focussen we op:
 *   • De standaardwaarde van elk veld bij een nieuwe instantie.
 *   • Het correct kunnen lezen en schrijven van de velden.
 *
 * Er is geen echte “business‑logica” om te mocken, maar we laten zien
 * hoe je een mock van `StatusCode` zou kunnen gebruiken als je dat ooit nodig hebt.
 */
@Disabled
internal class LogboekContextTest {

    private lateinit var context: LogboekContext

    @BeforeEach
    fun setUp() {
        // Een verse instantie voor elke test
        context = LogboekContext()
    }

    @Test
    @DisplayName("Standaardwaarden bij nieuw object")
    fun `default values are correct`() {
        assertNull(context.processingActivityId, "processingActivityId moet null zijn")
        assertNull(context.dataSubjectId, "dataSubjectId moet null zijn")
        assertNull(context.dataSubjectType, "dataSubjectType moet null zijn")
        assertEquals(StatusCode.UNSET, context.status, "status moet UNSET zijn")
    }

    @Nested
    @DisplayName("Setters / getters")
    inner class PropertyManipulation {

        @Test
        fun `kan processingActivityId zetten en ophalen`() {
            val expected = "activity-123"
            context.processingActivityId = expected
            assertEquals(expected, context.processingActivityId)
        }

        @Test
        fun `kan dataSubjectId zetten en ophalen`() {
            val expected = "subject-456"
            context.dataSubjectId = expected
            assertEquals(expected, context.dataSubjectId)
        }

        @Test
        fun `kan dataSubjectType zetten en ophalen`() {
            val expected = "PERSON"
            context.dataSubjectType = expected
            assertEquals(expected, context.dataSubjectType)
        }

        @Test
        fun `kan status zetten en ophalen`() {
            // Hier gebruiken we een mock van StatusCode om te laten zien hoe het werkt,
            // hoewel een enum‑waarde ook prima is.
            val mockedStatus = mockk<StatusCode>()
            every { mockedStatus.name } returns "OK"

            context.status = mockedStatus
            assertSame(mockedStatus, context.status)
        }
    }
}