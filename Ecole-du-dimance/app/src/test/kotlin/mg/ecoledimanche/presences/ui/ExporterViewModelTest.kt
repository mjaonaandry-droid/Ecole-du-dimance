package mg.ecoledimanche.presences.ui

import java.io.IOException
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import mg.ecoledimanche.presences.data.Environnement
import mg.ecoledimanche.presences.data.instantLocal
import mg.ecoledimanche.presences.domain.StatutPresence
import mg.ecoledimanche.presences.ui.exporter.ActionExport
import mg.ecoledimanche.presences.ui.exporter.ApercuExport
import mg.ecoledimanche.presences.ui.exporter.ExporterViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExporterViewModelTest : TestAvecMain() {
    private val dimanche = LocalDate.parse("2026-10-11")

    /** Marque UTF-8 attendue en tête du fichier (voir ExportCsv). */
    private val BOM: String = 0xFEFF.toChar().toString()

    /** Trois enfants inscrits le 06/10 ; Sarah présente et David en retard le 11/10 ; on est le lundi 12/10. */
    private suspend fun prepare(): Environnement {
        val env = Environnement(instantLocal("2026-10-06", 14))
        val sarah = env.inscrire("Rakoto", "Sarah")
        val david = env.inscrire("Rakoto", "David")
        val nathan = env.inscrire("Rakoto", "Nathan")
        env.allerA("2026-10-11", 8, 30)
        env.presences.pointer(sarah, dimanche, StatutPresence.PRESENT)
        env.presences.pointer(david, dimanche, StatutPresence.EN_RETARD)
        env.allerA("2026-10-12", 9, 0)
        env.enfants.archiver(nathan)
        return env
    }

    private fun TestScope.vm(env: Environnement): ExporterViewModel {
        val modele = ExporterViewModel(env.presences, env.horloge, flowOf(Unit))
        backgroundScope.launch { modele.etat.collect { } }
        return modele
    }

    @Test
    fun apercu_annonceLeNombreDEnfantsEtDeDimanches_archivesIncluesParDefaut() = runTest(dispatcher) {
        val modele = vm(prepare())
        advanceUntilIdle()
        assertTrue(modele.etat.value.inclureArchives)
        assertEquals(ApercuExport.Pret(enfants = 3, dimanches = 1), modele.etat.value.apercu)

        modele.inclureArchives(false)
        advanceUntilIdle()
        assertEquals(ApercuExport.Pret(enfants = 2, dimanches = 1), modele.etat.value.apercu)
    }

    @Test
    fun sansEnfant_apercuVide_etRienAExporter() = runTest(dispatcher) {
        val modele = vm(Environnement(instantLocal("2026-10-06", 14)))
        advanceUntilIdle()
        assertEquals(ApercuExport.Pret(enfants = 0, dimanches = 0), modele.etat.value.apercu)
    }

    @Test
    fun exporter_confieLeCsvAAecrire_puisAnnonceLeSucces() = runTest(dispatcher) {
        val modele = vm(prepare())
        advanceUntilIdle()
        var ecrit: String? = null
        modele.exporter { texte -> ecrit = texte }
        advanceUntilIdle()

        assertEquals(ActionExport.Reussie(enfants = 3, dimanches = 1), modele.etat.value.action)
        val lignes = ecrit!!.removePrefix(BOM).trimEnd().split("\r\n")
        assertEquals("Nom;Prénom;Sexe;Date de naissance;Âge;Statut;11/10/2026;Présences;Retards;Absences;Assiduité", lignes[0])
        assertEquals(4, lignes.size) // en-tête + 3 enfants
        assertEquals("Rakoto;David;Fille;15/03/2017;9;Actif;En retard;0;1;0;100 %", lignes[1])
        assertEquals("Rakoto;Nathan;Fille;15/03/2017;9;Archivé;Absent;0;0;1;0 %", lignes[2])
        assertEquals("Rakoto;Sarah;Fille;15/03/2017;9;Actif;Présent;1;0;0;100 %", lignes[3])
    }

    @Test
    fun exporterSansLesArchives_neContientPasLesEnfantsArchives() = runTest(dispatcher) {
        val modele = vm(prepare())
        advanceUntilIdle()
        modele.inclureArchives(false)
        advanceUntilIdle()
        var ecrit = ""
        modele.exporter { texte -> ecrit = texte }
        advanceUntilIdle()
        assertTrue(ecrit.contains("Sarah") && ecrit.contains("David"))
        assertTrue(!ecrit.contains("Nathan"))
        assertEquals(ActionExport.Reussie(enfants = 2, dimanches = 1), modele.etat.value.action)
    }

    @Test
    fun uneEcritureQuiEchoue_estAnnoncee_etOnPeutReessayer() = runTest(dispatcher) {
        val modele = vm(prepare())
        advanceUntilIdle()
        modele.exporter { throw IOException("destination refusée") }
        advanceUntilIdle()
        assertEquals(ActionExport.Echec, modele.etat.value.action)

        modele.acquitter()
        advanceUntilIdle()
        assertEquals(ActionExport.Repos, modele.etat.value.action)
        var ecrit = false
        modele.exporter { ecrit = true }
        advanceUntilIdle()
        assertTrue(ecrit)
        assertEquals(ActionExport.Reussie(3, 1), modele.etat.value.action)
    }

    @Test
    fun cliquesRapproches_unSeulExportALaFois() = runTest(dispatcher) {
        val modele = vm(prepare())
        advanceUntilIdle()
        val libere = CompletableDeferred<Unit>()
        var ecritures = 0
        modele.exporter { ecritures++; libere.await() }
        advanceUntilIdle()
        assertEquals(ActionExport.EnCours, modele.etat.value.action)
        modele.exporter { ecritures++ } // ignoré : un export est déjà en cours
        modele.acquitter() // et le résultat en cours ne peut pas être effacé
        advanceUntilIdle()
        assertEquals(1, ecritures)
        assertEquals(ActionExport.EnCours, modele.etat.value.action)

        libere.complete(Unit)
        advanceUntilIdle()
        assertEquals(ActionExport.Reussie(3, 1), modele.etat.value.action)
    }

    @Test
    fun nomDeFichierSuggere_contientLaDateDuJour() = runTest(dispatcher) {
        val modele = vm(prepare())
        assertEquals("presences-ecole-du-dimanche-2026-10-12.csv", modele.nomFichierSuggere())
    }
}
