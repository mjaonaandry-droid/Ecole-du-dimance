package mg.ecoledimanche.presences.ui.donnees

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mg.ecoledimanche.presences.data.repository.DonneesRepository
import mg.ecoledimanche.presences.data.repository.ResumeDonnees
import mg.ecoledimanche.presences.data.sauvegarde.FormatSauvegarde
import mg.ecoledimanche.presences.data.sauvegarde.ManifesteSauvegarde
import mg.ecoledimanche.presences.data.sauvegarde.ProblemeSauvegarde
import mg.ecoledimanche.presences.data.sauvegarde.SauvegardeInvalideException
import mg.ecoledimanche.presences.data.sauvegarde.SauvegardeLue
import mg.ecoledimanche.presences.domain.AppClock
import mg.ecoledimanche.presences.domain.aujourdhui

sealed interface ActionSauvegarde {
    data object Repos : ActionSauvegarde
    data object EnCours : ActionSauvegarde
    data class Reussie(val manifeste: ManifesteSauvegarde) : ActionSauvegarde
    data object Echec : ActionSauvegarde
}

sealed interface EtapeRestauration {
    data object Aucune : EtapeRestauration
    data object Lecture : EtapeRestauration

    /** Le fichier choisi ne peut pas être restauré : rien n'a été modifié. */
    data class Invalide(val probleme: ProblemeSauvegarde) : EtapeRestauration

    /** Sauvegarde lue et vérifiée, à confirmer : elle REMPLACERA les données actuelles. */
    data class Apercu(val lue: SauvegardeLue, val actuelles: ResumeDonnees) : EtapeRestauration
    data object EnCours : EtapeRestauration
    data class Reussie(val manifeste: ManifesteSauvegarde) : EtapeRestauration

    /** L'écriture a échoué : les données actuelles sont intactes. */
    data object Echec : EtapeRestauration
}

sealed interface EtapeRemiseAZero {
    data object Aucune : EtapeRemiseAZero
    data class Confirmation(val actuelles: ResumeDonnees) : EtapeRemiseAZero
    data object EnCours : EtapeRemiseAZero
    data object Echec : EtapeRemiseAZero
}

data class EtatDonnees(
    val sauvegarde: ActionSauvegarde = ActionSauvegarde.Repos,
    val restauration: EtapeRestauration = EtapeRestauration.Aucune,
    val remiseAZero: EtapeRemiseAZero = EtapeRemiseAZero.Aucune,
) {
    val occupe: Boolean
        get() = sauvegarde == ActionSauvegarde.EnCours ||
            restauration == EtapeRestauration.Lecture ||
            restauration == EtapeRestauration.EnCours ||
            remiseAZero == EtapeRemiseAZero.EnCours
}

/**
 * Sauvegarde complète, restauration et « Repartir de zéro ». Le ViewModel ne connaît pas Android :
 * l'écran lui donne de quoi ouvrir le fichier choisi avec le sélecteur de fichiers du système.
 *
 * @param apresRestauration travail à faire une fois les données remplacées (rattrapage des clôtures,
 *   retrait des anciennes photos devenues orphelines).
 */
