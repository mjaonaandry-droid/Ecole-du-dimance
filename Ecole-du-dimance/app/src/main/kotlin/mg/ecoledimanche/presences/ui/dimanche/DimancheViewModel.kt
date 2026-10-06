package mg.ecoledimanche.presences.ui.dimanche

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mg.ecoledimanche.presences.data.repository.ElementDimanche
import mg.ecoledimanche.presences.data.repository.MotifRefus
import mg.ecoledimanche.presences.data.repository.PresenceRepository
import mg.ecoledimanche.presences.data.repository.ResultatPointage
import mg.ecoledimanche.presences.domain.AppClock
import mg.ecoledimanche.presences.domain.Dimanches
import mg.ecoledimanche.presences.domain.PhaseSeance
import mg.ecoledimanche.presences.domain.StatutPresence
import mg.ecoledimanche.presences.domain.aujourdhui

sealed interface EtatEcranDimanche {
    data object Chargement : EtatEcranDimanche
    data object Erreur : EtatEcranDimanche

    data class Pret(
        val dimanche: LocalDate,
        val phase: PhaseSeance,
        val elements: List<ElementDimanche>,
        val peutReculer: Boolean,
        val peutAvancer: Boolean,
        /** Dimanches proposés par le sélecteur, du plus récent au plus ancien. */
        val dimanchesProposes: List<LocalDate>,
        val estDimancheParDefaut: Boolean,
        /** Mode essai (dimanche futur uniquement) : les statuts montrés sont simulés, jamais enregistrés. */
        val essaiActif: Boolean = false,
    ) : EtatEcranDimanche
}

/** Pourquoi un pointage a échoué ; transformé en message français par l'écran. */
enum class ErreurPointage {
    ECRITURE,
    SEANCE_A_VENIR,
    ENFANT_NON_ADMISSIBLE,
    ABSENT_INTERDIT_AVANT_CLOTURE,
    ANNULATION_INTERDITE_APRES_CLOTURE,
    ;

    companion object {
        fun depuis(motif: MotifRefus): ErreurPointage = when (motif) {
            MotifRefus.PAS_UN_DIMANCHE, MotifRefus.ENFANT_NON_ADMISSIBLE -> ENFANT_NON_ADMISSIBLE
            MotifRefus.SEANCE_A_VENIR -> SEANCE_A_VENIR
            MotifRefus.ABSENT_INTERDIT_AVANT_CLOTURE -> ABSENT_INTERDIT_AVANT_CLOTURE
            MotifRefus.ANNULATION_INTERDITE_APRES_CLOTURE -> ANNULATION_INTERDITE_APRES_CLOTURE
        }
    }
}

/** État du panneau de choix du statut (feuille modale ouverte sur un enfant). */
data class PanneauStatutUi(
    val enfantId: Long? = null,
    val enregistrement: Boolean = false,
    val erreur: ErreurPointage? = null,
)

/** Mode essai : statuts simulés en mémoire pour un dimanche futur. Rien n'est écrit dans Room. */
private data class EssaiUi(
    val actif: Boolean = false,
    val statuts: Map<Long, StatutPresence> = emptyMap(),
)

