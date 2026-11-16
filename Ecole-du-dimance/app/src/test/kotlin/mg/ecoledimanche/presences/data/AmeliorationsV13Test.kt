package mg.ecoledimanche.presences.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mg.ecoledimanche.presences.data.repository.DonneesRepository
import mg.ecoledimanche.presences.data.repository.NotesRepository
import mg.ecoledimanche.presences.data.repository.PresenceRepository
import mg.ecoledimanche.presences.data.sauvegarde.FormatSauvegarde
import mg.ecoledimanche.presences.data.sauvegarde.Json
import mg.ecoledimanche.presences.domain.CumulNotes
import mg.ecoledimanche.presences.domain.ExportCsv
import mg.ecoledimanche.presences.domain.GroupeExport
import mg.ecoledimanche.presences.domain.Notes
import mg.ecoledimanche.presences.domain.StatutPresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Version 1.3.0 : notes d'examen (cumul, note manquante = 0) et dimanches sans pointage non comptés. */
class AmeliorationsV13Test {
    private class EnvV13(debut: String = "2026-10-06", heure: Int = 14) {
        val env = Environnement(instantLocal(debut, heure))
        val m = env.magasin
        val notes = NotesRepository(m.examenDao, m.enfantDao, m.transaction, env.horloge)
        val presences = PresenceRepository(m.enfantDao, m.seanceDao, m.presenceDao, m.transaction, env.horloge, examenDao = m.examenDao)
        val fichiers = FauxFichiersPhotos()
        val donnees = DonneesRepository(m.maintenanceDao, m.parametreDao, m.transaction, fichiers, env.horloge, "1.3.0", 3)
    }

    private val d1 = LocalDate.parse("2026-10-11")
    private val d2 = LocalDate.parse("2026-10-18")
    private val d3 = LocalDate.parse("2026-10-25")

    // --- Saisie et format des notes ------------------------------------------------------------------

    @Test
    fun lectureEtAffichageDesNotes() {
        assertEquals(1200, Notes.lire("12"))
        assertEquals(1250, Notes.lire(" 12,5 "))
        assertEquals(1225, Notes.lire("12.25"))
        assertEquals(905, Notes.lire("9,05"))
        assertEquals(0, Notes.lire("0"))
        assertEquals(2000, Notes.lire("20"))
        for (invalide in listOf("", "21", "20,5", "-1", "12,345", "douze", "1 2")) assertNull(invalide, Notes.lire(invalide))
        assertEquals("12", Notes.format(1200))
        assertEquals("12,5", Notes.format(1250))
        assertEquals("12,25", Notes.format(1225))
        assertEquals("9,05", Notes.format(905))
        assertEquals("0", Notes.format(0))
    }

    @Test
    fun cumul_exempleDeLEcole_troisExamens_unEnfantEnADeux_laTroisiemeCompteZero() {
        val examens = listOf(1L, 2L, 3L)
        val a = CumulNotes.calculer(examens, mapOf(1L to 1200, 2L to 1500, 3L to 1800))
        val b = CumulNotes.calculer(examens, mapOf(1L to 1200, 2L to 1500))
        assertEquals(4500, a.totalCentiemes)
        assertEquals(1500, a.moyenneCentiemes)
        assertEquals(listOf(1200, 1500, null), b.notes) // chaque note reste visible, l'absente est signalée
        assertEquals(2700, b.totalCentiemes) // 12 + 15 + 0
        assertEquals(6000, b.maximumCentiemes)
        assertEquals(900, b.moyenneCentiemes) // 27 / 3 = 9 sur 20
        assertEquals(1, b.manquantes)
        // Arrondi au centième : (10 + 10 + 10,01) / 3 = 10,0033… → 10.
        assertEquals(1000, CumulNotes(listOf(1000, 1000, 1001)).moyenneCentiemes)
        assertEquals(1667, CumulNotes(listOf(2000, 2000, 1000)).moyenneCentiemes) // 16,666… → 16,67
        assertNull(CumulNotes(emptyList()).moyenneCentiemes)
    }

    // --- Dépôt des notes --------------------------------------------------------------------------

