package mg.ecoledimanche.presences.data.local

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.Flow
import mg.ecoledimanche.presences.domain.StatutPresence

/**
 * Condition SQL (alias `p` sur la table presences) : au moins un enfant a été pointé Présent ou En retard
 * le dimanche de la ligne `p`. Un dimanche clôturé sans aucun pointage n'entre pas dans les cumuls.
 */
private const val DIMANCHE_POINTE =
    "EXISTS (SELECT 1 FROM presences q WHERE q.dateDimanche = p.dateDimanche AND q.statut IN ('PRESENT', 'EN_RETARD'))"

/** Un enfant admissible à un dimanche, avec son statut s'il est déjà enregistré (sinon nul). */
data class EnfantAvecStatut(
    @Embedded val enfant: EnfantEntity,
    val statut: StatutPresence?,
)

/** Plage de suivi d'un enfant, pour décider sans requête supplémentaire quels dimanches traiter. */
data class PlageSuivi(
    val dateDebutSuivi: LocalDate,
    val dateFinSuivi: LocalDate?,
)

/** Nombre de présences d'un enfant dans un statut donné, sur les séances clôturées. */
data class CompteurStatutLigne(
    val enfantId: Long,
    val statut: StatutPresence,
    val nombre: Int,
)

/** Une ligne d'historique : le statut et l'état de clôture de la séance correspondante. */
data class LigneHistorique(
    val dateDimanche: LocalDate,
    val statut: StatutPresence,
    val cloturee: Boolean,
    /**
     * Vrai si au moins un enfant a été pointé (Présent ou En retard) ce dimanche-là. Un dimanche
     * clôturé où personne n'a été pointé n'entre pas dans les cumuls d'assiduité.
     */
    val pointee: Boolean = true,
)

@Dao
interface EnfantDao {
    @Insert
    suspend fun inserer(enfant: EnfantEntity): Long

    @Update
    suspend fun mettreAJour(enfant: EnfantEntity)

    @Query("SELECT * FROM enfants WHERE id = :id")
    suspend fun trouver(id: Long): EnfantEntity?

    @Query("SELECT * FROM enfants WHERE id = :id")
    fun observer(id: Long): Flow<EnfantEntity?>

    @Query("SELECT * FROM enfants WHERE isArchived = :archive")
    fun observerSelonArchivage(archive: Boolean): Flow<List<EnfantEntity>>

    /** Tous les enfants, actifs et archivés (export). */
    @Query("SELECT * FROM enfants")
    suspend fun tous(): List<EnfantEntity>

    @Query("SELECT dateDebutSuivi, dateFinSuivi FROM enfants")
    suspend fun plagesDeSuivi(): List<PlageSuivi>

    @Query(
        "SELECT COUNT(*) FROM enfants WHERE id = :id AND dateDebutSuivi <= :dimanche " +
            "AND (dateFinSuivi IS NULL OR dateFinSuivi >= :dimanche)",
    )
    suspend fun compterAdmissible(id: Long, dimanche: LocalDate): Int

    @Query(
        "SELECT COUNT(*) FROM enfants WHERE dateDebutSuivi <= :dimanche " +
            "AND (dateFinSuivi IS NULL OR dateFinSuivi >= :dimanche)",
    )
    suspend fun compterAdmissibles(dimanche: LocalDate): Int

    /** Grille d'un dimanche : seuls les enfants admissibles, avec leur statut éventuel. */
    @Query(
        "SELECT e.*, p.statut AS statut FROM enfants e " +
            "LEFT JOIN presences p ON p.enfantId = e.id AND p.dateDimanche = :dimanche " +
            "WHERE e.dateDebutSuivi <= :dimanche AND (e.dateFinSuivi IS NULL OR e.dateFinSuivi >= :dimanche)",
    )
    fun observerAdmissiblesAvecStatut(dimanche: LocalDate): Flow<List<EnfantAvecStatut>>

