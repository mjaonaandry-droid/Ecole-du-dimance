package mg.ecoledimanche.presences.data.sauvegarde

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import mg.ecoledimanche.presences.data.local.EnfantEntity
import mg.ecoledimanche.presences.data.local.ExamenEntity
import mg.ecoledimanche.presences.data.local.NoteEntity
import mg.ecoledimanche.presences.data.local.PresenceEntity
import mg.ecoledimanche.presences.data.local.SeanceEntity
import mg.ecoledimanche.presences.domain.Notes
import mg.ecoledimanche.presences.domain.Sexe
import mg.ecoledimanche.presences.domain.StatutPresence

/** Ce qu'annonce une sauvegarde (fichier `sauvegarde.json` de l'archive). */
data class ManifesteSauvegarde(
    val format: Int,
    val versionApplication: String,
    /** Version du schéma de la base de l'application qui a créé la sauvegarde (information). */
    val versionBase: Int,
    val creeeLe: Instant,
    val enfants: Int,
    val seances: Int,
    val presences: Int,
    val photos: Int,
    /** Depuis le format 2. */
    val examens: Int = 0,
)

/** Toutes les données de la base qui sont sauvegardées. */
data class ContenuSauvegarde(
    val enfants: List<EnfantEntity>,
    val seances: List<SeanceEntity>,
    val presences: List<PresenceEntity>,
    /** Depuis le format 2 (application 1.3.0) ; vides dans une sauvegarde plus ancienne. */
    val examens: List<ExamenEntity> = emptyList(),
    val notes: List<NoteEntity> = emptyList(),
)

enum class ProblemeSauvegarde {
    /** Ce n'est pas une sauvegarde de cette application (autre fichier .zip, fichier quelconque). */
    PAS_UNE_SAUVEGARDE,

    /** Sauvegarde créée par une version plus récente de l'application : il faut d'abord mettre l'application à jour. */
    VERSION_TROP_RECENTE,

    /** Archive abîmée ou incomplète. */
    ILLISIBLE,

    /** Données contradictoires (présence d'un enfant inconnu, identifiant en double…). */
    INCOHERENTE,

    TROP_VOLUMINEUSE,
}

class SauvegardeInvalideException(val probleme: ProblemeSauvegarde, detail: String? = null) :
    Exception("${probleme.name}${detail?.let { " : $it" }.orEmpty()}")

/** Sauvegarde lue et vérifiée, prête à être restaurée. */
data class SauvegardeLue(
    val manifeste: ManifesteSauvegarde,
    val contenu: ContenuSauvegarde,
    /** Chemins des photos présentes dans l'archive (« photos/xxx.jpg »). */
    val photos: Set<String>,
)

/**
 * Format de la sauvegarde complète : une archive .zip contenant
 * - `sauvegarde.json` : le manifeste (format, version de l'application et de la base, date, nombres) ;
 * - `donnees.json` : toutes les lignes des tables enfants, seances, presences, examens et notes ;
 * - `photos/…` : les photos des enfants, sous le même chemin relatif que dans l'application.
 *
 * Le contenu est LOGIQUE (des valeurs, pas le fichier SQLite) : une sauvegarde faite avec une ancienne
 * version de l'application se restaure dans une version plus récente, quelle que soit l'évolution du
 * schéma Room entre-temps. [FORMAT] augmente quand `donnees.json` gagne des données qu'une version plus
 * ancienne perdrait sans le dire ; la lecture accepte toujours les formats précédents.
 * - format 1 (application 1.2.0) : enfants, séances, présences ;
 * - format 2 (application 1.3.0) : + examens et notes. Une 1.2.0 refuse une sauvegarde au format 2
 *   (« version plus récente ») au lieu de perdre les notes.
 */
