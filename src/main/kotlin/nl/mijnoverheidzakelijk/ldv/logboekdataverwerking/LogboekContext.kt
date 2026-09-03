package nl.mijnoverheidzakelijk.ldv.logboekdataverwerking

import io.opentelemetry.api.trace.StatusCode
import jakarta.enterprise.context.RequestScoped

/**
 * Een betrokkene bij een verwerking.
 *
 * @property id   Versleuteld of gepseudonimiseerd ID van de betrokkene.
 *                Mag NOOIT een onversleuteld BSN of direct identificerend gegeven bevatten.
 * @property type Type identificatie (bijv. "BSN", "KVK", "personeelsnummer").
 */
data class DataSubject(val id: String, val type: String)

/**
 * Request-scoped holder for LogboekDataverwerking-related context data that will be attached to spans.
 * This includes the processing activity, the data subject(s), and the span status.
 */
@RequestScoped
class LogboekContext {
    var processingActivityId: String? = null

    /**
     * Versleuteld of gepseudonimiseerd ID van de betrokkene.
     * Mag NOOIT een onversleuteld BSN of direct identificerend gegeven bevatten.
     * Zie de LDV standaard: https://gitdocumentatie.logius.nl/publicatie/logboek/dataverwerkingen/1.0.0/
     *
     * Sugar voor het enkelvoudige geval. Bij meerdere betrokkenen: gebruik [addSubject]
     * zodat de standaard een aparte logregel per betrokkene afdwingt (MUST).
     */
    var dataSubjectId: String? = null

    var dataSubjectType: String? = null

    var status: StatusCode = StatusCode.UNSET

    /**
     * De exceptie die via [expectException] als verwachte uitkomst is aangekondigd.
     * Vergelijking gebeurt op identiteit: alleen deze exceptie levert een logregel
     * zonder ERROR op. Eén slot: een tweede aankondiging vervangt de eerste. De
     * buitenste `@Logboek`-actie consumeert de aankondiging na afloop, zodat een
     * hergebruikte exceptie-instantie niet aangekondigd blijft voor latere acties in
     * dezelfde request; eerder intrekken kan met [clearExpectedException].
     */
    @Volatile
    var expectedException: Throwable? = null
        internal set

    /**
     * Markeert [e] als verwachte uitkomst en zet [status] op UNSET: de logregel krijgt
     * geen ERROR en geen `exception.*`-attributen. Aanroepen vlak vóór de throw.
     *
     * UNSET is wat de standaard voorschrijft voor een verwerking die zonder systeemfout
     * afrondt maar geen resultaat oplevert; ERROR is voor systeemfouten.
     */
    fun expectException(e: Throwable) {
        expectException(e, StatusCode.UNSET)
    }

    /** Als [expectException], met een expliciete [status] in plaats van UNSET. */
    fun expectException(e: Throwable, status: StatusCode) {
        this.status = status
        expectedException = e
    }

    /** True wanneer [e] de aangekondigde exceptie is; vergelijking op identiteit. */
    fun isExpected(e: Throwable): Boolean = expectedException === e

    /**
     * Trekt de aankondiging in. Aanroepen wanneer de aangekondigde exceptie tóch wordt
     * afgevangen en de actie doorloopt; zet daarna zelf [status], want die houdt de
     * waarde die bij de aankondiging is gezet.
     */
    fun clearExpectedException() {
        expectedException = null
    }

    /**
     * Mensleesbare actienaam, gezet door de interceptor. Wordt hergebruikt als naam
     * voor de per-betrokkene logregels bij meerdere betrokkenen.
     */
    var actionName: String? = null

    /**
     * Betrokkenen bij deze verwerking. Wanneer deze lijst gevuld is, krijgt elke
     * betrokkene een eigen logregel; de enkelvoudige [dataSubjectId]/[dataSubjectType]
     * worden dan genegeerd.
     */
    val subjects: MutableList<DataSubject> = mutableListOf()

    /** Voegt een betrokkene toe; gebruik dit bij verwerkingen met meerdere betrokkenen. */
    fun addSubject(id: String, type: String) {
        subjects.add(DataSubject(id, type))
    }

    /**
     * De effectieve betrokkenen: de expliciete [subjects] indien gevuld, anders het
     * enkelvoudige [dataSubjectId]/[dataSubjectType] paar als dat gezet is, anders leeg.
     */
    fun effectiveSubjects(): List<DataSubject> {
        if (subjects.isNotEmpty()) return subjects.toList()
        val id = dataSubjectId
        val type = dataSubjectType
        return if (id != null && type != null) listOf(DataSubject(id, type)) else emptyList()
    }
}
