package mg.ecoledimanche.presences.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mg.ecoledimanche.presences.data.local.CleParametre
import mg.ecoledimanche.presences.data.local.PHOTO_NON_PRISE
import mg.ecoledimanche.presences.data.repository.DonneesRepository
import mg.ecoledimanche.presences.data.repository.EnfantRepository
import mg.ecoledimanche.presences.data.repository.EtatHorloge
import mg.ecoledimanche.presences.data.repository.GardienHorloge
import mg.ecoledimanche.presences.data.repository.HorlogeDouteuseException
import mg.ecoledimanche.presences.data.repository.MotifRefus
import mg.ecoledimanche.presences.data.repository.PresenceRepository
import mg.ecoledimanche.presences.data.repository.ResultatPointage
import mg.ecoledimanche.presences.data.sauvegarde.FormatSauvegarde
import mg.ecoledimanche.presences.data.sauvegarde.Json
import mg.ecoledimanche.presences.data.sauvegarde.ProblemeSauvegarde
import mg.ecoledimanche.presences.data.sauvegarde.SauvegardeInvalideException
import mg.ecoledimanche.presences.domain.MiseAJourEnfant
import mg.ecoledimanche.presences.domain.StatutPresence
import mg.ecoledimanche.presences.domain.VerdictHorloge
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Version 1.2.0 : garde-fou de l'horloge, import avec mise à jour, sauvegarde complète, remise à zéro. */
class AmeliorationsV12Test {
    /** Environnement avec le VRAI garde-fou de l'horloge et le dépôt des données. */
    private class EnvV12(debut: String = "2026-10-04", heure: Int = 8) {
        val env = Environnement(instantLocal(debut, heure))
        val m = env.magasin
        val gardien = GardienHorloge(m.parametreDao, env.horloge)
        val enfants = EnfantRepository(m.enfantDao, m.seanceDao, m.transaction, env.photos, env.horloge, gardien)
        val presences = PresenceRepository(m.enfantDao, m.seanceDao, m.presenceDao, m.transaction, env.horloge, gardien)
        val fichiers = FauxFichiersPhotos()
        val donnees = DonneesRepository(m.maintenanceDao, m.parametreDao, m.transaction, fichiers, env.horloge, "1.2.0", 2)
    }

    // --- Garde-fou de l'horloge --------------------------------------------------------------------

    @Test
    fun horlogeEnAvanceDUnAn_rienNEstCloture_puisConfirmation() = runTest {
        val e = EnvV12()
        val sarah = e.enfants.ajouter(donnees("Rakoto", "Sarah"), null)
        assertEquals(0, e.presences.rattraper()) // dimanche 04/10 à 08 h : rien d'échu, date mémorisée

        e.env.allerA("2027-10-10", 12) // année mal réglée
        assertEquals(0, e.presences.rattraper())
        assertTrue(e.m.seances.isEmpty()) // aucun dimanche clôturé à tort
        val etat = e.gardien.etat.value as EtatHorloge.Douteuse
        assertEquals(VerdictHorloge.SAUT, etat.verdict)
        assertEquals(
            ResultatPointage.Refuse(MotifRefus.HORLOGE_DOUTEUSE),
            e.presences.pointer(sarah, LocalDate.parse("2027-10-10"), StatutPresence.PRESENT),
        )
        try {
            e.enfants.ajouter(donnees("Rabe", "David"), null)
            fail("inscription acceptée avec une date douteuse")
        } catch (attendu: HorlogeDouteuseException) {
            assertEquals(1, e.m.enfants.size)
        }

        // On remet la bonne date : tout redevient normal, sans confirmation.
        e.env.allerA("2026-10-11", 12)
        assertEquals(2, e.presences.rattraper()) // 04/10 et 11/10
        assertEquals(EtatHorloge.Fiable, e.gardien.etat.value)
    }

    @Test
    fun horlogeEnArriere_signalee_confirmationPossible_dateImpossible_non() = runTest {
        val e = EnvV12("2026-11-15", 12)
        assertTrue(e.gardien.horlogeFiable())
        e.env.allerA("2026-11-01", 12)
        assertFalse(e.gardien.horlogeFiable())
        assertEquals(VerdictHorloge.RECUL, (e.gardien.etat.value as EtatHorloge.Douteuse).verdict)
        e.gardien.confirmerDateActuelle() // « La date du téléphone est correcte »
        assertTrue(e.gardien.horlogeFiable())

        e.env.allerA("2016-01-01", 12) // batterie vidée : date d'usine
        assertFalse(e.gardien.horlogeFiable())
        val etat = e.gardien.etat.value as EtatHorloge.Douteuse
        assertFalse(etat.confirmable)
        e.gardien.confirmerDateActuelle()
        assertFalse(e.gardien.horlogeFiable())
    }

    @Test
    fun vacances_troisMoisSansOuvrir_pasDAlerte() = runTest {
        val e = EnvV12("2026-06-28", 12)
        assertTrue(e.gardien.horlogeFiable())
        e.env.horloge.maintenant = e.env.horloge.maintenant.plus(Duration.ofDays(95))
        assertTrue(e.gardien.horlogeFiable())
    }

