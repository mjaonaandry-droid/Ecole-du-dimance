package mg.ecoledimanche.presences.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant
import java.time.LocalDate
import mg.ecoledimanche.presences.domain.EtatSeance
import mg.ecoledimanche.presences.domain.Sexe
import mg.ecoledimanche.presences.domain.StatutPresence

/**
 * Table ENFANTS. L'âge n'est pas stocké (calculé depuis [dateNaissance]) et la photo n'est pas
 * un BLOB : [photoPath] est un chemin RELATIF au répertoire privé de l'application.
 *
 * La photo est facultative : tant qu'elle n'a pas été prise, [photoPath] vaut [PHOTO_NON_PRISE] (chaîne
 * vide) et l'application affiche une silhouette grisée selon le [sexe]. Une chaîne vide plutôt qu'une
 * colonne nullable évite une migration du schéma Room (les données existantes restent valides).
 *
 * [dateDebutSuivi] et [dateFinSuivi] sont des données techniques, distinctes de
 * [dateArriveeEglise] (information de la fiche). [dateFinSuivi] est une borne inclusive.
 */
@Entity(
    tableName = "enfants",
    indices = [
        Index(value = ["isArchived"]),
        Index(value = ["dateDebutSuivi"]),
        Index(value = ["nom", "prenom"]),
    ],
)
data class EnfantEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nom: String,
    val prenom: String,
    val dateNaissance: LocalDate,
    val sexe: Sexe,
    val adresse: String?,
    val nomPere: String?,
    /** Nul = information non renseignée (différent de « Non »). */
    val pereMembre: Boolean?,
    val nomMere: String?,
    val mereMembre: Boolean?,
    val nombreFreresSoeurs: Int,
    val nombreFreresSoeursMembres: Int,
    val photoPath: String,
    val dateArriveeEglise: LocalDate?,
    val dateDebutSuivi: LocalDate,
    val dateFinSuivi: LocalDate?,
    val isArchived: Boolean,
    val archivedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Valeur de [EnfantEntity.photoPath] tant que la photo n'a pas été prise. */
const val PHOTO_NON_PRISE = ""

/** Vrai si une photo a été prise pour cet enfant (le fichier peut néanmoins avoir disparu). */
val EnfantEntity.aUnePhoto: Boolean get() = photoPath.isNotBlank()

/**
 * Table SEANCES : une ligne par dimanche matérialisé. La date civile est la clé primaire ;
 * le fuseau et l'instant de clôture prévus sont figés à la création, pour qu'un changement
 * de fuseau ou d'horloge ne déplace ni ne rouvre une séance.
 */
@Entity(tableName = "seances")
data class SeanceEntity(
    @PrimaryKey val dateDimanche: LocalDate,
    val zoneId: String,
    val cloturePrevueAt: Instant,
    val cloturee: Boolean,
    val clotureeAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    fun versEtat(): EtatSeance = EtatSeance(cloturee = cloturee, cloturePrevueAt = cloturePrevueAt)
}

/**
 * Table PRESENCES : clé primaire composite (enfantId, dateDimanche), donc une seule présence
 * par enfant et par dimanche. Les clés étrangères en RESTRICT interdisent de supprimer un
 * enfant ou une séance qui a un historique ; l'archivage est une simple mise à jour.
 */
@Entity(
    tableName = "presences",
    primaryKeys = ["enfantId", "dateDimanche"],
    foreignKeys = [
        ForeignKey(
            entity = EnfantEntity::class,
            parentColumns = ["id"],
            childColumns = ["enfantId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = SeanceEntity::class,
            parentColumns = ["dateDimanche"],
            childColumns = ["dateDimanche"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["dateDimanche"]),
        Index(value = ["enfantId", "statut"]),
    ],
)
data class PresenceEntity(
    val enfantId: Long,
    val dateDimanche: LocalDate,
    val statut: StatutPresence,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Table PARAMETRES (version 2 de la base) : petites valeurs de fonctionnement, clé → texte.
 * Elle fait partie de la base, donc de la sauvegarde complète. Clés connues : [CleParametre].
 */
@Entity(tableName = "parametres")
data class ParametreEntity(
    @PrimaryKey val cle: String,
    val valeur: String,
)

/** Clés de la table PARAMETRES. */
object CleParametre {
    /** « 1 » quand l'écran d'accueil (commencer vide ou restaurer une sauvegarde) a été passé. */
    const val ACCUEIL_FAIT = "accueil.fait"

    /** Instant le plus récent vu sur l'horloge du téléphone (millisecondes Unix) : garde-fou de date. */
    const val HORLOGE_DERNIERE = "horloge.derniere"
}

/**
 * Table EXAMENS (version 3 de la base). Toutes les notes sont sur 20 ; un enfant actif sans note à un
 * examen compte 0 dans son cumul (voir `domain/Notes.kt`).
 */
@Entity(tableName = "examens")
data class ExamenEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val intitule: String,
    val date: LocalDate,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/**
 * Table NOTES (version 3 de la base) : une note par enfant et par examen, en CENTIÈMES de point
 * (1250 = 12,5 / 20) pour éviter les arrondis des nombres à virgule. Pas de ligne = pas de note.
 * Clés étrangères en RESTRICT, comme les présences : supprimer un examen ou un enfant supprime
 * d'abord ses notes, explicitement, dans la même transaction.
 */
@Entity(
    tableName = "notes",
    primaryKeys = ["examenId", "enfantId"],
    foreignKeys = [
        ForeignKey(
            entity = ExamenEntity::class,
            parentColumns = ["id"],
            childColumns = ["examenId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = EnfantEntity::class,
            parentColumns = ["id"],
            childColumns = ["enfantId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["enfantId"])],
)
data class NoteEntity(
    val examenId: Long,
    val enfantId: Long,
    val centiemes: Int,
    val createdAt: Instant,
    val updatedAt: Instant,
)
