package mg.ecoledimanche.presences.data

import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import mg.ecoledimanche.presences.data.local.CompteurStatutLigne
import mg.ecoledimanche.presences.data.local.EnfantAvecStatut
import mg.ecoledimanche.presences.data.local.EnfantDao
import mg.ecoledimanche.presences.data.local.EnfantEntity
import mg.ecoledimanche.presences.data.local.ExamenDao
import mg.ecoledimanche.presences.data.local.ExamenEntity
import mg.ecoledimanche.presences.data.local.NoteEntity
import mg.ecoledimanche.presences.data.local.LigneHistorique
import mg.ecoledimanche.presences.data.local.MaintenanceDao
import mg.ecoledimanche.presences.data.local.ParametreDao
import mg.ecoledimanche.presences.data.local.ParametreEntity
import mg.ecoledimanche.presences.data.local.PlageSuivi
import mg.ecoledimanche.presences.data.local.PresenceDao
import mg.ecoledimanche.presences.data.local.PresenceEntity
import mg.ecoledimanche.presences.data.local.SeanceDao
import mg.ecoledimanche.presences.data.local.SeanceEntity
import mg.ecoledimanche.presences.data.repository.ExecuteurTransaction
import mg.ecoledimanche.presences.data.repository.FichiersPhotos
import mg.ecoledimanche.presences.data.repository.StockagePhotos
import mg.ecoledimanche.presences.domain.AppClock
import mg.ecoledimanche.presences.domain.StatutPresence

/** Horloge contrôlée : on la positionne sur un dimanche à 09 h 59 ou 10 h 00 sans attendre. */
class HorlogeTest(
    var maintenant: Instant,
    var fuseau: ZoneId = ZoneId.of("Indian/Antananarivo"),
) : AppClock {
    override fun instant(): Instant = maintenant
    override fun zone(): ZoneId = fuseau
}

/**
 * Base en mémoire qui reproduit le comportement de Room utile aux règles métier :
 * clés primaires, INSERT OR IGNORE, clés étrangères en RESTRICT, et transactions qui
 * s'annulent en bloc en cas d'exception.
 */
class FauxMagasin {
    val enfants = LinkedHashMap<Long, EnfantEntity>()
    val seances = LinkedHashMap<LocalDate, SeanceEntity>()
    val presences = LinkedHashMap<Pair<Long, LocalDate>, PresenceEntity>()
    val parametres = LinkedHashMap<String, String>()
    val examens = LinkedHashMap<Long, ExamenEntity>()
    val notes = LinkedHashMap<Pair<Long, Long>, NoteEntity>()
    private var sequenceExamens = 0L

    /** Si vrai, `marquerCloturee` échoue : sert à vérifier l'atomicité de la clôture. */
    var echecSurMarquerCloturee = false

    /** Si vrai, l'insertion d'un enfant échoue. */
    var echecSurInsertionEnfant = false

    /** Si vrai, la mise à jour de la référence de photo échoue (après la copie du fichier). */
    var echecSurChangerPhoto = false

    private var sequence = 0L
    private val invalidation = MutableStateFlow(0)

    private fun notifier() {
        invalidation.value = invalidation.value + 1
    }

    private fun <T> flux(calcul: () -> T): Flow<T> = invalidation.map { calcul() }

    private fun admissible(e: EnfantEntity, dimanche: LocalDate): Boolean =
        e.dateDebutSuivi <= dimanche && (e.dateFinSuivi == null || e.dateFinSuivi >= dimanche)

    // --- Instantanés pour les transactions -------------------------------------------------------

    class Instantane(
        val enfants: Map<Long, EnfantEntity>,
        val seances: Map<LocalDate, SeanceEntity>,
        val presences: Map<Pair<Long, LocalDate>, PresenceEntity>,
        val parametres: Map<String, String>,
        val examens: Map<Long, ExamenEntity>,
        val notes: Map<Pair<Long, Long>, NoteEntity>,
        val sequence: Long,
        val sequenceExamens: Long,
    )

    fun capturer() = Instantane(
        LinkedHashMap(enfants), LinkedHashMap(seances), LinkedHashMap(presences), LinkedHashMap(parametres),
        LinkedHashMap(examens), LinkedHashMap(notes), sequence, sequenceExamens,
    )

    fun restaurer(i: Instantane) {
        enfants.clear(); enfants.putAll(i.enfants)
        seances.clear(); seances.putAll(i.seances)
        presences.clear(); presences.putAll(i.presences)
        parametres.clear(); parametres.putAll(i.parametres)
        examens.clear(); examens.putAll(i.examens)
        notes.clear(); notes.putAll(i.notes)
        sequence = i.sequence
        sequenceExamens = i.sequenceExamens
        notifier()
    }