object FormatSauvegarde {
    const val FORMAT = 2
    const val APPLICATION = "mg.ecoledimanche.presences"
    const val ENTREE_MANIFESTE = "sauvegarde.json"
    const val ENTREE_DONNEES = "donnees.json"
    private const val PREFIXE_PHOTOS = "photos/"

    /** Garde-fous mémoire : bien au-delà d'une école du dimanche. */
    const val TAILLE_MAX_DONNEES = 50L * 1024 * 1024
    const val TAILLE_MAX_PHOTO = 20L * 1024 * 1024
    const val TAILLE_MAX_TOTALE = 1024L * 1024 * 1024

    private val nomPhotoValide = Regex("photos/[A-Za-z0-9._-]{1,200}")

    /** Nom proposé : « ecole-du-dimanche-sauvegarde-2026-11-16.zip ». */
    fun nomFichier(aujourdhui: LocalDate): String = "ecole-du-dimanche-sauvegarde-$aujourdhui.zip"

    /** Vrai pour un chemin de photo sûr (« photos/xxx.jpg », sans dossier ni « .. »). */
    fun cheminPhotoValide(chemin: String): Boolean = nomPhotoValide.matches(chemin) && ".." !in chemin

    // ---------------------------------------------------------------------------------------------
    // Écriture
    // ---------------------------------------------------------------------------------------------

    /**
     * Écrit l'archive dans [sortie] (qui n'est pas fermée). Une photo référencée mais introuvable
     * ([lirePhoto] rend nul) n'est pas incluse ; la fiche la référence quand même (silhouette à la restauration).
     */
    fun ecrire(
        sortie: OutputStream,
        contenu: ContenuSauvegarde,
        versionApplication: String,
        versionBase: Int,
        maintenant: Instant,
        lirePhoto: (String) -> InputStream?,
    ): ManifesteSauvegarde {
        val zip = ZipOutputStream(sortie)
        val photosEcrites = LinkedHashSet<String>()

        zip.putNextEntry(ZipEntry(ENTREE_DONNEES))
        zip.write(Json.ecrire(versJson(contenu)).toByteArray(Charsets.UTF_8))
        zip.closeEntry()

        for (chemin in contenu.enfants.map { it.photoPath }.filter { cheminPhotoValide(it) }.toSortedSet()) {
            val flux = lirePhoto(chemin) ?: continue
            flux.use {
                zip.putNextEntry(ZipEntry(chemin))
                it.copyTo(zip)
                zip.closeEntry()
            }
            photosEcrites += chemin
        }

        val manifeste = ManifesteSauvegarde(
            format = FORMAT,
            versionApplication = versionApplication,
            versionBase = versionBase,
            creeeLe = maintenant,
            enfants = contenu.enfants.size,
            seances = contenu.seances.size,
            presences = contenu.presences.size,
            photos = photosEcrites.size,
            examens = contenu.examens.size,
        )
        zip.putNextEntry(ZipEntry(ENTREE_MANIFESTE))
        zip.write(Json.ecrire(manifesteVersJson(manifeste)).toByteArray(Charsets.UTF_8))
        zip.closeEntry()
        zip.finish()
        return manifeste
    }

    // ---------------------------------------------------------------------------------------------
    // Lecture
    // ---------------------------------------------------------------------------------------------