    @Test
    fun tableauDesNotes_tousLesEnfantsActifs_noteManquanteZero_nouvelEnfantAussi() = runTest {
        val e = EnvV13()
        val sarah = e.env.inscrire("Rakoto", "Sarah")
        val david = e.env.inscrire("Rabe", "David")
        val x1 = e.notes.enregistrerExamen(null, "Premier trimestre", d1, mapOf(sarah to 1200, david to 1600))
        val x2 = e.notes.enregistrerExamen(null, "Deuxième trimestre", d2, mapOf(sarah to 1500, david to null))
        e.notes.enregistrerExamen(null, "Troisième trimestre", d3, mapOf(sarah to 1800))

        val tableau = e.notes.observerTableau().first()
        assertEquals(listOf("Premier trimestre", "Deuxième trimestre", "Troisième trimestre"), tableau.examens.map { it.examen.intitule })
        assertEquals(listOf(2, 1, 1), tableau.examens.map { it.notesSaisies })
        val parId = tableau.lignes.associateBy { it.enfant.id }
        assertEquals(listOf(1200, 1500, 1800), parId.getValue(sarah).cumul.notes)
        assertEquals(1500, parId.getValue(sarah).cumul.moyenneCentiemes)
        assertEquals(listOf(1600, null, null), parId.getValue(david).cumul.notes)
        assertEquals(1600, parId.getValue(david).cumul.totalCentiemes)
        assertEquals(533, parId.getValue(david).cumul.moyenneCentiemes) // 16 / 3 = 5,33

        // Un enfant inscrit plus tard compte aussi à tous les examens (règle choisie : tous les enfants actifs).
        val nathan = e.env.inscrire("Randria", "Nathan")
        val n = e.notes.observerTableau().first().lignes.single { it.enfant.id == nathan }
        assertEquals(listOf(null, null, null), n.cumul.notes)
        assertEquals(0, n.cumul.moyenneCentiemes)

        // Correction : une note effacée redevient « pas de note » ; une note modifiée est remplacée.
        e.notes.enregistrerExamen(x1, "Premier trimestre", d1, mapOf(sarah to null, david to 1700))
        assertNull(e.m.notes[x1 to sarah])
        assertEquals(1700, e.m.notes.getValue(x1 to david).centiemes)
        assertEquals(1500, e.m.notes.getValue(x2 to sarah).centiemes) // autres examens intacts
    }

    @Test
    fun enfantArchive_masque_maisSesNotesSontConservees() = runTest {
        val e = EnvV13()
        val sarah = e.env.inscrire("Rakoto", "Sarah")
        val david = e.env.inscrire("Rabe", "David")
        val x = e.notes.enregistrerExamen(null, "Examen", d1, mapOf(sarah to 1200, david to 1400))
        e.env.enfants.archiver(david)
        val tableau = e.notes.observerTableau().first()
        assertEquals(listOf(sarah), tableau.lignes.map { it.enfant.id })
        assertEquals(1, tableau.examens.single().notesSaisies)
        // L'écran de saisie n'envoie que les enfants actifs : la note de David reste.
        e.notes.enregistrerExamen(x, "Examen", d1, mapOf(sarah to 1300))
        assertEquals(1400, e.m.notes.getValue(x to david).centiemes)
        e.env.enfants.desarchiver(david)
        assertEquals(listOf(1400), e.notes.observerTableau().first().lignes.single { it.enfant.id == david }.cumul.notes)
    }

    @Test
    fun supprimerUnExamen_ouUnEnfant_retireSesNotes() = runTest {
        val e = EnvV13()
        val sarah = e.env.inscrire("Rakoto", "Sarah")
        val david = e.env.inscrire("Rabe", "David")
        val x1 = e.notes.enregistrerExamen(null, "A", d1, mapOf(sarah to 1200, david to 1400))
        val x2 = e.notes.enregistrerExamen(null, "B", d2, mapOf(sarah to 1000, david to 800))

        assertTrue(e.notes.supprimerExamen(x1))
        assertFalse(e.notes.supprimerExamen(x1))
        assertEquals(setOf(x2 to sarah, x2 to david), e.m.notes.keys)

        assertTrue(e.env.enfants.supprimerDefinitivement(sarah)) // impossible sans retirer ses notes (clé étrangère)
        assertEquals(setOf(x2 to david), e.m.notes.keys)
    }

    @Test
    fun intituleEtNotesControles() = runTest {
        val e = EnvV13()
        val sarah = e.env.inscrire("Rakoto", "Sarah")
        for ((intitule, note) in listOf("  " to 1200, "Examen" to 2001, "Examen" to -1)) {
            try {
                e.notes.enregistrerExamen(null, intitule, d1, mapOf(sarah to note))
                throw AssertionError("accepté : $intitule $note")
            } catch (attendu: IllegalArgumentException) {
                assertTrue(e.m.examens.isEmpty())
            }
        }
    }

