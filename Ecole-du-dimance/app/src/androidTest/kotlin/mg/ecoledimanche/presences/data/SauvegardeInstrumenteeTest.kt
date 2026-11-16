package mg.ecoledimanche.presences.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mg.ecoledimanche.presences.data.local.AppDatabase
import mg.ecoledimanche.presences.data.local.CleParametre
import mg.ecoledimanche.presences.data.local.ExecuteurTransactionRoom
import mg.ecoledimanche.presences.data.repository.DonneesRepository
import mg.ecoledimanche.presences.data.repository.EnfantRepository
import mg.ecoledimanche.presences.data.repository.FichiersPhotos
import mg.ecoledimanche.presences.data.repository.GardienHorloge
import mg.ecoledimanche.presences.data.repository.PresenceRepository
import mg.ecoledimanche.presences.domain.StatutPresence
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sauvegarde complète, restauration et remise à zéro sur la VRAIE base Room (ordre des clés étrangères,
 * insertion avec les identifiants d'origine, table des paramètres). Exécuté sur émulateur par la CI.
 */
@RunWith(AndroidJUnit4::class)
class SauvegardeInstrumenteeTest {
    private class Telephone(contexte: Context) {
        val base: AppDatabase = Room.inMemoryDatabaseBuilder(contexte, AppDatabase::class.java).build()
        val horloge = HorlogeControlee(Instant.EPOCH).apply { allerA("2026-10-06", 14) }
        val stockage = StockageFactice()
        val fichiers = FichiersEnMemoire()
        private val transaction = ExecuteurTransactionRoom(base)
        val gardien = GardienHorloge(base.parametreDao(), horloge)
        val enfants = EnfantRepository(base.enfantDao(), base.seanceDao(), transaction, stockage, horloge, gardien)
        val presences = PresenceRepository(base.enfantDao(), base.seanceDao(), base.presenceDao(), transaction, horloge, gardien)
        val donnees = DonneesRepository(base.maintenanceDao(), base.parametreDao(), transaction, fichiers, horloge, "1.2.0", AppDatabase.VERSION)
    }

    private class FichiersEnMemoire : FichiersPhotos {
        val contenus = LinkedHashMap<String, ByteArray>()
        override fun ouvrir(chemin: String): InputStream? = contenus[chemin]?.inputStream()
        override fun enregistrer(chemin: String, contenu: InputStream) {
            contenus[chemin] = contenu.readBytes()
        }
        override fun toutSupprimer() = contenus.clear()
    }

    private lateinit var a: Telephone
    private lateinit var b: Telephone

    @Before
    fun preparer() {
        val contexte = ApplicationProvider.getApplicationContext<Context>()
        a = Telephone(contexte)
        b = Telephone(contexte)
    }

    @After
    fun fermer() {
        a.base.close()
        b.base.close()
    }

    @Test
    fun sauvegardeSurUnTelephone_restaurationSurUnAutre_toutEstIdentique() = runTest {
        val sarah = a.enfants.ajouter(donneesEnfant("Rakoto", "Sarah"), a.stockage.nouvelleTemporaire())
        a.fichiers.contenus[a.base.enfantDao().trouver(sarah)!!.photoPath] = byteArrayOf(9, 8, 7)
        val david = a.enfants.ajouter(donneesEnfant("Rabe", "David"), null)
        a.horloge.allerA("2026-10-11", 9)
        a.presences.pointer(sarah, LocalDate.parse("2026-10-11"), StatutPresence.PRESENT)
        a.horloge.allerA("2026-10-11", 11)
        a.presences.rattraper()
        a.enfants.archiver(david)
        val notes = mg.ecoledimanche.presences.data.repository.NotesRepository(a.base.examenDao(), a.base.enfantDao(), ExecuteurTransactionRoom(a.base), a.horloge)
        notes.enregistrerExamen(null, "Trimestre 1", LocalDate.parse("2026-10-11"), mapOf(sarah to 1250, david to 900))

        val octets = ByteArrayOutputStream().also { a.donnees.sauvegarder(it) }.toByteArray()

        // Le second téléphone a déjà un enfant : la restauration le remplace.
        b.enfants.ajouter(donneesEnfant("Ravelo", "Fanja"), null)
        val lue = b.donnees.lireSauvegarde { ByteArrayInputStream(octets) }
        b.donnees.restaurer(lue) { ByteArrayInputStream(octets) }

        assertEquals(a.base.maintenanceDao().enfants(), b.base.maintenanceDao().enfants())
        assertEquals(a.base.maintenanceDao().seances(), b.base.maintenanceDao().seances())
        assertEquals(a.base.maintenanceDao().presences(), b.base.maintenanceDao().presences())
        assertEquals(a.base.maintenanceDao().examens(), b.base.maintenanceDao().examens())
        assertEquals(a.base.maintenanceDao().notes(), b.base.maintenanceDao().notes())
        assertEquals(2, b.base.maintenanceDao().notes().size)
        assertEquals(StatutPresence.ABSENT, b.base.presenceDao().trouver(david, LocalDate.parse("2026-10-11"))?.statut)
        assertTrue(b.base.enfantDao().trouver(david)!!.isArchived)
        val photo = b.base.enfantDao().trouver(sarah)!!.photoPath
        assertArrayEquals(byteArrayOf(9, 8, 7), b.fichiers.contenus[photo])
        assertEquals("1", b.base.parametreDao().lire(CleParametre.ACCUEIL_FAIT))

        // Les identifiants continuent après ceux de la sauvegarde.
        val nouveau = b.enfants.ajouter(donneesEnfant("Ravelo", "Fanja"), null)
        assertTrue(nouveau > david)
    }

    @Test
    fun repartirDeZero_videLaBase_etRameneALAccueil() = runTest {
        val sarah = a.enfants.ajouter(donneesEnfant("Rakoto", "Sarah"), null)
        a.horloge.allerA("2026-10-11", 11)
        a.presences.rattraper()
        a.donnees.terminerAccueil()
        assertFalse(a.donnees.accueilAFaire().first())

        a.donnees.reinitialiser()
        assertEquals(0, a.base.maintenanceDao().nombreEnfants())
        assertEquals(0, a.base.maintenanceDao().nombreSeances())
        assertNull(a.base.enfantDao().trouver(sarah))
        assertTrue(a.donnees.accueilAFaire().first())
    }
}