    /**
     * Lit et VÉRIFIE toute l'archive sans rien écrire : manifeste, données, cohérence, noms des photos.
     * Le contenu des photos n'est pas gardé en mémoire (voir [extrairePhotos]).
     * @throws SauvegardeInvalideException si l'archive ne peut pas être restaurée.
     */
    fun lire(entree: InputStream): SauvegardeLue {
        var manifeste: Map<String, Any?>? = null
        var donnees: Map<String, Any?>? = null
        val photos = LinkedHashSet<String>()
        var total = 0L
        try {
            val zip = ZipInputStream(entree)
            while (true) {
                val element = zip.nextEntry ?: break
                when {
                    element.isDirectory -> Unit
                    element.name == ENTREE_MANIFESTE -> manifeste = objet(Json.lire(lireTexte(zip, TAILLE_MAX_DONNEES)))
                    element.name == ENTREE_DONNEES -> donnees = objet(Json.lire(lireTexte(zip, TAILLE_MAX_DONNEES)))
                    cheminPhotoValide(element.name) -> {
                        val taille = compter(zip, TAILLE_MAX_PHOTO)
                        total += taille
                        if (taille > 0) photos += element.name
                    }
                    else -> total += compter(zip, TAILLE_MAX_PHOTO) // entrée inconnue : ignorée
                }
                if (total > TAILLE_MAX_TOTALE) throw SauvegardeInvalideException(ProblemeSauvegarde.TROP_VOLUMINEUSE)
            }
        } catch (erreur: ZipException) {
            throw SauvegardeInvalideException(ProblemeSauvegarde.ILLISIBLE, erreur.message)
        } catch (erreur: FormatInvalideException) {
            throw SauvegardeInvalideException(ProblemeSauvegarde.ILLISIBLE, erreur.message)
        }

        val m = manifeste ?: throw SauvegardeInvalideException(ProblemeSauvegarde.PAS_UNE_SAUVEGARDE)
        if (m["application"] != APPLICATION) throw SauvegardeInvalideException(ProblemeSauvegarde.PAS_UNE_SAUVEGARDE)
        val format = (m["format"] as? Long)?.toInt() ?: throw SauvegardeInvalideException(ProblemeSauvegarde.PAS_UNE_SAUVEGARDE)
        if (format > FORMAT) throw SauvegardeInvalideException(ProblemeSauvegarde.VERSION_TROP_RECENTE)
        val d = donnees ?: throw SauvegardeInvalideException(ProblemeSauvegarde.ILLISIBLE, "$ENTREE_DONNEES absent")

        val contenu = try {
            depuisJson(d)
        } catch (erreur: FormatInvalideException) {
            throw SauvegardeInvalideException(ProblemeSauvegarde.ILLISIBLE, erreur.message)
        }
        verifierCoherence(contenu)
        val lu = try {
            manifesteDepuisJson(m)
        } catch (erreur: FormatInvalideException) {
            throw SauvegardeInvalideException(ProblemeSauvegarde.ILLISIBLE, erreur.message)
        }
        return SauvegardeLue(lu, contenu, photos)
    }

    /** Relit l'archive et confie chaque photo voulue à [ecrire] (chemin relatif, flux du contenu). */
    fun extrairePhotos(entree: InputStream, voulues: Set<String>, ecrire: (String, InputStream) -> Unit) {
        val zip = ZipInputStream(entree)
        while (true) {
            val element = zip.nextEntry ?: break
            if (!element.isDirectory && element.name in voulues && cheminPhotoValide(element.name)) {
                ecrire(element.name, FluxBorne(zip, TAILLE_MAX_PHOTO))
            }
        }
    }

    private fun verifierCoherence(c: ContenuSauvegarde) {
        fun incoherente(detail: String): Nothing = throw SauvegardeInvalideException(ProblemeSauvegarde.INCOHERENTE, detail)
        val ids = HashSet<Long>()
        for (e in c.enfants) {
            if (e.id <= 0 || !ids.add(e.id)) incoherente("identifiant d'enfant ${e.id}")
            if (e.photoPath.isNotEmpty() && !cheminPhotoValide(e.photoPath)) incoherente("chemin de photo ${e.photoPath}")
        }
        val dates = HashSet<LocalDate>()
        for (s in c.seances) if (!dates.add(s.dateDimanche)) incoherente("séance ${s.dateDimanche} en double")
        val cles = HashSet<Pair<Long, LocalDate>>()
        for (p in c.presences) {
            if (p.enfantId !in ids || p.dateDimanche !in dates) incoherente("présence ${p.enfantId} ${p.dateDimanche}")
            if (!cles.add(p.enfantId to p.dateDimanche)) incoherente("présence ${p.enfantId} ${p.dateDimanche} en double")
        }
        val examens = HashSet<Long>()
        for (x in c.examens) if (x.id <= 0 || !examens.add(x.id)) incoherente("identifiant d'examen ${x.id}")
        val notes = HashSet<Pair<Long, Long>>()
        for (n in c.notes) {
            if (n.examenId !in examens || n.enfantId !in ids) incoherente("note ${n.examenId} ${n.enfantId}")
            if (n.centiemes !in 0..Notes.MAX_CENTIEMES) incoherente("note ${n.centiemes} hors barème")
            if (!notes.add(n.examenId to n.enfantId)) incoherente("note ${n.examenId} ${n.enfantId} en double")
        }
    }

