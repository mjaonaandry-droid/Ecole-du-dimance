package mg.ecoledimanche.presences.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mg.ecoledimanche.presences.data.repository.EnfantRepository
import mg.ecoledimanche.presences.data.repository.NotesRepository
import mg.ecoledimanche.presences.domain.AppClock
import mg.ecoledimanche.presences.domain.Notes
import mg.ecoledimanche.presences.domain.aujourdhui

/** La note d'un enfant telle que tapée ; vide = pas de note (compte 0). */
data class SaisieNote(
    val enfantId: Long,
    val prenom: String,
    val nom: String,
    val texte: String,
    val invalide: Boolean = false,
)

data class EtatExamen(
    val chargement: Boolean = true,
    /** Nul : nouvel examen. */
    val examenId: Long? = null,
    val intitule: String = "",
    val date: LocalDate,
    val notes: List<SaisieNote> = emptyList(),
    val intituleInvalide: Boolean = false,
    val modifie: Boolean = false,
    val enregistrement: Boolean = false,
    val erreurEnregistrement: Boolean = false,
    /** Examen introuvable (supprimé entre-temps). */
    val introuvable: Boolean = false,
    val confirmationSuppression: Boolean = false,
    /** Enregistré ou supprimé : l'écran se ferme. */
    val termine: Boolean = false,
) {
    val notesSaisies: Int get() = notes.count { it.texte.isNotBlank() }
}

/**
 * Création ou modification d'un examen : intitulé, date et une note sur 20 par enfant ACTIF. Une case
 * vide signifie « pas de note » et compte 0 dans le cumul. Tout est enregistré en une transaction.
 */
class ExamenViewModel(
    private val notesRepository: NotesRepository,
    private val enfants: EnfantRepository,
    horloge: AppClock,
    private val examenId: Long?,
) : ViewModel() {
    private val _etat = MutableStateFlow(EtatExamen(examenId = examenId, date = horloge.aujourdhui()))
    val etat: StateFlow<EtatExamen> = _etat

    init {
        viewModelScope.launch {
            try {
                val actifs = enfants.observerActifs().first()
                val existant = examenId?.let { notesRepository.chargerExamen(it) }
                if (examenId != null && existant == null) {
                    _etat.update { it.copy(chargement = false, introuvable = true) }
                    return@launch
                }
                _etat.update { e ->
                    e.copy(
                        chargement = false,
                        intitule = existant?.examen?.intitule.orEmpty(),
                        date = existant?.examen?.date ?: e.date,
                        notes = actifs.map { enfant ->
                            SaisieNote(enfant.id, enfant.prenom, enfant.nom, existant?.notes?.get(enfant.id)?.let(Notes::format).orEmpty())
                        },
                    )
                }
            } catch (erreur: CancellationException) {
                throw erreur
            } catch (erreur: Exception) {
                _etat.update { it.copy(chargement = false, introuvable = true) }
            }
        }
    }

    fun modifierIntitule(texte: String) {
        _etat.update { it.copy(intitule = texte.take(Notes.LONGUEUR_MAX_INTITULE), intituleInvalide = false, modifie = true) }
    }

    fun modifierDate(date: LocalDate) {
        _etat.update { it.copy(date = date, modifie = true) }
    }

    fun modifierNote(enfantId: Long, texte: String) {
        val propre = texte.filter { it.isDigit() || it == ',' || it == '.' }.take(5)
        _etat.update { e ->
            e.copy(
                notes = e.notes.map { if (it.enfantId == enfantId) it.copy(texte = propre, invalide = false) else it },
                modifie = true,
            )
        }
    }

    fun enregistrer() {
        val e = _etat.value
        if (e.chargement || e.enregistrement) return
        val notes = e.notes.map { it to (if (it.texte.isBlank()) null else Notes.lire(it.texte)) }
        val intituleInvalide = e.intitule.isBlank()
        val invalides = notes.filter { (saisie, valeur) -> saisie.texte.isNotBlank() && valeur == null }.map { it.first.enfantId }.toSet()
        if (intituleInvalide || invalides.isNotEmpty()) {
            _etat.update { s -> s.copy(intituleInvalide = intituleInvalide, notes = s.notes.map { it.copy(invalide = it.enfantId in invalides) }) }
            return
        }
        _etat.update { it.copy(enregistrement = true, erreurEnregistrement = false) }
        viewModelScope.launch {
            val reussi = executer {
                notesRepository.enregistrerExamen(e.examenId, e.intitule, e.date, notes.associate { (saisie, valeur) -> saisie.enfantId to valeur })
            }
            _etat.update { it.copy(enregistrement = false, termine = reussi, erreurEnregistrement = !reussi) }
        }
    }

    fun demanderSuppression() {
        if (_etat.value.examenId != null) _etat.update { it.copy(confirmationSuppression = true) }
    }

    fun annulerSuppression() {
        _etat.update { it.copy(confirmationSuppression = false) }
    }

    fun confirmerSuppression() {
        val id = _etat.value.examenId ?: return
        _etat.update { it.copy(confirmationSuppression = false, enregistrement = true) }
        viewModelScope.launch {
            val reussi = executer { notesRepository.supprimerExamen(id) }
            _etat.update { it.copy(enregistrement = false, termine = reussi, erreurEnregistrement = !reussi) }
        }
    }

    /** Vrai si [bloc] a réussi ; une panne d'écriture est annoncée, jamais ignorée. */
    private suspend fun executer(bloc: suspend () -> Unit): Boolean = try {
        bloc()
        true
    } catch (erreur: CancellationException) {
        throw erreur
    } catch (erreur: Exception) {
        false
    }
}