    // --- Sauvegarde, remise à zéro, export ------------------------------------------------------------

    @Test
    fun sauvegardeRestaurationEtRemiseAZero_avecLesNotes() = runTest {
        val source = EnvV13()
        val sarah = source.env.inscrire("Rakoto", "Sarah")
        source.notes.enregistrerExamen(null, "Examen 1", d1, mapOf(sarah to 1350))
        val octets = ByteArrayOutputStream().also { source.donnees.sauvegarder(it) }.toByteArray()

        val cible = EnvV13()
        val lue = cible.donnees.lireSauvegarde { ByteArrayInputStream(octets) }
        assertEquals(FormatSauvegarde.FORMAT, lue.manifeste.format)
        assertEquals(1, lue.manifeste.examens)
        cible.donnees.restaurer(lue) { ByteArrayInputStream(octets) }
        assertEquals(source.m.examens, cible.m.examens)
        assertEquals(source.m.notes, cible.m.notes)

        cible.donnees.reinitialiser()
        assertTrue(cible.m.examens.isEmpty() && cible.m.notes.isEmpty() && cible.m.enfants.isEmpty())
    }

    @Test
    fun sauvegardeAuFormat1_1_2_0_seRestaureSansNotes() = runTest {
        val source = EnvV13()
        source.env.inscrire("Rakoto", "Sarah")
        val ancien = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(ancien).use { z ->
            val donnees = FormatSauvegarde.versJson(
                mg.ecoledimanche.presences.data.sauvegarde.ContenuSauvegarde(source.m.enfants.values.toList(), emptyList(), emptyList()),
            ).filterKeys { it != "examens" && it != "notes" }
            z.putNextEntry(java.util.zip.ZipEntry(FormatSauvegarde.ENTREE_DONNEES))
            z.write(Json.ecrire(donnees).toByteArray())
            z.closeEntry()
            z.putNextEntry(java.util.zip.ZipEntry(FormatSauvegarde.ENTREE_MANIFESTE))
            z.write(Json.ecrire(mapOf("application" to FormatSauvegarde.APPLICATION, "format" to 1, "creeeLe" to "2026-11-01T06:00:00Z")).toByteArray())
            z.closeEntry()
        }
        val octets = ancien.toByteArray()
        val cible = EnvV13()
        val lue = cible.donnees.lireSauvegarde { ByteArrayInputStream(octets) }
        cible.donnees.restaurer(lue) { ByteArrayInputStream(octets) }
        assertEquals(listOf("Sarah"), cible.m.enfants.values.map { it.prenom })
        assertTrue(cible.m.examens.isEmpty())
    }

    @Test
    fun export_colonnesDeNotes_etDimancheSansPointageVideEtNonCompte() = runTest {
        val e = EnvV13()
        val sarah = e.env.inscrire("Rakoto", "Sarah")
        val david = e.env.inscrire("Rabe", "David")
        e.notes.enregistrerExamen(null, "Trimestre 1", d1, mapOf(sarah to 1250))
        e.notes.enregistrerExamen(null, "Trimestre 2", d2, mapOf(sarah to 1500, david to 1000))
        e.env.allerA("2026-10-11", 9)
        e.presences.pointer(sarah, d1, StatutPresence.PRESENT)
        e.env.allerA("2026-10-19", 9) // 18/10 clôturé sans aucun pointage
        e.presences.rattraper()

        val tableau = e.presences.tableauExport(inclureArchives = true)
        val lignes = ExportCsv.construire(tableau, LocalDate.parse("2026-10-19"), setOf(GroupeExport.PRESENCES, GroupeExport.TOTAUX, GroupeExport.NOTES))
            .removePrefix(0xFEFF.toChar().toString()).trimEnd().split("\r\n")
        assertEquals(
            "Nom;Prénom;Sexe;Date de naissance;11/10/2026;18/10/2026;Présences;Retards;Absences;Assiduité;" +
                "Examen : Trimestre 1 (11/10/2026);Examen : Trimestre 2 (18/10/2026);Total des notes;Moyenne sur 20",
            lignes[0],
        )
        // David : absent le 11/10 (compté) ; le 18/10, personne n'a été pointé : cellule vide, non compté.
        assertEquals("Rabe;David;Fille;15/03/2017;Absent;;0;0;1;0 %;;10;10;5", lignes[1])
        assertEquals("Rakoto;Sarah;Fille;15/03/2017;Présent;;1;0;0;100 %;12,5;15;27,5;13,75", lignes[2])
    }
}