    /** Archivage = mise à jour de la fiche : la photo et l'historique sont conservés. */
    @Query(
        "UPDATE enfants SET isArchived = 1, dateFinSuivi = :finSuivi, archivedAt = :maintenant, " +
            "updatedAt = :maintenant WHERE id = :id AND isArchived = 0",
    )
    suspend fun archiver(id: Long, finSuivi: LocalDate, maintenant: Instant): Int

    /**
     * Retire les présences enregistrées d'avance pour des dimanches postérieurs à la fin de suivi
     * (pointage anticipé, puis archivage avant le jour J) : l'enfant n'est plus admissible à ces
     * dimanches, une ligne restante fausserait l'assiduité.
     */
    @Query("DELETE FROM presences WHERE enfantId = :id AND dateDimanche > :finSuivi")
    suspend fun retirerPresencesApres(id: Long, finSuivi: LocalDate): Int

    @Query("UPDATE enfants SET photoPath = :chemin, updatedAt = :maintenant WHERE id = :id")
    suspend fun changerPhoto(id: Long, chemin: String, maintenant: Instant): Int

    @Query("SELECT photoPath FROM enfants")
    suspend fun tousLesCheminsPhoto(): List<String>

    /**
     * Enfants actifs dont le suivi commence APRÈS [dimanche] : sur un dimanche passé, ils sont proposés
     * pour une saisie « avant inscription » (registre papier), qui avance leur début de suivi.
     */
    @Query("SELECT * FROM enfants WHERE isArchived = 0 AND dateDebutSuivi > :dimanche")
    fun observerNonInscritsA(dimanche: LocalDate): Flow<List<EnfantEntity>>

    /** Avance (antidate) le début de suivi d'un enfant. */
    @Query("UPDATE enfants SET dateDebutSuivi = :debut, updatedAt = :maintenant WHERE id = :id")
    suspend fun changerDebutSuivi(id: Long, debut: LocalDate, maintenant: Instant): Int

    /** Désarchivage : l'enfant redevient actif, sans fin de suivi. */
    @Query(
        "UPDATE enfants SET isArchived = 0, dateFinSuivi = NULL, archivedAt = NULL, updatedAt = :maintenant " +
            "WHERE id = :id AND isArchived = 1",
    )
    suspend fun desarchiver(id: Long, maintenant: Instant): Int

    /**
     * Désarchivage : chaque séance DÉJÀ clôturée postérieure à [apres] (et à partir de [debut]) reçoit une
     * ligne [statut] pour l'enfant, comme l'aurait fait la clôture s'il n'avait pas été archivé. Les lignes
     * existantes ne sont jamais remplacées.
     */
    @Query(
        "INSERT OR IGNORE INTO presences (enfantId, dateDimanche, statut, createdAt, updatedAt) " +
            "SELECT :id, dateDimanche, :statut, :maintenant, :maintenant FROM seances " +
            "WHERE cloturee = 1 AND dateDimanche > :apres AND dateDimanche >= :debut",
    )
    suspend fun completerSeancesCloturees(id: Long, apres: LocalDate, debut: LocalDate, statut: StatutPresence, maintenant: Instant)

    /** Suppression définitive, étape 1 : toutes les présences de l'enfant (clé étrangère en RESTRICT). */
    @Query("DELETE FROM presences WHERE enfantId = :id")
    suspend fun supprimerPresences(id: Long): Int

    /** Suppression définitive : ses notes d'examen (clé étrangère en RESTRICT). */
    @Query("DELETE FROM notes WHERE enfantId = :id")
    suspend fun supprimerNotes(id: Long): Int

    /** Suppression définitive, étape 2 : la fiche elle-même. */
    @Query("DELETE FROM enfants WHERE id = :id")
    suspend fun supprimer(id: Long): Int
}