    // --- Import avec mise à jour --------------------------------------------------------------------

    @Test
    fun appliquerImport_ajoutsEtMisesAJour_uneSeuleTransaction() = runTest {
        val e = EnvV12()
        val sarah = e.enfants.ajouter(donnees("Rakoto", "Sarah"), null)
        e.enfants.archiver(sarah)
        val avant = e.m.enfants.getValue(sarah)
        val connue = e.enfants.enfantsConnus().single()
        val maj = MiseAJourEnfant(sarah, connue.donnees, connue.donnees.copy(adresse = "Lot 2", nombreFreresSoeurs = 3))

        assertEquals(1 to 1, e.enfants.appliquerImport(listOf(donnees("Rabe", "David", "2015-11-02")), listOf(maj)))
        val apres = e.m.enfants.getValue(sarah)
        assertEquals("Lot 2", apres.adresse)
        assertEquals(3, apres.nombreFreresSoeurs)
        // Suivi, archivage et photo ne sont jamais modifiés par un import.
        assertEquals(avant.dateDebutSuivi, apres.dateDebutSuivi)
        assertEquals(avant.dateFinSuivi, apres.dateFinSuivi)
        assertTrue(apres.isArchived)
        assertEquals(avant.photoPath, apres.photoPath)
        assertEquals(2, e.m.enfants.size)

        // Un enfant disparu entre l'aperçu et l'enregistrement : rien n'est enregistré.
        val fantome = maj.copy(id = 999)
        try {
            e.enfants.appliquerImport(listOf(donnees("Ravelo", "Fanja")), listOf(fantome))
            fail("import accepté")
        } catch (attendu: IllegalStateException) {
            assertEquals(2, e.m.enfants.size)
        }
    }

    // --- Sauvegarde complète et restauration ---------------------------------------------------------

    /** Deux enfants (dont un avec photo), une séance clôturée, des présences. */
    private suspend fun EnvV12.remplir(): Long {
        val tmp = env.photos.nouvelleTemporaire()
        val sarah = enfants.ajouter(donnees("Rakoto", "Sarah").copy(adresse = "Lot 1 « A » ; Antananarivo\nporte 2"), tmp)
        val photo = m.enfants.getValue(sarah).photoPath
        fichiers.contenus[photo] = byteArrayOf(1, 2, 3, 4)
        enfants.ajouter(donnees("Rabe", "David", "2015-11-02"), null)
        presences.pointer(sarah, LocalDate.parse("2026-10-04"), StatutPresence.EN_RETARD)
        env.allerA("2026-10-04", 11)
        presences.rattraper()
        return sarah
    }

    private suspend fun EnvV12.sauvegarde(): ByteArray = ByteArrayOutputStream().also { donnees.sauvegarder(it) }.toByteArray()