class DimancheViewModel(
    private val presences: PresenceRepository,
    private val horloge: AppClock,
    private val actualisation: Flow<Unit>,
) : ViewModel() {
    /** Dimanche choisi par l'utilisateur ; nul = dimanche par défaut (celui du jour, sinon le prochain). */
    private val choix = MutableStateFlow<LocalDate?>(null)

    private val _panneau = MutableStateFlow(PanneauStatutUi())
    val panneau: StateFlow<PanneauStatutUi> = _panneau

    private val essai = MutableStateFlow(EssaiUi())

    @OptIn(ExperimentalCoroutinesApi::class)
    val etat: StateFlow<EtatEcranDimanche> = combine(choix, actualisation) { choisi, _ -> choisi }
        .flatMapLatest { choisi -> fluxDimanche(choisi) }
        .combine(essai) { etatBase, essaiCourant -> appliquerEssai(etatBase, essaiCourant) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EtatEcranDimanche.Chargement)

    private fun fluxDimanche(choisi: LocalDate?): Flow<EtatEcranDimanche> = flow<EtatEcranDimanche> {
        // Rattrapage AVANT d'afficher : après 10 h, l'écran montre toujours un résultat cohérent.
        presences.rattraper()
        val parDefaut = Dimanches.parDefaut(horloge.aujourdhui())
        val dimanche = choisi ?: parDefaut
        val premier = presences.premierDimancheSuivi()
        val dernier = parDefaut.plusWeeks(SEMAINES_A_VENIR)
        val proposes = if (premier == null) listOf(parDefaut) else Dimanches.entre(minOf(premier, dimanche), dernier).asReversed()
        emitAll(
            presences.observerDimanche(dimanche).map { contenu ->
                EtatEcranDimanche.Pret(
                    dimanche = dimanche,
                    phase = contenu.phase,
                    elements = contenu.elements,
                    peutReculer = premier != null && dimanche.isAfter(premier),
                    peutAvancer = dimanche.isBefore(dernier),
                    dimanchesProposes = proposes,
                    estDimancheParDefaut = dimanche == parDefaut,
                )
            },
        )
    }.catch { erreur ->
        if (erreur is CancellationException) throw erreur
        emit(EtatEcranDimanche.Erreur)
    }

    fun choisirDimanche(dimanche: LocalDate) {
        quitterEssai()
        choix.value = dimanche
    }

    fun revenirAuDimancheParDefaut() {
        quitterEssai()
        choix.value = null
    }

    fun dimanchePrecedent() = deplacer { Dimanches.precedentStrict(it) }

    fun dimancheSuivant() = deplacer { Dimanches.suivantStrict(it) }

    private fun deplacer(calcul: (LocalDate) -> LocalDate) {
        val courant = (etat.value as? EtatEcranDimanche.Pret)?.dimanche ?: return
        choisirDimanche(calcul(courant))
    }

    fun ouvrirPanneau(enfantId: Long) {
        val pret = etat.value as? EtatEcranDimanche.Pret ?: return
        // Pointage réel désactivé pour un dimanche futur ; seul le mode essai (rien d'enregistré) l'ouvre.
        if (pret.phase == PhaseSeance.A_VENIR && !pret.essaiActif) return
        _panneau.value = PanneauStatutUi(enfantId = enfantId)
    }

    fun fermerPanneau() {
        _panneau.value = PanneauStatutUi()
    }

    /**
     * Enregistre immédiatement le statut. Le panneau ne se ferme et la carte ne change qu'une fois
     * l'écriture réussie : l'écran lit uniquement la base, jamais un état « espéré ».
     */
    fun pointer(statut: StatutPresence) {
        val pret = etat.value as? EtatEcranDimanche.Pret ?: return
        val enfantId = _panneau.value.enfantId ?: return
        if (pret.essaiActif) {
            pointerEnEssai(enfantId, statut)
            return
        }
        if (_panneau.value.enregistrement) return // clics rapprochés : un seul enregistrement à la fois
        _panneau.update { it.copy(enregistrement = true, erreur = null) }
        viewModelScope.launch {
            val resultat = try {
                presences.pointer(enfantId, pret.dimanche, statut)
            } catch (erreur: CancellationException) {
                throw erreur
            } catch (erreur: Exception) {
                null
            }
            when (resultat) {
                is ResultatPointage.Enregistre -> _panneau.value = PanneauStatutUi()
                is ResultatPointage.Refuse -> _panneau.update { it.copy(enregistrement = false, erreur = ErreurPointage.depuis(resultat.motif)) }
                null -> _panneau.update { it.copy(enregistrement = false, erreur = ErreurPointage.ECRITURE) }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Mode essai : cliquer sur les photos d'un dimanche futur pour s'entraîner, sans rien enregistrer
    // ---------------------------------------------------------------------------------------------

    /** Active l'essai ; ignoré hors dimanche futur (un vrai pointage reste un vrai pointage). */
    fun demarrerEssai() {
        val pret = etat.value as? EtatEcranDimanche.Pret ?: return
        if (pret.phase != PhaseSeance.A_VENIR) return
        fermerPanneau()
        essai.value = EssaiUi(actif = true)
    }

    /** Quitte l'essai : tous les statuts simulés disparaissent. */
    fun quitterEssai() {
        fermerPanneau()
        essai.value = EssaiUi()
    }

    private fun pointerEnEssai(enfantId: Long, statut: StatutPresence) {
        if (statut == StatutPresence.ABSENT) return // « Absent » n'existe qu'à la clôture
        essai.update { courant ->
            val statuts = if (statut == StatutPresence.NON_ENREGISTRE) courant.statuts - enfantId else courant.statuts + (enfantId to statut)
            courant.copy(statuts = statuts)
        }
        _panneau.value = PanneauStatutUi()
    }

    private fun appliquerEssai(base: EtatEcranDimanche, essaiCourant: EssaiUi): EtatEcranDimanche {
        if (base !is EtatEcranDimanche.Pret || !essaiCourant.actif || base.phase != PhaseSeance.A_VENIR) return base
        return base.copy(
            essaiActif = true,
            elements = base.elements.map { element ->
                essaiCourant.statuts[element.enfant.id]?.let { element.copy(statut = it) } ?: element
            },
        )
    }

    private companion object {
        /** Combien de dimanches à venir le sélecteur propose. */
        const val SEMAINES_A_VENIR = 8L
    }
}