@Dao
interface SeanceDao {
    /** N'écrase jamais une séance existante (retourne -1 si elle existe déjà). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insererSiAbsente(seance: SeanceEntity): Long

    @Query("SELECT * FROM seances WHERE dateDimanche = :dimanche")
    suspend fun trouver(dimanche: LocalDate): SeanceEntity?

    @Query("SELECT * FROM seances WHERE dateDimanche = :dimanche")
    fun observer(dimanche: LocalDate): Flow<SeanceEntity?>

    @Query("SELECT * FROM seances")
    suspend fun toutes(): List<SeanceEntity>

    /** Ne touche qu'une séance encore ouverte : une séance clôturée n'est jamais modifiée. */
    @Query(
        "UPDATE seances SET cloturee = 1, clotureeAt = :maintenant, updatedAt = :maintenant " +
            "WHERE dateDimanche = :dimanche AND cloturee = 0",
    )
    suspend fun marquerCloturee(dimanche: LocalDate, maintenant: Instant): Int
}

@Dao
interface PresenceDao {
    /** N'écrase jamais un statut déjà enregistré. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insererSiAbsente(presence: PresenceEntity): Long

    @Query(
        "UPDATE presences SET statut = :statut, updatedAt = :maintenant " +
            "WHERE enfantId = :enfantId AND dateDimanche = :dimanche",
    )
    suspend fun changerStatut(enfantId: Long, dimanche: LocalDate, statut: StatutPresence, maintenant: Instant): Int

    /**
     * Crée, pour chaque enfant admissible au dimanche, la ligne manquante avec [statut].
     * Les lignes existantes ne sont jamais remplacées (INSERT OR IGNORE sur la clé composite).
     */
    @Query(
        "INSERT OR IGNORE INTO presences (enfantId, dateDimanche, statut, createdAt, updatedAt) " +
            "SELECT id, :dimanche, :statut, :maintenant, :maintenant FROM enfants " +
            "WHERE dateDebutSuivi <= :dimanche AND (dateFinSuivi IS NULL OR dateFinSuivi >= :dimanche)",
    )
    suspend fun creerManquantesPourAdmissibles(dimanche: LocalDate, statut: StatutPresence, maintenant: Instant)

    /** Convertit uniquement les lignes du dimanche qui ont le statut [ancien]. */
    @Query(
        "UPDATE presences SET statut = :nouveau, updatedAt = :maintenant " +
            "WHERE dateDimanche = :dimanche AND statut = :ancien",
    )
    suspend fun remplacerStatut(dimanche: LocalDate, ancien: StatutPresence, nouveau: StatutPresence, maintenant: Instant): Int

    @Query("SELECT * FROM presences WHERE dateDimanche = :dimanche")
    suspend fun duDimanche(dimanche: LocalDate): List<PresenceEntity>

    /** Toutes les présences enregistrées (export). */
    @Query("SELECT * FROM presences")
    suspend fun toutes(): List<PresenceEntity>

    @Query("SELECT * FROM presences WHERE enfantId = :enfantId AND dateDimanche = :dimanche")
    suspend fun trouver(enfantId: Long, dimanche: LocalDate): PresenceEntity?

    /**
     * Compteurs par enfant et par statut, sur les seules séances CLÔTURÉES où au moins un enfant a été
     * pointé (Présent ou En retard) : un dimanche où personne n'a été pointé n'est pas compté.
     */
    @Query(
        "SELECT p.enfantId AS enfantId, p.statut AS statut, COUNT(*) AS nombre FROM presences p " +
            "INNER JOIN seances s ON s.dateDimanche = p.dateDimanche " +
            "WHERE s.cloturee = 1 AND " + DIMANCHE_POINTE + " GROUP BY p.enfantId, p.statut",
    )
    fun observerCompteurs(): Flow<List<CompteurStatutLigne>>

    @Query(
        "SELECT p.dateDimanche AS dateDimanche, p.statut AS statut, s.cloturee AS cloturee, " +
            DIMANCHE_POINTE + " AS pointee FROM presences p " +
            "INNER JOIN seances s ON s.dateDimanche = p.dateDimanche WHERE p.enfantId = :enfantId " +
            "ORDER BY p.dateDimanche DESC",
    )
    fun observerHistorique(enfantId: Long): Flow<List<LigneHistorique>>
}

