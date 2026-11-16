package mg.ecoledimanche.presences.data.repository

import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import mg.ecoledimanche.presences.data.local.EnfantDao
import mg.ecoledimanche.presences.data.local.EnfantEntity
import mg.ecoledimanche.presences.data.local.ExamenDao
import mg.ecoledimanche.presences.data.local.ExamenEntity
import mg.ecoledimanche.presences.data.local.NoteEntity
import mg.ecoledimanche.presences.domain.AppClock
import mg.ecoledimanche.presences.domain.CumulNotes
import mg.ecoledimanche.presences.domain.Notes
import mg.ecoledimanche.presences.domain.Recherche

/** Un examen et le nombre de notes saisies (sur les enfants actifs). */
data class ResumeExamen(val examen: ExamenEntity, val notesSaisies: Int)

/** Cumul d'un enfant actif : chaque note (dans l'ordre des examens), total et moyenne. */
data class LigneNotes(val enfant: EnfantEntity, val cumul: CumulNotes)

data class TableauNotes(
    /** Du plus ancien au plus récent : c'est l'ordre des notes de chaque [LigneNotes]. */
    val examens: List<ResumeExamen>,
    val lignes: List<LigneNotes>,
)

/** Un examen ouvert pour la saisie : sa fiche et les notes déjà saisies (enfant → centièmes). */
data class ExamenSaisi(val examen: ExamenEntity, val notes: Map<Long, Int>)

/**
 * Examens et notes. Règle du cumul : chaque enfant ACTIF compte à TOUS les examens, un examen sans note
 * comptant 0 (voir [CumulNotes]). Les notes des enfants archivés sont conservées mais n'apparaissent pas.
 */
class NotesRepository(
    private val examenDao: ExamenDao,
    private val enfantDao: EnfantDao,
    private val transaction: ExecuteurTransaction,
    private val clock: AppClock,
) {
    private val comparateur = Recherche.comparateurNoms()

    fun observerTableau(): Flow<TableauNotes> =
        combine(enfantDao.observerSelonArchivage(false), examenDao.observerExamens(), examenDao.observerNotes()) { enfants, examens, notes ->
            val actifs = enfants.map { it.id }.toSet()
            val parEnfant = notes.groupBy { it.enfantId }.mapValues { (_, l) -> l.associate { it.examenId to it.centiemes } }
            val ids = examens.map { it.id }
            val parExamen = notes.filter { it.enfantId in actifs }.groupingBy { it.examenId }.eachCount()
            TableauNotes(
                examens = examens.map { ResumeExamen(it, parExamen[it.id] ?: 0) },
                lignes = enfants
                    .sortedWith { a, b -> comparateur.compare(a.prenom to a.nom, b.prenom to b.nom) }
                    .map { LigneNotes(it, CumulNotes.calculer(ids, parEnfant[it.id].orEmpty())) },
            )
        }

    suspend fun chargerExamen(id: Long): ExamenSaisi? = transaction.executer {
        val examen = examenDao.trouver(id) ?: return@executer null
        ExamenSaisi(examen, examenDao.notesDe(id).associate { it.enfantId to it.centiemes })
    }

    /**
     * Crée ([id] nul) ou modifie un examen et ses notes, en UNE transaction.
     * [notes] : pour chaque enfant concerné, sa note en centièmes, ou nul pour « pas de note » (compte 0).
     * Les enfants absents de [notes] (par exemple archivés) gardent leur note telle quelle.
     * @return l'identifiant de l'examen.
     */
    suspend fun enregistrerExamen(id: Long?, intitule: String, date: LocalDate, notes: Map<Long, Int?>): Long {
        val titre = intitule.trim()
        require(titre.isNotEmpty() && titre.length <= Notes.LONGUEUR_MAX_INTITULE) { "Intitulé invalide" }
        require(notes.values.all { it == null || it in 0..Notes.MAX_CENTIEMES }) { "Note invalide" }
        return transaction.executer {
            val maintenant = clock.instant()
            val examenId = if (id == null) {
                examenDao.inserer(ExamenEntity(intitule = titre, date = date, createdAt = maintenant, updatedAt = maintenant))
            } else {
                val existant = examenDao.trouver(id) ?: throw IllegalStateException("Examen $id introuvable")
                examenDao.mettreAJour(existant.copy(intitule = titre, date = date, updatedAt = maintenant))
                id
            }
            val avant = examenDao.notesDe(examenId).associateBy { it.enfantId }
            for ((enfantId, centiemes) in notes) {
                val ancienne = avant[enfantId]
                when {
                    centiemes == null -> if (ancienne != null) examenDao.effacerNote(examenId, enfantId)
                    ancienne?.centiemes == centiemes -> Unit
                    else -> examenDao.ecrireNote(
                        NoteEntity(examenId, enfantId, centiemes, ancienne?.createdAt ?: maintenant, maintenant),
                    )
                }
            }
            examenId
        }
    }

    /** Supprime l'examen et toutes ses notes (une transaction). @return faux s'il n'existait pas. */
    suspend fun supprimerExamen(id: Long): Boolean = transaction.executer {
        examenDao.supprimerNotesDe(id)
        examenDao.supprimer(id) > 0
    }
}