    @Test
    fun sauvegardePuisRestaurationSurUnTelephoneVierge_toutEstIdentique() = runTest {
        val source = EnvV12()
        val sarah = source.remplir()
        val octets = source.sauvegarde()

        val cible = EnvV12("2026-11-16", 12)
        assertTrue(cible.donnees.accueilAFaire().first())
        val lue = cible.donnees.lireSauvegarde { ByteArrayInputStream(octets) }
        assertEquals(2, lue.manifeste.enfants)
        assertEquals(1, lue.manifeste.photos)
        assertEquals("1.2.0", lue.manifeste.versionApplication)
        cible.donnees.restaurer(lue) { ByteArrayInputStream(octets) }

        assertEquals(source.m.enfants, cible.m.enfants) // identifiants, fiches, suivi, archivage, horodatages
        assertEquals(source.m.seances, cible.m.seances)
        assertEquals(source.m.presences, cible.m.presences)
        val photo = cible.m.enfants.getValue(sarah).photoPath
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), cible.fichiers.contenus[photo])
        assertFalse(cible.donnees.accueilAFaire().first())
        assertEquals(StatutPresence.EN_RETARD, cible.m.presences[sarah to LocalDate.parse("2026-10-04")]?.statut)
    }

    @Test
    fun restauration_remplaceLesDonneesActuelles_etUnEchecNePerdRien() = runTest {
        val source = EnvV12()
        source.remplir()
        val octets = source.sauvegarde()

        val cible = EnvV12()
        cible.enfants.ajouter(donnees("Ravelo", "Fanja", "2018-05-10"), null)
        val lue = cible.donnees.lireSauvegarde { ByteArrayInputStream(octets) }

        cible.m.echecSurInsertionPresences = true
        try {
            cible.donnees.restaurer(lue) { ByteArrayInputStream(octets) }
            fail("restauration acceptée")
        } catch (attendu: IllegalStateException) {
            assertEquals(listOf("Fanja"), cible.m.enfants.values.map { it.prenom }) // données actuelles intactes
        }
        cible.m.echecSurInsertionPresences = false
        cible.donnees.restaurer(lue) { ByteArrayInputStream(octets) }
        assertEquals(setOf("Sarah", "David"), cible.m.enfants.values.map { it.prenom }.toSet())
        assertEquals(source.m.presences, cible.m.presences)
    }

    @Test
    fun photoAbsenteDeLaSauvegarde_silhouetteALaRestauration() = runTest {
        val source = EnvV12()
        val sarah = source.remplir()
        source.fichiers.contenus.clear() // le fichier de la photo a disparu du téléphone
        val octets = source.sauvegarde()
        val cible = EnvV12()
        val lue = cible.donnees.lireSauvegarde { ByteArrayInputStream(octets) }
        assertEquals(0, lue.manifeste.photos)
        cible.donnees.restaurer(lue) { ByteArrayInputStream(octets) }
        assertEquals(PHOTO_NON_PRISE, cible.m.enfants.getValue(sarah).photoPath)
    }

    @Test
    fun fichiersQuiNeSontPasDesSauvegardes_refuses() = runTest {
        val e = EnvV12()
        fun probleme(octets: ByteArray): ProblemeSauvegarde? = try {
            FormatSauvegarde.lire(ByteArrayInputStream(octets))
            null
        } catch (erreur: SauvegardeInvalideException) {
            erreur.probleme
        }
        assertEquals(ProblemeSauvegarde.PAS_UNE_SAUVEGARDE, probleme("Nom;Prénom\n".toByteArray()))
        assertEquals(ProblemeSauvegarde.PAS_UNE_SAUVEGARDE, probleme(zip("autre.txt" to "bonjour")))

        val manifeste = Json.ecrire(mapOf("application" to FormatSauvegarde.APPLICATION, "format" to 99))
        assertEquals(ProblemeSauvegarde.VERSION_TROP_RECENTE, probleme(zip("sauvegarde.json" to manifeste)))

        val v1 = Json.ecrire(mapOf("application" to FormatSauvegarde.APPLICATION, "format" to 1, "creeeLe" to "2026-11-16T06:00:00Z"))
        assertEquals(ProblemeSauvegarde.ILLISIBLE, probleme(zip("sauvegarde.json" to v1)))
        assertEquals(ProblemeSauvegarde.ILLISIBLE, probleme(zip("sauvegarde.json" to v1, "donnees.json" to "{\"enfants\":[")))
        // Présence d'un enfant qui n'existe pas dans la sauvegarde.
        val incoherent = Json.ecrire(
            mapOf(
                "enfants" to emptyList<Any>(),
                "seances" to emptyList<Any>(),
                "presences" to listOf(mapOf("enfantId" to 3, "dateDimanche" to "2026-10-04", "statut" to "PRESENT", "createdAt" to 1, "updatedAt" to 1)),
            ),
        )
        assertEquals(ProblemeSauvegarde.INCOHERENTE, probleme(zip("sauvegarde.json" to v1, "donnees.json" to incoherent)))
        // Une photo au chemin dangereux n'est jamais écrite.
        assertFalse(FormatSauvegarde.cheminPhotoValide("photos/../databases/ecole_dimanche.db"))
        assertFalse(FormatSauvegarde.cheminPhotoValide("../photos/a.jpg"))
        assertTrue(FormatSauvegarde.cheminPhotoValide("photos/1f0c-aa.jpg"))
        assertTrue(e.m.enfants.isEmpty())
    }

    private fun zip(vararg entrees: Pair<String, String>): ByteArray {
        val sortie = ByteArrayOutputStream()
        ZipOutputStream(sortie).use { z ->
            for ((nom, texte) in entrees) {
                z.putNextEntry(ZipEntry(nom))
                z.write(texte.toByteArray())
                z.closeEntry()
            }
        }
        return sortie.toByteArray()
    }

    @Test
    fun json_allerRetour_caracteresSpeciaux() {
        val valeur = linkedMapOf(
            "texte" to "Ravo « Hery » ; \"guillemets\" \\ \n\t é œ \u0001",
            "nombre" to -42L,
            "vrai" to true,
            "nul" to null,
            "liste" to listOf(1L, "a", emptyMap<String, Any?>(), emptyList<Any?>()),
        )
        assertEquals(valeur, Json.lire(Json.ecrire(valeur)))
        assertEquals(mapOf("a" to listOf(1L, 2L)), Json.lire(" { \"a\" : [ 1 , 2 ] } "))
    }

    // --- Repartir de zéro ------------------------------------------------------------------------------

    @Test
    fun repartirDeZero_effaceToutEtRameneALAccueil() = runTest {
        val e = EnvV12()
        e.remplir()
        e.donnees.terminerAccueil()
        assertFalse(e.donnees.accueilAFaire().first())

        e.donnees.reinitialiser()
        assertTrue(e.m.enfants.isEmpty() && e.m.seances.isEmpty() && e.m.presences.isEmpty())
        assertTrue(e.fichiers.contenus.isEmpty())
        assertTrue(e.donnees.accueilAFaire().first())
        assertNull(e.m.parametres[CleParametre.ACCUEIL_FAIT])
        // La date mémorisée par le garde-fou est conservée.
        assertTrue(e.m.parametres.containsKey(CleParametre.HORLOGE_DERNIERE))

        e.donnees.terminerAccueil() // « Commencer avec des données vierges »
        assertFalse(e.donnees.accueilAFaire().first())
    }
}