    // --- DAO -----------------------------------------------------------------------------------------

    val enfantDao = object : EnfantDao {
        override suspend fun inserer(enfant: EnfantEntity): Long {
            if (echecSurInsertionEnfant) throw IllegalStateException("insertion refusée (panne simulée)")
            val id = ++sequence
            enfants[id] = enfant.copy(id = id)
            notifier()
            return id
        }

        override suspend fun mettreAJour(enfant: EnfantEntity) {
            check(enfants.containsKey(enfant.id)) { "enfant inconnu" }
            enfants[enfant.id] = enfant
            notifier()
        }

        override suspend fun trouver(id: Long): EnfantEntity? = enfants[id]

        override fun observer(id: Long): Flow<EnfantEntity?> = flux { enfants[id] }

        override fun observerSelonArchivage(archive: Boolean): Flow<List<EnfantEntity>> =
            flux { enfants.values.filter { it.isArchived == archive } }

        override suspend fun tous(): List<EnfantEntity> = enfants.values.toList()

        override suspend fun retirerPresencesApres(id: Long, finSuivi: LocalDate): Int {
            val aRetirer = presences.keys.filter { it.first == id && it.second > finSuivi }
            aRetirer.forEach { presences.remove(it) }
            if (aRetirer.isNotEmpty()) notifier()
            return aRetirer.size
        }

        override suspend fun plagesDeSuivi(): List<PlageSuivi> =
            enfants.values.map { PlageSuivi(it.dateDebutSuivi, it.dateFinSuivi) }

        override suspend fun compterAdmissible(id: Long, dimanche: LocalDate): Int =
            enfants[id]?.let { if (admissible(it, dimanche)) 1 else 0 } ?: 0

        override suspend fun compterAdmissibles(dimanche: LocalDate): Int =
            enfants.values.count { admissible(it, dimanche) }

        override fun observerAdmissiblesAvecStatut(dimanche: LocalDate): Flow<List<EnfantAvecStatut>> = flux {
            enfants.values.filter { admissible(it, dimanche) }
                .map { EnfantAvecStatut(it, presences[it.id to dimanche]?.statut) }
        }

        override suspend fun archiver(id: Long, finSuivi: LocalDate, maintenant: Instant): Int {
            val e = enfants[id] ?: return 0
            if (e.isArchived) return 0
            enfants[id] = e.copy(isArchived = true, dateFinSuivi = finSuivi, archivedAt = maintenant, updatedAt = maintenant)
            notifier()
            return 1
        }

        override suspend fun changerPhoto(id: Long, chemin: String, maintenant: Instant): Int {
            if (echecSurChangerPhoto) throw IllegalStateException("mise à jour refusée (panne simulée)")
            val e = enfants[id] ?: return 0
            enfants[id] = e.copy(photoPath = chemin, updatedAt = maintenant)
            notifier()
            return 1
        }

        override suspend fun tousLesCheminsPhoto(): List<String> = enfants.values.map { it.photoPath }

        override fun observerNonInscritsA(dimanche: LocalDate): Flow<List<EnfantEntity>> =
            flux { enfants.values.filter { !it.isArchived && it.dateDebutSuivi > dimanche } }

        override suspend fun changerDebutSuivi(id: Long, debut: LocalDate, maintenant: Instant): Int {
            val e = enfants[id] ?: return 0
            enfants[id] = e.copy(dateDebutSuivi = debut, updatedAt = maintenant)
            notifier()
            return 1
        }

        override suspend fun desarchiver(id: Long, maintenant: Instant): Int {
            val e = enfants[id] ?: return 0
            if (!e.isArchived) return 0
            enfants[id] = e.copy(isArchived = false, dateFinSuivi = null, archivedAt = null, updatedAt = maintenant)
            notifier()
            return 1
        }

        override suspend fun completerSeancesCloturees(
            id: Long,
            apres: LocalDate,
            debut: LocalDate,
            statut: StatutPresence,
            maintenant: Instant,
        ) {
            for (seance in seances.values.filter { it.cloturee && it.dateDimanche > apres && it.dateDimanche >= debut }) {
                check(enfants.containsKey(id)) { "FOREIGN KEY constraint failed (enfant)" }
                val cle = id to seance.dateDimanche
                if (!presences.containsKey(cle)) presences[cle] = PresenceEntity(id, seance.dateDimanche, statut, maintenant, maintenant)
            }
            notifier()
        }

        override suspend fun supprimerPresences(id: Long): Int {
            val aRetirer = presences.keys.filter { it.first == id }
            aRetirer.forEach { presences.remove(it) }
            notifier()
            return aRetirer.size
        }

        override suspend fun supprimerNotes(id: Long): Int {
            val aRetirer = notes.keys.filter { it.second == id }
            aRetirer.forEach { notes.remove(it) }
            notifier()
            return aRetirer.size
        }

        override suspend fun supprimer(id: Long): Int {
            // Clés étrangères en RESTRICT : impossible tant qu'il reste des présences ou des notes.
            check(presences.keys.none { it.first == id }) { "FOREIGN KEY constraint failed (presences)" }
            check(notes.keys.none { it.second == id }) { "FOREIGN KEY constraint failed (notes)" }
            val retire = enfants.remove(id) != null
            if (retire) notifier()
            return if (retire) 1 else 0
        }
    }

