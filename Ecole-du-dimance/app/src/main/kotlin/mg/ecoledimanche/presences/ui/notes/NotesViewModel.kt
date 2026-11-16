package mg.ecoledimanche.presences.ui.notes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import mg.ecoledimanche.presences.data.repository.NotesRepository
import mg.ecoledimanche.presences.data.repository.TableauNotes

sealed interface EtatEcranNotes {
    data object Chargement : EtatEcranNotes
    data object Erreur : EtatEcranNotes
    data class Pret(val tableau: TableauNotes) : EtatEcranNotes
}

/** Onglet Notes : examens et cumul (total, moyenne) de chaque enfant actif, se mettant à jour tout seul. */
class NotesViewModel(notes: NotesRepository) : ViewModel() {
    val etat: StateFlow<EtatEcranNotes> = notes.observerTableau()
        .map<TableauNotes, EtatEcranNotes> { EtatEcranNotes.Pret(it) }
        .catch { erreur ->
            if (erreur is CancellationException) throw erreur
            emit(EtatEcranNotes.Erreur)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EtatEcranNotes.Chargement)
}