class DonneesViewModel(
    private val donnees: DonneesRepository,
    private val horloge: AppClock,
    private val apresRestauration: suspend () -> Unit,
) : ViewModel() {
    private val _etat = MutableStateFlow(EtatDonnees())
    val etat: StateFlow<EtatDonnees> = _etat

    /** Fichier de la restauration en cours : relu une seconde fois pour copier les photos. */
    private var ouvrirRestauration: (() -> InputStream)? = null

    fun nomFichierSuggere(): String = FormatSauvegarde.nomFichier(horloge.aujourdhui())

    /** Écrit une sauvegarde complète dans le fichier ouvert par [ouvrir] (fermé ensuite). */
    fun sauvegarder(ouvrir: () -> OutputStream) {
        if (_etat.value.occupe) return
        _etat.update { it.copy(sauvegarde = ActionSauvegarde.EnCours) }
        viewModelScope.launch {
            val resultat = try {
                val manifeste = ouvrir().use { donnees.sauvegarder(it) }
                ActionSauvegarde.Reussie(manifeste)
            } catch (erreur: CancellationException) {
                throw erreur
            } catch (erreur: Exception) {
                ActionSauvegarde.Echec
            }
            _etat.update { it.copy(sauvegarde = resultat) }
        }
    }

    fun acquitterSauvegarde() {
        if (_etat.value.sauvegarde != ActionSauvegarde.EnCours) _etat.update { it.copy(sauvegarde = ActionSauvegarde.Repos) }
    }

    /** Lit et vérifie la sauvegarde choisie, sans rien modifier, puis présente l'aperçu à confirmer. */
    fun lireRestauration(ouvrir: () -> InputStream) {
        if (_etat.value.occupe) return
        _etat.update { it.copy(restauration = EtapeRestauration.Lecture) }
        viewModelScope.launch {
            val resultat = try {
                val lue = donnees.lireSauvegarde(ouvrir)
                ouvrirRestauration = ouvrir
                EtapeRestauration.Apercu(lue, donnees.resume())
            } catch (erreur: CancellationException) {
                throw erreur
            } catch (erreur: SauvegardeInvalideException) {
                EtapeRestauration.Invalide(erreur.probleme)
            } catch (erreur: Exception) {
                EtapeRestauration.Invalide(ProblemeSauvegarde.ILLISIBLE)
            }
            _etat.update { it.copy(restauration = resultat) }
        }
    }

    fun confirmerRestauration() {
        val apercu = _etat.value.restauration as? EtapeRestauration.Apercu ?: return
        val ouvrir = ouvrirRestauration ?: return
        _etat.update { it.copy(restauration = EtapeRestauration.EnCours) }
        viewModelScope.launch {
            val resultat = try {
                donnees.restaurer(apercu.lue, ouvrir)
                try {
                    apresRestauration()
                } catch (erreur: Exception) {
                    // L'entretien sera refait au prochain démarrage : la restauration elle-même a réussi.
                }
                EtapeRestauration.Reussie(apercu.lue.manifeste)
            } catch (erreur: CancellationException) {
                throw erreur
            } catch (erreur: Exception) {
                EtapeRestauration.Echec
            }
            ouvrirRestauration = null
            _etat.update { it.copy(restauration = resultat) }
        }
    }

    /** Ferme l'aperçu ou le message de la restauration. */
    fun fermerRestauration() {
        val etape = _etat.value.restauration
        if (etape == EtapeRestauration.Lecture || etape == EtapeRestauration.EnCours) return
        ouvrirRestauration = null
        _etat.update { it.copy(restauration = EtapeRestauration.Aucune) }
    }

    fun demanderRemiseAZero() {
        if (_etat.value.occupe) return
        viewModelScope.launch {
            val actuelles = try {
                donnees.resume()
            } catch (erreur: CancellationException) {
                throw erreur
            } catch (erreur: Exception) {
                ResumeDonnees(0, 0)
            }
            _etat.update { it.copy(remiseAZero = EtapeRemiseAZero.Confirmation(actuelles)) }
        }
    }

    /** Efface tout : l'application revient d'elle-même à l'écran d'accueil (données vierges). */
    fun confirmerRemiseAZero() {
        if (_etat.value.remiseAZero !is EtapeRemiseAZero.Confirmation) return
        _etat.update { it.copy(remiseAZero = EtapeRemiseAZero.EnCours) }
        viewModelScope.launch {
            val resultat = try {
                donnees.reinitialiser()
                EtapeRemiseAZero.Aucune
            } catch (erreur: CancellationException) {
                throw erreur
            } catch (erreur: Exception) {
                EtapeRemiseAZero.Echec
            }
            _etat.update { it.copy(remiseAZero = resultat) }
        }
    }

    fun annulerRemiseAZero() {
        if (_etat.value.remiseAZero != EtapeRemiseAZero.EnCours) _etat.update { it.copy(remiseAZero = EtapeRemiseAZero.Aucune) }
    }

    /** « Commencer avec des données vierges » (écran d'accueil). */
    fun commencerVide() {
        viewModelScope.launch {
            try {
                donnees.terminerAccueil()
            } catch (erreur: CancellationException) {
                throw erreur
            } catch (erreur: Exception) {
                // L'écran d'accueil reste affiché : l'utilisateur peut réessayer.
            }
        }
    }
}