/** Paramètres de fonctionnement (table PARAMETRES, version 2 de la base). */
@Dao
interface ParametreDao {
    @Query("SELECT valeur FROM parametres WHERE cle = :cle")
    suspend fun lire(cle: String): String?

    @Query("SELECT valeur FROM parametres WHERE cle = :cle")
    fun observer(cle: String): Flow<String?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun ecrire(parametre: ParametreEntity)

    @Query("DELETE FROM parametres WHERE cle = :cle")
    suspend fun effacer(cle: String)

    @Query("SELECT * FROM parametres")
    suspend fun tous(): List<ParametreEntity>
}

/**
 * Opérations sur TOUTE la base : sauvegarde complète, restauration et « Repartir de zéro ».
 * L'ordre compte à cause des clés étrangères en RESTRICT : on vide présences → séances → enfants,
 * et on remplit enfants → séances → présences. À n'appeler que dans une transaction.
 */
@Dao
interface MaintenanceDao {
    @Query("SELECT * FROM enfants ORDER BY id")
    suspend fun enfants(): List<EnfantEntity>

    @Query("SELECT * FROM seances ORDER BY dateDimanche")
    suspend fun seances(): List<SeanceEntity>

    @Query("SELECT * FROM presences ORDER BY enfantId, dateDimanche")
    suspend fun presences(): List<PresenceEntity>

    @Query("SELECT * FROM examens ORDER BY id")
    suspend fun examens(): List<ExamenEntity>

    @Query("SELECT * FROM notes ORDER BY examenId, enfantId")
    suspend fun notes(): List<NoteEntity>

    @Query("SELECT COUNT(*) FROM enfants")
    suspend fun nombreEnfants(): Int

    @Query("SELECT COUNT(*) FROM seances")
    suspend fun nombreSeances(): Int

    @Query("DELETE FROM notes")
    suspend fun viderNotes()

    @Query("DELETE FROM examens")
    suspend fun viderExamens()

    @Query("DELETE FROM presences")
    suspend fun viderPresences()

    @Query("DELETE FROM seances")
    suspend fun viderSeances()

    @Query("DELETE FROM enfants")
    suspend fun viderEnfants()

    /** Les identifiants des enfants sont conservés tels quels (colonne « Identifiant » des CSV). */
    @Insert
    suspend fun insererEnfants(enfants: List<EnfantEntity>)

    @Insert
    suspend fun insererSeances(seances: List<SeanceEntity>)

    @Insert
    suspend fun insererPresences(presences: List<PresenceEntity>)

    @Insert
    suspend fun insererExamens(examens: List<ExamenEntity>)

    @Insert
    suspend fun insererNotes(notes: List<NoteEntity>)
}

/** Examens et notes (tables EXAMENS et NOTES, version 3 de la base). */
@Dao
interface ExamenDao {
    @Query("SELECT * FROM examens ORDER BY date, id")
    fun observerExamens(): Flow<List<ExamenEntity>>

    @Query("SELECT * FROM notes")
    fun observerNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM examens ORDER BY date, id")
    suspend fun tous(): List<ExamenEntity>

    @Query("SELECT * FROM notes")
    suspend fun toutesLesNotes(): List<NoteEntity>

    @Query("SELECT * FROM examens WHERE id = :id")
    suspend fun trouver(id: Long): ExamenEntity?

    @Query("SELECT * FROM notes WHERE examenId = :examenId")
    suspend fun notesDe(examenId: Long): List<NoteEntity>

    @Insert
    suspend fun inserer(examen: ExamenEntity): Long

    @Update
    suspend fun mettreAJour(examen: ExamenEntity)

    /** Enregistre ou remplace la note d'un enfant à un examen. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun ecrireNote(note: NoteEntity)

    @Query("DELETE FROM notes WHERE examenId = :examenId AND enfantId = :enfantId")
    suspend fun effacerNote(examenId: Long, enfantId: Long)

    @Query("DELETE FROM notes WHERE examenId = :examenId")
    suspend fun supprimerNotesDe(examenId: Long)

    @Query("DELETE FROM examens WHERE id = :id")
    suspend fun supprimer(id: Long): Int
}