    val seanceDao = object : SeanceDao {
        override suspend fun insererSiAbsente(seance: SeanceEntity): Long {
            if (seances.containsKey(seance.dateDimanche)) return -1
            seances[seance.dateDimanche] = seance
            notifier()
            return 1
        }

        override suspend fun trouver(dimanche: LocalDate): SeanceEntity? = seances[dimanche]

        override fun observer(dimanche: LocalDate): Flow<SeanceEntity?> = flux { seances[dimanche] }

        override suspend fun toutes(): List<SeanceEntity> = seances.values.toList()

        override suspend fun marquerCloturee(dimanche: LocalDate, maintenant: Instant): Int {
            if (echecSurMarquerCloturee) throw IllegalStateException("écriture refusée (panne simulée)")
            val s = seances[dimanche] ?: return 0
            if (s.cloturee) return 0
            seances[dimanche] = s.copy(cloturee = true, clotureeAt = maintenant, updatedAt = maintenant)
            notifier()
            return 1
        }
    }

    val presenceDao = object : PresenceDao {
        private fun verifierClesEtrangeres(enfantId: Long, dimanche: LocalDate) {
            check(enfants.containsKey(enfantId)) { "FOREIGN KEY constraint failed (enfant)" }
            check(seances.containsKey(dimanche)) { "FOREIGN KEY constraint failed (séance)" }
        }

        override suspend fun insererSiAbsente(presence: PresenceEntity): Long {
            verifierClesEtrangeres(presence.enfantId, presence.dateDimanche)
            val cle = presence.enfantId to presence.dateDimanche
            if (presences.containsKey(cle)) return -1
            presences[cle] = presence
            notifier()
            return 1
        }

        override suspend fun changerStatut(enfantId: Long, dimanche: LocalDate, statut: StatutPresence, maintenant: Instant): Int {
            val cle = enfantId to dimanche
            val p = presences[cle] ?: return 0
            presences[cle] = p.copy(statut = statut, updatedAt = maintenant)
            notifier()
            return 1
        }

        override suspend fun creerManquantesPourAdmissibles(dimanche: LocalDate, statut: StatutPresence, maintenant: Instant) {
            for (e in enfants.values.filter { admissible(it, dimanche) }) {
                val cle = e.id to dimanche
                verifierClesEtrangeres(e.id, dimanche)
                if (!presences.containsKey(cle)) presences[cle] = PresenceEntity(e.id, dimanche, statut, maintenant, maintenant)
            }
            notifier()
        }

        override suspend fun remplacerStatut(dimanche: LocalDate, ancien: StatutPresence, nouveau: StatutPresence, maintenant: Instant): Int {
            var n = 0
            for ((cle, p) in presences.toList()) {
                if (cle.second == dimanche && p.statut == ancien) {
                    presences[cle] = p.copy(statut = nouveau, updatedAt = maintenant)
                    n++
                }
            }
            notifier()
            return n
        }

        override suspend fun duDimanche(dimanche: LocalDate): List<PresenceEntity> =
            presences.values.filter { it.dateDimanche == dimanche }

        override suspend fun toutes(): List<PresenceEntity> = presences.values.toList()

        override suspend fun trouver(enfantId: Long, dimanche: LocalDate): PresenceEntity? = presences[enfantId to dimanche]

        /** Comme la requête Room : au moins un enfant Présent ou En retard ce dimanche-là. */
        private fun pointe(dimanche: LocalDate): Boolean =
            presences.values.any { it.dateDimanche == dimanche && (it.statut == StatutPresence.PRESENT || it.statut == StatutPresence.EN_RETARD) }

        override fun observerCompteurs(): Flow<List<CompteurStatutLigne>> = flux {
            presences.values
                .filter { seances[it.dateDimanche]?.cloturee == true && pointe(it.dateDimanche) }
                .groupBy { it.enfantId to it.statut }
                .map { (cle, liste) -> CompteurStatutLigne(cle.first, cle.second, liste.size) }
        }

        override fun observerHistorique(enfantId: Long): Flow<List<LigneHistorique>> = flux {
            presences.values.filter { it.enfantId == enfantId }
                .sortedByDescending { it.dateDimanche }
                .map { LigneHistorique(it.dateDimanche, it.statut, seances.getValue(it.dateDimanche).cloturee, pointe(it.dateDimanche)) }
        }
    }

