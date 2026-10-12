package mg.ecoledimanche.presences.ui.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mg.ecoledimanche.presences.data.repository.EnfantRepository
import mg.ecoledimanche.presences.domain.AnalyseImport
import mg.ecoledimanche.presences.domain.AppClock
import mg.ecoledimanche.presences.domain.DonneesEnfant
import mg.ecoledimanche.presences.domain.ImportCsv
import mg.ecoledimanche.presences.domain.LigneRejetee
import mg.ecoledimanche.presences.domain.aujourdhui

/** Étapes de l'import : choix du fichier → aperçu (rien n'est encore enregistré) → ajout. */
sealed interface EtatImport {
    /** Explications et bouton « Choisir un fichier CSV ». */
    data object Repos : EtatImport

    data object Lecture : EtatImport

    /** Le fichier n'a pas pu être lu (absent, trop gros, illisible). */
    data object FichierIllisible : EtatImport

    data object FichierVide : EtatImport

    data class ColonnesManquantes(val colonnes: List<String>) : EtatImport

    /** Résultat de l'analyse, à confirmer : les enfants valides et les lignes écartées (avec le motif). */
    data class Apercu(
        val aAjouter: List<DonneesEnfant>,
        val rejets: List<LigneRejetee>,
        val enregistrement: Boolean = false,
        val erreurEnregistrement: Boolean = false,
    ) : EtatImport

    data class Termine(val ajoutes: Int, val ignores: Int) : EtatImport
}

/**
 * Import CSV des enfants. Le ViewModel ne connaît pas Android : l'écran lui donne une fonction qui lit
 * les octets du fichier choisi avec le sélecteur de fichiers du système.
 */
class ImporterViewModel(
    private val enfants: EnfantRepository,
    private val horloge: AppClock,
) : ViewModel() {
    private val _etat = MutableStateFlow<EtatImport>(EtatImport.Repos)
    val etat: StateFlow<EtatImport> = _etat

    fun analyser(lire: suspend () -> ByteArray) {
        if (_etat.value == EtatImport.Lecture) return
        _etat.value = EtatImport.Lecture
        viewModelScope.launch {
            _etat.value = try {
                val octets = lire()
                if (octets.size > ImportCsv.TAILLE_MAX_OCTETS) {
                    EtatImport.FichierIllisible
                } else {
                    val existants = enfants.identitesExistantes()
                    val analyse = withContext(Dispatchers.Default) {
                        ImportCsv.analyser(ImportCsv.decoder(octets), horloge.aujourdhui(), existants)
                    }
                    when (analyse) {
                        AnalyseImport.FichierVide -> EtatImport.FichierVide
                        is AnalyseImport.ColonnesManquantes -> EtatImport.ColonnesManquantes(analyse.colonnes)
                        is AnalyseImport.Pret -> EtatImport.Apercu(analyse.aAjouter, analyse.rejets)
                    }
                }
            } catch (erreur: CancellationException) {
                throw erreur
            } catch (erreur: Exception) {
                EtatImport.FichierIllisible
            }
        }
    }

    /** Ajoute tous les enfants valides de l'aperçu, en une seule transaction (tous ou aucun). */
    fun confirmer() {
        val apercu = _etat.value as? EtatImport.Apercu ?: return
        if (apercu.enregistrement || apercu.aAjouter.isEmpty()) return
        _etat.value = apercu.copy(enregistrement = true, erreurEnregistrement = false)
        viewModelScope.launch {
            _etat.value = try {
                val ajoutes = enfants.ajouterPlusieurs(apercu.aAjouter)
                EtatImport.Termine(ajoutes, apercu.rejets.size)
            } catch (erreur: CancellationException) {
                throw erreur
            } catch (erreur: Exception) {
                apercu.copy(enregistrement = false, erreurEnregistrement = true)
            }
        }
    }

    /** Retour au choix du fichier (rien n'a été enregistré, ou l'import est terminé). */
    fun recommencer() {
        val courant = _etat.value
        if (courant is EtatImport.Apercu && courant.enregistrement) return
        _etat.value = EtatImport.Repos
    }
}