    private fun lireTexte(flux: InputStream, limite: Long): String {
        val sortie = ByteArrayOutputStream()
        val tampon = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val lus = flux.read(tampon)
            if (lus < 0) break
            total += lus
            if (total > limite) throw SauvegardeInvalideException(ProblemeSauvegarde.TROP_VOLUMINEUSE)
            sortie.write(tampon, 0, lus)
        }
        return sortie.toString(Charsets.UTF_8.name())
    }

    private fun compter(flux: InputStream, limite: Long): Long {
        val tampon = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val lus = flux.read(tampon)
            if (lus < 0) return total
            total += lus
            if (total > limite) throw SauvegardeInvalideException(ProblemeSauvegarde.TROP_VOLUMINEUSE)
        }
    }

    /** Flux de l'entrée courante d'une archive, limité en taille, et que l'appelant ne doit pas fermer. */
    private class FluxBorne(private val source: InputStream, private val limite: Long) : InputStream() {
        private var lus = 0L

        override fun read(): Int {
            val c = source.read()
            if (c >= 0 && ++lus > limite) throw SauvegardeInvalideException(ProblemeSauvegarde.TROP_VOLUMINEUSE)
            return c
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = source.read(b, off, len)
            if (n > 0) {
                lus += n
                if (lus > limite) throw SauvegardeInvalideException(ProblemeSauvegarde.TROP_VOLUMINEUSE)
            }
            return n
        }

        override fun close() = Unit
    }

    // ---------------------------------------------------------------------------------------------
    // Conversions JSON
    // ---------------------------------------------------------------------------------------------

    private fun manifesteVersJson(m: ManifesteSauvegarde): Map<String, Any?> = linkedMapOf(
        "application" to APPLICATION,
        "format" to m.format,
        "versionApplication" to m.versionApplication,
        "versionBase" to m.versionBase,
        "creeeLe" to m.creeeLe.toString(),
        "enfants" to m.enfants,
        "seances" to m.seances,
        "presences" to m.presences,
        "photos" to m.photos,
        "examens" to m.examens,
    )

    private fun manifesteDepuisJson(m: Map<String, Any?>): ManifesteSauvegarde = ManifesteSauvegarde(
        format = entier(m, "format").toInt(),
        versionApplication = m["versionApplication"] as? String ?: "?",
        versionBase = (m["versionBase"] as? Long)?.toInt() ?: 0,
        creeeLe = instant(m, "creeeLe"),
        enfants = (m["enfants"] as? Long)?.toInt() ?: 0,
        seances = (m["seances"] as? Long)?.toInt() ?: 0,
        presences = (m["presences"] as? Long)?.toInt() ?: 0,
        photos = (m["photos"] as? Long)?.toInt() ?: 0,
        examens = (m["examens"] as? Long)?.toInt() ?: 0,
    )

    internal fun versJson(c: ContenuSauvegarde): Map<String, Any?> = linkedMapOf(
        "enfants" to c.enfants.map { e ->
            linkedMapOf(
                "id" to e.id,
                "nom" to e.nom,
                "prenom" to e.prenom,
                "dateNaissance" to e.dateNaissance.toString(),
                "sexe" to e.sexe.name,
                "adresse" to e.adresse,
                "nomPere" to e.nomPere,
                "pereMembre" to e.pereMembre,
                "nomMere" to e.nomMere,
                "mereMembre" to e.mereMembre,
                "nombreFreresSoeurs" to e.nombreFreresSoeurs,
                "nombreFreresSoeursMembres" to e.nombreFreresSoeursMembres,
                "photoPath" to e.photoPath,
                "dateArriveeEglise" to e.dateArriveeEglise?.toString(),
                "dateDebutSuivi" to e.dateDebutSuivi.toString(),
                "dateFinSuivi" to e.dateFinSuivi?.toString(),
                "isArchived" to e.isArchived,
                "archivedAt" to e.archivedAt?.toEpochMilli(),
                "createdAt" to e.createdAt.toEpochMilli(),
                "updatedAt" to e.updatedAt.toEpochMilli(),
            )
        },
        "seances" to c.seances.map { s ->
            linkedMapOf(
                "dateDimanche" to s.dateDimanche.toString(),
                "zoneId" to s.zoneId,
                "cloturePrevueAt" to s.cloturePrevueAt.toEpochMilli(),
                "cloturee" to s.cloturee,
                "clotureeAt" to s.clotureeAt?.toEpochMilli(),
                "createdAt" to s.createdAt.toEpochMilli(),
                "updatedAt" to s.updatedAt.toEpochMilli(),
            )
        },
        "presences" to c.presences.map { p ->
            linkedMapOf(
                "enfantId" to p.enfantId,
                "dateDimanche" to p.dateDimanche.toString(),
                "statut" to p.statut.name,
                "createdAt" to p.createdAt.toEpochMilli(),
                "updatedAt" to p.updatedAt.toEpochMilli(),
            )
        },
        "examens" to c.examens.map { x ->
            linkedMapOf(
                "id" to x.id,
                "intitule" to x.intitule,
                "date" to x.date.toString(),
                "createdAt" to x.createdAt.toEpochMilli(),
                "updatedAt" to x.updatedAt.toEpochMilli(),
            )
        },
        "notes" to c.notes.map { n ->
            linkedMapOf(
                "examenId" to n.examenId,
                "enfantId" to n.enfantId,
                "centiemes" to n.centiemes,
                "createdAt" to n.createdAt.toEpochMilli(),
                "updatedAt" to n.updatedAt.toEpochMilli(),
            )
        },
    )

    internal fun depuisJson(d: Map<String, Any?>): ContenuSauvegarde = ContenuSauvegarde(
        enfants = liste(d, "enfants").map { o ->
            val e = objet(o)
            EnfantEntity(
                id = entier(e, "id"),
                nom = texte(e, "nom"),
                prenom = texte(e, "prenom"),
                dateNaissance = date(e, "dateNaissance"),
                sexe = enumeration<Sexe>(e, "sexe"),
                adresse = texteOuNul(e, "adresse"),
                nomPere = texteOuNul(e, "nomPere"),
                pereMembre = e["pereMembre"] as? Boolean,
                nomMere = texteOuNul(e, "nomMere"),
                mereMembre = e["mereMembre"] as? Boolean,
                nombreFreresSoeurs = entier(e, "nombreFreresSoeurs").toInt(),
                nombreFreresSoeursMembres = entier(e, "nombreFreresSoeursMembres").toInt(),
                photoPath = texteOuNul(e, "photoPath").orEmpty(),
                dateArriveeEglise = dateOuNul(e, "dateArriveeEglise"),
                dateDebutSuivi = date(e, "dateDebutSuivi"),
                dateFinSuivi = dateOuNul(e, "dateFinSuivi"),
                isArchived = e["isArchived"] as? Boolean ?: false,
                archivedAt = instantOuNul(e, "archivedAt"),
                createdAt = instant(e, "createdAt"),
                updatedAt = instant(e, "updatedAt"),
            )
        },
        seances = liste(d, "seances").map { o ->
            val s = objet(o)
            SeanceEntity(
                dateDimanche = date(s, "dateDimanche"),
                zoneId = texte(s, "zoneId"),
                cloturePrevueAt = instant(s, "cloturePrevueAt"),
                cloturee = s["cloturee"] as? Boolean ?: false,
                clotureeAt = instantOuNul(s, "clotureeAt"),
                createdAt = instant(s, "createdAt"),
                updatedAt = instant(s, "updatedAt"),
            )
        },
        presences = liste(d, "presences").map { o ->
            val p = objet(o)
            PresenceEntity(
                enfantId = entier(p, "enfantId"),
                dateDimanche = date(p, "dateDimanche"),
                statut = enumeration<StatutPresence>(p, "statut"),
                createdAt = instant(p, "createdAt"),
                updatedAt = instant(p, "updatedAt"),
            )
        },
        // Absents d'une sauvegarde au format 1 : listes vides.
        examens = liste(d, "examens").map { o ->
            val x = objet(o)
            ExamenEntity(
                id = entier(x, "id"),
                intitule = texte(x, "intitule"),
                date = date(x, "date"),
                createdAt = instant(x, "createdAt"),
                updatedAt = instant(x, "updatedAt"),
            )
        },
        notes = liste(d, "notes").map { o ->
            val n = objet(o)
            NoteEntity(
                examenId = entier(n, "examenId"),
                enfantId = entier(n, "enfantId"),
                centiemes = entier(n, "centiemes").toInt(),
                createdAt = instant(n, "createdAt"),
                updatedAt = instant(n, "updatedAt"),
            )
        },
    )

    @Suppress("UNCHECKED_CAST")
    private fun objet(valeur: Any?): Map<String, Any?> =
        valeur as? Map<String, Any?> ?: throw FormatInvalideException("Objet attendu")

    private fun liste(o: Map<String, Any?>, cle: String): List<Any?> = o[cle] as? List<Any?> ?: emptyList()

    private fun texte(o: Map<String, Any?>, cle: String): String = o[cle] as? String ?: throw FormatInvalideException("« $cle » manquant")

    private fun texteOuNul(o: Map<String, Any?>, cle: String): String? = o[cle] as? String

    private fun entier(o: Map<String, Any?>, cle: String): Long = o[cle] as? Long ?: throw FormatInvalideException("« $cle » manquant")

    private fun date(o: Map<String, Any?>, cle: String): LocalDate = dateOuNul(o, cle) ?: throw FormatInvalideException("« $cle » manquant")

    private fun dateOuNul(o: Map<String, Any?>, cle: String): LocalDate? = (o[cle] as? String)?.let {
        try {
            LocalDate.parse(it)
        } catch (erreur: DateTimeException) {
            throw FormatInvalideException("Date « $cle » invalide")
        }
    }

    private fun instant(o: Map<String, Any?>, cle: String): Instant =
        instantOuNul(o, cle) ?: throw FormatInvalideException("« $cle » manquant")

    /** Millisecondes Unix, ou texte ISO-8601 (manifeste). */
    private fun instantOuNul(o: Map<String, Any?>, cle: String): Instant? = when (val v = o[cle]) {
        is Long -> Instant.ofEpochMilli(v)
        is String -> try {
            Instant.parse(v)
        } catch (erreur: DateTimeException) {
            throw FormatInvalideException("Instant « $cle » invalide")
        }
        else -> null
    }

    private inline fun <reified E : Enum<E>> enumeration(o: Map<String, Any?>, cle: String): E {
        val nom = texte(o, cle)
        return enumValues<E>().firstOrNull { it.name == nom } ?: throw FormatInvalideException("Valeur « $nom » inconnue pour « $cle »")
    }
}