    val parametreDao = object : ParametreDao {
        override suspend fun lire(cle: String): String? = parametres[cle]

        override fun observer(cle: String): Flow<String?> = flux { parametres[cle] }

        override suspend fun ecrire(parametre: ParametreEntity) {
            parametres[parametre.cle] = parametre.valeur
            notifier()
        }

        override suspend fun effacer(cle: String) {
            parametres.remove(cle)
            notifier()
        }

        override suspend fun tous(): List<ParametreEntity> = parametres.map { (cle, valeur) -> ParametreEntity(cle, valeur) }
    }

    /** Si vrai, l'insertion des présences restaurées échoue : sert à vérifier qu'une restauration ratée ne perd rien. */
    var echecSurInsertionPresences = false

    val maintenanceDao = object : MaintenanceDao {
        override suspend fun enfants(): List<EnfantEntity> = enfants.values.sortedBy { it.id }

        override suspend fun seances(): List<SeanceEntity> = seances.values.sortedBy { it.dateDimanche }

        override suspend fun presences(): List<PresenceEntity> = presences.values.sortedWith(compareBy({ it.enfantId }, { it.dateDimanche }))

        override suspend fun examens(): List<ExamenEntity> = examens.values.sortedBy { it.id }

        override suspend fun notes(): List<NoteEntity> = notes.values.sortedWith(compareBy({ it.examenId }, { it.enfantId }))

        override suspend fun viderNotes() {
            notes.clear()
            notifier()
        }

        override suspend fun viderExamens() {
            check(notes.isEmpty()) { "FOREIGN KEY constraint failed (notes)" }
            examens.clear()
            notifier()
        }

        override suspend fun insererExamens(examens: List<ExamenEntity>) {
            for (x in examens) {
                check(!this@FauxMagasin.examens.containsKey(x.id)) { "UNIQUE constraint failed: examens.id" }
                this@FauxMagasin.examens[x.id] = x
                if (x.id > sequenceExamens) sequenceExamens = x.id
            }
            notifier()
        }

        override suspend fun insererNotes(notes: List<NoteEntity>) {
            for (n in notes) {
                check(examens.containsKey(n.examenId) && enfants.containsKey(n.enfantId)) { "FOREIGN KEY constraint failed" }
                this@FauxMagasin.notes[n.examenId to n.enfantId] = n
            }
            notifier()
        }

        override suspend fun nombreEnfants(): Int = enfants.size

        override suspend fun nombreSeances(): Int = seances.size

        override suspend fun viderPresences() {
            presences.clear()
            notifier()
        }

        override suspend fun viderSeances() {
            check(presences.isEmpty()) { "FOREIGN KEY constraint failed (presences)" }
            seances.clear()
            notifier()
        }

        override suspend fun viderEnfants() {
            check(presences.isEmpty() && notes.isEmpty()) { "FOREIGN KEY constraint failed (presences, notes)" }
            enfants.clear()
            notifier()
        }

        override suspend fun insererEnfants(enfants: List<EnfantEntity>) {
            for (e in enfants) {
                check(!this@FauxMagasin.enfants.containsKey(e.id)) { "UNIQUE constraint failed: enfants.id" }
                this@FauxMagasin.enfants[e.id] = e
                if (e.id > sequence) sequence = e.id
            }
            notifier()
        }

        override suspend fun insererSeances(seances: List<SeanceEntity>) {
            for (x in seances) {
                check(!this@FauxMagasin.seances.containsKey(x.dateDimanche)) { "UNIQUE constraint failed: seances" }
                this@FauxMagasin.seances[x.dateDimanche] = x
            }
            notifier()
        }

        override suspend fun insererPresences(presences: List<PresenceEntity>) {
            if (echecSurInsertionPresences) throw IllegalStateException("insertion refusée (panne simulée)")
            for (p in presences) {
                check(enfants.containsKey(p.enfantId) && seances.containsKey(p.dateDimanche)) { "FOREIGN KEY constraint failed" }
                this@FauxMagasin.presences[p.enfantId to p.dateDimanche] = p
            }
            notifier()
        }
    }

