package mg.ecoledimanche.presences.data.repository

import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import mg.ecoledimanche.presences.data.local.CleParametre
import mg.ecoledimanche.presences.data.local.MaintenanceDao
import mg.ecoledimanche.presences.data.local.PHOTO_NON_PRISE
import mg.ecoledimanche.presences.data.local.ParametreDao
import mg.ecoledimanche.presences.data.local.ParametreEntity
import mg.ecoledimanche.presences.data.sauvegarde.ContenuSauvegarde
import mg.ecoledimanche.presences.data.sauvegarde.FormatSauvegarde
import mg.ecoledimanche.presences.data.sauvegarde.ManifesteSauvegarde
import mg.ecoledimanche.presences.data.sauvegarde.SauvegardeLue
import mg.ecoledimanche.presences.domain.AppClock

/** Ce que contient actuellement l'application (affiché avant une restauration ou une remise à zéro). */
data class ResumeDonnees(val enfants: Int, val seances: Int)

/**
 * Données de l'application dans leur ensemble : sauvegarde complète (base + photos dans un seul fichier),
 * restauration, remise à zéro (« Repartir de zéro ») et écran d'accueil d'une installation vierge.
 */
class DonneesRepository(
    private val maintenance: MaintenanceDao,
    private val parametres: ParametreDao,
    private val transaction: ExecuteurTransaction,
    private val fichiers: FichiersPhotos,
    private val clock: AppClock,
    private val versionApplication: String,
    private val versionBase: Int,
) {
    /** Vrai tant que l'écran d'accueil (commencer vide ou restaurer une sauvegarde) n'a pas été passé. */
    fun accueilAFaire(): Flow<Boolean> = parametres.observer(CleParametre.ACCUEIL_FAIT).map { it != "1" }

    /** « Commencer avec des données vierges » : l'application s'ouvre, sans rien à importer. */
    suspend fun terminerAccueil() {
        parametres.ecrire(ParametreEntity(CleParametre.ACCUEIL_FAIT, "1"))
    }

    suspend fun resume(): ResumeDonnees = transaction.executer { ResumeDonnees(maintenance.nombreEnfants(), maintenance.nombreSeances()) }

    /**
     * Écrit une sauvegarde complète dans [sortie] : toutes les tables (lues dans une transaction, donc
     * cohérentes entre elles) et les photos des enfants. [sortie] n'est pas fermée.
     */
    suspend fun sauvegarder(sortie: OutputStream): ManifesteSauvegarde {
        val contenu = transaction.executer {
            ContenuSauvegarde(
                maintenance.enfants(),
                maintenance.seances(),
                maintenance.presences(),
                maintenance.examens(),
                maintenance.notes(),
            )
        }
        return withContext(Dispatchers.IO) {
            FormatSauvegarde.ecrire(sortie, contenu, versionApplication, versionBase, clock.instant(), fichiers::ouvrir)
        }
    }

    /**
     * Lit et vérifie une sauvegarde sans rien modifier (aperçu avant restauration).
     * @throws mg.ecoledimanche.presences.data.sauvegarde.SauvegardeInvalideException si elle ne peut pas être restaurée.
     */
    suspend fun lireSauvegarde(ouvrir: () -> InputStream): SauvegardeLue =
        withContext(Dispatchers.IO) { ouvrir().use { FormatSauvegarde.lire(it) } }

    /**
     * REMPLACE toutes les données par celles de la sauvegarde :
     * 1. les photos de la sauvegarde sont copiées dans le répertoire privé (rien n'est encore effacé) ;
     * 2. dans UNE transaction : les tables sont vidées puis remplies avec la sauvegarde (identifiants
     *    conservés), et l'écran d'accueil est marqué comme passé.
     * Si l'étape 2 échoue, les données actuelles restent intactes. Les anciennes photos, devenues
     * orphelines, sont retirées par l'entretien habituel ([ConteneurApp.rattraperEtEntretenir]).
     * Une fiche dont la photo manque dans la sauvegarde reçoit la silhouette.
     * Non annulable en cours de route.
     */
    suspend fun restaurer(lue: SauvegardeLue, ouvrir: () -> InputStream) = withContext(NonCancellable) {
        val photos = lue.contenu.enfants.map { it.photoPath }.filter { it.isNotEmpty() && it in lue.photos }.toSet()
        if (photos.isNotEmpty()) {
            withContext(Dispatchers.IO) {
                ouvrir().use { flux -> FormatSauvegarde.extrairePhotos(flux, photos) { chemin, contenu -> fichiers.enregistrer(chemin, contenu) } }
            }
        }
        val enfants = lue.contenu.enfants.map { if (it.photoPath.isNotEmpty() && it.photoPath !in photos) it.copy(photoPath = PHOTO_NON_PRISE) else it }
        transaction.executer {
            viderTout()
            maintenance.insererEnfants(enfants)
            maintenance.insererSeances(lue.contenu.seances)
            maintenance.insererPresences(lue.contenu.presences)
            maintenance.insererExamens(lue.contenu.examens)
            maintenance.insererNotes(lue.contenu.notes)
            parametres.ecrire(ParametreEntity(CleParametre.ACCUEIL_FAIT, "1"))
        }
    }

    /**
     * « Repartir de zéro » : efface DÉFINITIVEMENT enfants, séances, présences, examens, notes et photos. L'application
     * revient à l'écran d'accueil (commencer vide ou restaurer une sauvegarde). Non annulable en cours de route.
     */
    suspend fun reinitialiser() = withContext(NonCancellable) {
        transaction.executer {
            viderTout()
            parametres.effacer(CleParametre.ACCUEIL_FAIT)
        }
        withContext(Dispatchers.IO) { fichiers.toutSupprimer() }
    }

    /** Ordre imposé par les clés étrangères en RESTRICT : les tables « enfants » avant leurs parents. */
    private suspend fun viderTout() {
        maintenance.viderNotes()
        maintenance.viderPresences()
        maintenance.viderSeances()
        maintenance.viderEnfants()
        maintenance.viderExamens()
    }
}
