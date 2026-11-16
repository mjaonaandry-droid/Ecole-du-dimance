package mg.ecoledimanche.presences.ui.exporter

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mg.ecoledimanche.presences.data.repository.PresenceRepository
import mg.ecoledimanche.presences.domain.AppClock
import mg.ecoledimanche.presences.domain.ExportCsv
import mg.ecoledimanche.presences.domain.GroupeExport
import mg.ecoledimanche.presences.domain.aujourdhui

/** Ce que contiendra le fichier, annoncé avant l'export. */
sealed interface ApercuExport {
    data object Chargement : ApercuExport
    data object Erreur : ApercuExport
    data class Pret(val enfants: Int, val dimanches: Int) : ApercuExport
}

/** Où en est l'export demandé par l'utilisateur. */
sealed interface ActionExport {
    data object Repos : ActionExport
    data object EnCours : ActionExport
    data class Reussie(val enfants: Int, val dimanches: Int) : ActionExport
    data object Echec : ActionExport
}

data class EtatEcranExport(
    val inclureArchives: Boolean,
    val apercu: ApercuExport,
    val action: ActionExport,
    /** Groupes de colonnes choisis (l'identité de l'enfant est toujours exportée). */
    val groupes: Set<GroupeExport> = GroupeExport.TOUS,
) {
    val toutExporter: Boolean get() = groupes.containsAll(GroupeExport.TOUS)

    /** Le fichier contiendra des informations sur la famille (adresse, parents, fratrie). */
    val contientInfosPersonnelles: Boolean get() = groupes.any { it in GroupeExport.PERSONNELS }
}

/**
 * Export CSV des enfants, complet ou partiel (groupes de colonnes au choix). Le ViewModel ne connaît ni le système
 * de fichiers ni Android : l'écran lui donne la fonction qui écrit le texte dans le fichier choisi.
 */
class ExporterViewModel(
    private val presences: PresenceRepository,
    private val horloge: AppClock,
    private val actualisation: Flow<Unit>,
) : ViewModel() {
    private val archives = MutableStateFlow(true)
    private val action = MutableStateFlow<ActionExport>(ActionExport.Repos)
    private val groupes = MutableStateFlow(GroupeExport.TOUS)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val apercu: Flow<ApercuExport> = combine(archives, actualisation) { inclure, _ -> inclure }
        .flatMapLatest { inclure ->
            flow<ApercuExport> {
                emit(ApercuExport.Chargement)
                emit(
                    try {
                        val tableau = presences.tableauExport(inclure)
                        ApercuExport.Pret(tableau.lignes.size, tableau.dimanches.size)
                    } catch (erreur: CancellationException) {
                        throw erreur
                    } catch (erreur: Exception) {
                        ApercuExport.Erreur
                    },
                )
            }
        }

    val etat: StateFlow<EtatEcranExport> = combine(archives, apercu, action, groupes) { inclure, contenu, avancement, choisis ->
        EtatEcranExport(inclure, contenu, avancement, choisis)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        EtatEcranExport(archives.value, ApercuExport.Chargement, ActionExport.Repos, groupes.value),
    )

    fun inclureArchives(inclure: Boolean) {
        archives.value = inclure
        action.value = ActionExport.Repos
    }

    /** Coche ou décoche un groupe de colonnes. */
    fun basculerGroupe(groupe: GroupeExport, inclus: Boolean) {
        groupes.update { if (inclus) it + groupe else it - groupe }
        action.value = ActionExport.Repos
    }

    /** « Tout exporter » coché : tous les groupes ; décoché : seulement l'identité des enfants. */
    fun toutExporter(tout: Boolean) {
        groupes.value = if (tout) GroupeExport.TOUS else emptySet()
        action.value = ActionExport.Repos
    }

    fun choisirGroupes(choisis: Set<GroupeExport>) {
        groupes.value = choisis
        action.value = ActionExport.Repos
    }

    /** Nom de fichier proposé au moment de choisir la destination. */
    fun nomFichierSuggere(): String = ExportCsv.nomFichier(horloge.aujourdhui())

    /**
     * Construit le CSV à partir des données actuelles puis le confie à [ecrire] (écriture dans le
     * fichier choisi par l'utilisateur). Un seul export à la fois ; une panne d'écriture est
     * annoncée, jamais ignorée.
     */
    fun exporter(ecrire: suspend (String) -> Unit) {
        if (action.value == ActionExport.EnCours) return
        action.value = ActionExport.EnCours
        val inclure = archives.value
        val choisis = groupes.value
        viewModelScope.launch {
            val resultat = try {
                val tableau = presences.tableauExport(inclure)
                ecrire(ExportCsv.construire(tableau, horloge.aujourdhui(), choisis))
                ActionExport.Reussie(tableau.lignes.size, tableau.dimanches.size)
            } catch (erreur: CancellationException) {
                throw erreur
            } catch (erreur: Exception) {
                ActionExport.Echec
            }
            action.update { resultat }
        }
    }

    /** Efface le message de résultat. */
    fun acquitter() {
        if (action.value != ActionExport.EnCours) action.value = ActionExport.Repos
    }
}