    val examenDao = object : ExamenDao {
        override fun observerExamens(): Flow<List<ExamenEntity>> = flux { examens.values.sortedWith(compareBy({ it.date }, { it.id })) }

        override fun observerNotes(): Flow<List<NoteEntity>> = flux { notes.values.toList() }

        override suspend fun tous(): List<ExamenEntity> = examens.values.sortedWith(compareBy({ it.date }, { it.id }))

        override suspend fun toutesLesNotes(): List<NoteEntity> = notes.values.toList()

        override suspend fun trouver(id: Long): ExamenEntity? = examens[id]

        override suspend fun notesDe(examenId: Long): List<NoteEntity> = notes.values.filter { it.examenId == examenId }

        override suspend fun inserer(examen: ExamenEntity): Long {
            val id = ++sequenceExamens
            examens[id] = examen.copy(id = id)
            notifier()
            return id
        }

        override suspend fun mettreAJour(examen: ExamenEntity) {
            check(examens.containsKey(examen.id)) { "examen inconnu" }
            examens[examen.id] = examen
            notifier()
        }

        override suspend fun ecrireNote(note: NoteEntity) {
            check(examens.containsKey(note.examenId) && enfants.containsKey(note.enfantId)) { "FOREIGN KEY constraint failed" }
            notes[note.examenId to note.enfantId] = note
            notifier()
        }

        override suspend fun effacerNote(examenId: Long, enfantId: Long) {
            notes.remove(examenId to enfantId)
            notifier()
        }

        override suspend fun supprimerNotesDe(examenId: Long) {
            notes.keys.filter { it.first == examenId }.forEach { notes.remove(it) }
            notifier()
        }

        override suspend fun supprimer(id: Long): Int {
            check(notes.keys.none { it.first == id }) { "FOREIGN KEY constraint failed (notes)" }
            val retire = examens.remove(id) != null
            notifier()
            return if (retire) 1 else 0
        }
    }

    val transaction = object : ExecuteurTransaction {
        override suspend fun <T> executer(bloc: suspend () -> T): T {
            val avant = capturer()
            try {
                return bloc()
            } catch (erreur: Throwable) {
                restaurer(avant)
                throw erreur
            }
        }
    }
}

/** Faux stockage de photos : suit les fichiers temporaires et définitifs sans toucher au disque. */
class FauxStockagePhotos : StockagePhotos {
    val temporaires = LinkedHashSet<String>()
    val definitives = LinkedHashSet<String>()
    var echecPromotion = false

    /**
     * Si vrai, la photo temporaire n'est jamais consommée : sert à prouver que ce sont bien les gardes
     * du code (et non un effet du faux) qui empêchent les doublons.
     */
    var temporairesIndelebiles = false
    private var numero = 0

    fun nouvelleTemporaire(): String = "photos_tmp/t${++numero}.jpg".also { temporaires.add(it) }

    override suspend fun promouvoirTemporaire(cheminTemporaire: String): String {
        if (echecPromotion || (!temporairesIndelebiles && cheminTemporaire !in temporaires)) {
            throw IOException("photo temporaire illisible : $cheminTemporaire")
        }
        return "photos/f${++numero}.jpg".also { definitives.add(it) } // copie : la temporaire est conservée
    }

    override suspend fun supprimer(chemin: String) {
        definitives.remove(chemin)
        if (!temporairesIndelebiles) temporaires.remove(chemin)
    }

    override suspend fun existe(chemin: String): Boolean = chemin in temporaires || chemin in definitives
}

/** Faux répertoire de photos pour la sauvegarde complète : chemin relatif → contenu. */
class FauxFichiersPhotos : FichiersPhotos {
    val contenus = LinkedHashMap<String, ByteArray>()

    override fun ouvrir(chemin: String): java.io.InputStream? = contenus[chemin]?.inputStream()

    override fun enregistrer(chemin: String, contenu: java.io.InputStream) {
        require(chemin.startsWith("photos/") && ".." !in chemin) { "chemin refusé" }
        contenus[chemin] = contenu.readBytes()
    }

    override fun toutSupprimer() = contenus.clear()
}

