package mg.ecoledimanche.presences.data

import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mg.ecoledimanche.presences.data.local.PHOTO_NON_PRISE
import mg.ecoledimanche.presences.data.repository.MotifRefus
import mg.ecoledimanche.presences.data.repository.ResultatPointage
import mg.ecoledimanche.presences.domain.CompteursAssiduite
import mg.ecoledimanche.presences.domain.IdentiteEnfant
import mg.ecoledimanche.presences.domain.PhaseSeance
import mg.ecoledimanche.presences.domain.StatutPresence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Version 1.1 : tri de la grille, saisie avant inscription, désarchivage, suppression, ajout groupé. */
class AmeliorationsV11Test {
    private val d27sept = LocalDate.parse("2026-09-27")
    private val d04 = LocalDate.parse("2026-10-04")
    private val d11 = LocalDate.parse("2026-10-11")
    private val d18 = LocalDate.parse("2026-10-18")
    private val d25 = LocalDate.parse("2026-10-25")

    /** Sarah, David, Nathan inscrits le mardi 06/10/2026 : suivi dès le dimanche 11/10. */
    private suspend fun troisEnfants(env: Environnement): Triple<Long, Long, Long> {
        env.allerA("2026-10-06", 14, 0)
        return Triple(env.inscrire("Rakoto", "Sarah"), env.inscrire("Rakoto", "David"), env.inscrire("Rakoto", "Nathan"))
    }

    private suspend fun compteurs(env: Environnement, id: Long, archives: Boolean = false): CompteursAssiduite =
        env.presences.observerAssiduite(archives).first().single { it.enfant.id == id }.compteurs

    // ------------------------------------------------------------------------------------------
    // Grille du dimanche : non pointés en haut, pointés en bas, ordre alphabétique conservé
    // ------------------------------------------------------------------------------------------

    @Test
    fun grille_nonPointesEnHaut_pointesEnBas_alphabetiqueDansChaqueGroupe() = runTest {
        val env = Environnement(instantLocal("2026-10-06", 14))
        val (sarah, david, _) = troisEnfants(env)
        env.allerA("2026-10-11", 8, 30)
        suspend fun prenoms() = env.presences.observerDimanche(d11).first().elements.map { it.enfant.prenom }

        assertEquals(listOf("David", "Nathan", "Sarah"), prenoms())
        env.presences.pointer(david, d11, StatutPresence.PRESENT)
        assertEquals(listOf("Nathan", "Sarah", "David"), prenoms())
        env.presences.pointer(sarah, d11, StatutPresence.EN_RETARD)
        assertEquals(listOf("Nathan", "David", "Sarah"), prenoms())
        // Annuler le pointage le remet en haut.
        env.presences.pointer(david, d11, StatutPresence.NON_ENREGISTRE)
        assertEquals(listOf("David", "Nathan", "Sarah"), prenoms())
    }

    @Test
    fun grille_seanceCloturee_lesAbsentsRestentEnHaut() = runTest {
        val env = Environnement(instantLocal("2026-10-06", 14))
        val (_, david, _) = troisEnfants(env)
        env.allerA("2026-10-11", 9, 0)
        env.presences.pointer(david, d11, StatutPresence.PRESENT)
        env.allerA("2026-10-12", 9, 0)
        env.presences.rattraper()
        val etat = env.presences.observerDimanche(d11).first()
        assertEquals(PhaseSeance.CLOTUREE, etat.phase)
        assertEquals(listOf("Nathan", "Sarah", "David"), etat.elements.map { it.enfant.prenom })
        assertEquals(listOf(StatutPresence.ABSENT, StatutPresence.ABSENT, StatutPresence.PRESENT), etat.elements.map { it.statut })
    }

    // ------------------------------------------------------------------------------------------
    // Dimanches passés antérieurs à l'inscription (registre papier)
    // ------------------------------------------------------------------------------------------

    @Test
    fun dimanchePasseAvantInscription_lesEnfantsActifsSontProposes_nonInscrits() = runTest {
        val env = Environnement(instantLocal("2026-10-06", 14))
        troisEnfants(env)
        env.allerA("2026-10-14", 9, 0)
        val etat = env.presences.observerDimanche(d04).first()
        assertEquals(PhaseSeance.CLOTUREE, etat.phase)
        assertTrue(etat.elements.isEmpty())
        assertEquals(listOf("David", "Nathan", "Sarah"), etat.nonInscrits.map { it.enfant.prenom })
        assertTrue(etat.nonInscrits.none { it.inscrit })
        // Séance ouverte ou à venir : rien n'est proposé.
        assertTrue(env.presences.observerDimanche(d18).first().nonInscrits.isEmpty())
        assertTrue(env.presences.observerDimanche(d25).first().nonInscrits.isEmpty())
    }

    @Test
    fun pointerAvantInscription_avanceLeSuivi_etLesDimanchesIntermediairesSontAbsents() = runTest {
        val env = Environnement(instantLocal("2026-10-06", 14))
        val (sarah, david, nathan) = troisEnfants(env)
        env.allerA("2026-10-20", 9, 0)
        env.presences.rattraper() // 11/10 et 18/10 clôturés : tout le monde absent

        assertEquals(
            ResultatPointage.Enregistre(StatutPresence.PRESENT),
            env.presences.pointerAvantInscription(sarah, d27sept, StatutPresence.PRESENT),
        )
        assertEquals(d27sept, env.magasin.enfants.getValue(sarah).dateDebutSuivi)
        assertEquals(StatutPresence.PRESENT, env.magasin.presences.getValue(sarah to d27sept).statut)
        assertEquals(StatutPresence.ABSENT, env.magasin.presences.getValue(sarah to d04).statut)
        assertTrue(env.magasin.seances.getValue(d27sept).cloturee)
        assertTrue(env.magasin.seances.getValue(d04).cloturee)
        // Les autres enfants ne sont pas concernés.
        assertNull(env.magasin.presences[david to d27sept])
        assertNull(env.magasin.presences[nathan to d04])
        assertEquals(LocalDate.parse("2026-10-11"), env.magasin.enfants.getValue(david).dateDebutSuivi)
        // Assiduité : 27/09 présent, 04/10, 11/10 et 18/10 absents.
        assertEquals(CompteursAssiduite(1, 0, 3), compteurs(env, sarah))

        // Le 04/10 se corrige ensuite comme n'importe quel dimanche clôturé.
        assertEquals(enregistre(StatutPresence.EN_RETARD), env.presences.pointer(sarah, d04, StatutPresence.EN_RETARD))
        assertEquals(CompteursAssiduite(1, 1, 2), compteurs(env, sarah))
        // Sarah n'est plus proposée comme « non inscrite » au 04/10 : elle y est suivie.
        val etat04 = env.presences.observerDimanche(d04).first()
        assertEquals(listOf(sarah), etat04.elements.map { it.enfant.id })
        assertEquals(setOf(david, nathan), etat04.nonInscrits.map { it.enfant.id }.toSet())
        // Le rattrapage ne touche plus à rien.
        assertEquals(0, env.presences.rattraper())
        assertEquals(StatutPresence.EN_RETARD, env.magasin.presences.getValue(sarah to d04).statut)
    }

    @Test
    fun pointerAvantInscription_surUneSeanceDejaClotureeParUnAutreEnfant() = runTest {
        val env = Environnement(instantLocal("2026-10-06", 14))
        val (sarah, david, _) = troisEnfants(env)
        env.allerA("2026-10-14", 9, 0)
        env.presences.pointerAvantInscription(sarah, d04, StatutPresence.PRESENT)
        assertEquals(
            enregistre(StatutPresence.EN_RETARD),
            env.presences.pointerAvantInscription(david, d27sept, StatutPresence.EN_RETARD),
        )
        assertEquals(StatutPresence.EN_RETARD, env.magasin.presences.getValue(david to d27sept).statut)
        assertEquals(StatutPresence.ABSENT, env.magasin.presences.getValue(david to d04).statut) // séance déjà clôturée
        assertEquals(StatutPresence.PRESENT, env.magasin.presences.getValue(sarah to d04).statut) // inchangé
        assertNull(env.magasin.presences[sarah to d27sept]) // Sarah n'était pas suivie le 27/09
    }

    @Test
    fun pointerAvantInscription_refuse_archive_seanceOuverte_etAnnulation() = runTest {
        val env = Environnement(instantLocal("2026-10-06", 14))
        val (sarah, david, nathan) = troisEnfants(env)
        env.allerA("2026-10-14", 9, 0)
        env.enfants.archiver(sarah)
        assertEquals(
            ResultatPointage.Refuse(MotifRefus.ENFANT_NON_ADMISSIBLE),
            env.presences.pointerAvantInscription(sarah, d04, StatutPresence.PRESENT),
        )
        assertEquals(
            ResultatPointage.Refuse(MotifRefus.ANNULATION_INTERDITE_APRES_CLOTURE),
            env.presences.pointerAvantInscription(david, d04, StatutPresence.NON_ENREGISTRE),
        )
        assertEquals(
            ResultatPointage.Refuse(MotifRefus.PAS_UN_DIMANCHE),
            env.presences.pointerAvantInscription(david, LocalDate.parse("2026-10-07"), StatutPresence.PRESENT),
        )
        assertEquals(
            ResultatPointage.Refuse(MotifRefus.ENFANT_NON_ADMISSIBLE),
            env.presences.pointerAvantInscription(9999, d04, StatutPresence.PRESENT),
        )
        assertEquals(LocalDate.parse("2026-10-11"), env.magasin.enfants.getValue(david).dateDebutSuivi)
        assertTrue(env.magasin.presences.keys.none { it.second == d04 })
        // Enfant déjà suivi à cette date : simple correction.
        env.allerA("2026-10-19", 9, 0)
        env.presences.rattraper()
        assertEquals(enregistre(StatutPresence.PRESENT), env.presences.pointerAvantInscription(nathan, d11, StatutPresence.PRESENT))
        assertEquals(LocalDate.parse("2026-10-11"), env.magasin.enfants.getValue(nathan).dateDebutSuivi)
    }

    // ------------------------------------------------------------------------------------------
    // Désarchivage
    // ------------------------------------------------------------------------------------------

    @Test
    fun desarchivage_redevientActif_lesDimanchesClotures_pendantLArchivageSontAbsents() = runTest {
        val env = Environnement(instantLocal("2026-10-06", 14))
        val (sarah, _, _) = troisEnfants(env)
        env.allerA("2026-10-11", 9, 0)
        env.presences.pointer(sarah, d11, StatutPresence.PRESENT)
        env.allerA("2026-10-13", 9, 0)
        assertTrue(env.enfants.archiver(sarah))
        env.allerA("2026-10-27", 9, 0)
        env.presences.rattraper() // 18/10 et 25/10 clôturés sans Sarah
        assertNull(env.magasin.presences[sarah to d18])

        assertTrue(env.enfants.desarchiver(sarah))
        val fiche = env.magasin.enfants.getValue(sarah)
        assertFalse(fiche.isArchived)
        assertNull(fiche.dateFinSuivi)
        assertNull(fiche.archivedAt)
        assertEquals(d11, fiche.dateDebutSuivi)
        assertEquals(StatutPresence.PRESENT, env.magasin.presences.getValue(sarah to d11).statut) // historique intact
        assertEquals(StatutPresence.ABSENT, env.magasin.presences.getValue(sarah to d18).statut)
        assertEquals(StatutPresence.ABSENT, env.magasin.presences.getValue(sarah to d25).statut)
        assertTrue(env.enfants.observerActifs().first().any { it.id == sarah })
        assertTrue(env.enfants.observerArchives().first().isEmpty())
        // Elle figure de nouveau dans le prochain dimanche.
        assertTrue(env.presences.observerDimanche(LocalDate.parse("2026-11-01")).first().elements.any { it.enfant.id == sarah })
        assertFalse(env.enfants.desarchiver(sarah)) // déjà active
    }

    @Test
    fun desarchivage_leJourMeme_neCreeAucuneAbsence() = runTest {
        val env = Environnement(instantLocal("2026-10-06", 14))
        val (sarah, _, _) = troisEnfants(env)
        env.allerA("2026-10-14", 9, 0)
        env.presences.rattraper()
        env.enfants.archiver(sarah)
        val avant = env.magasin.presences.filterKeys { it.first == sarah }
        assertTrue(env.enfants.desarchiver(sarah))
        assertEquals(avant, env.magasin.presences.filterKeys { it.first == sarah })
    }

    // ------------------------------------------------------------------------------------------
    // Suppression définitive
    // ------------------------------------------------------------------------------------------

    @Test
    fun suppressionDefinitive_retireFicheHistoriqueEtPhoto_sansToucherAuxAutres() = runTest {
        val env = Environnement(instantLocal("2026-10-06", 14))
        val (sarah, david, _) = troisEnfants(env)
        env.allerA("2026-10-11", 9, 0)
        env.presences.pointer(sarah, d11, StatutPresence.PRESENT)
        env.presences.pointer(david, d11, StatutPresence.EN_RETARD)
        env.allerA("2026-10-19", 9, 0)
        env.presences.rattraper()
        val photoSarah = env.magasin.enfants.getValue(sarah).photoPath
        val photoDavid = env.magasin.enfants.getValue(david).photoPath

        assertTrue(env.enfants.supprimerDefinitivement(sarah))
        assertNull(env.magasin.enfants[sarah])
        assertTrue(env.magasin.presences.keys.none { it.first == sarah })
        assertFalse(photoSarah in env.photos.definitives)
        // David est intact.
        assertEquals(StatutPresence.EN_RETARD, env.magasin.presences.getValue(david to d11).statut)
        assertTrue(photoDavid in env.photos.definitives)
        assertEquals(CompteursAssiduite(0, 1, 1), compteurs(env, david))
        assertTrue(env.presences.observerDimanche(d11).first().elements.none { it.enfant.id == sarah })
        assertNull(env.presences.observerHistorique(sarah).first())
        assertFalse(env.enfants.supprimerDefinitivement(sarah)) // n'existe plus
    }

    @Test
    fun suppressionDefinitive_dUnEnfantArchiveSansPhoto() = runTest {
        val env = Environnement(instantLocal("2026-10-06", 14))
        val id = env.enfants.ajouter(donnees("Rabe", "Lova"), null)
        env.allerA("2026-10-20", 9, 0)
        env.presences.rattraper()
        env.enfants.archiver(id)
        assertTrue(env.enfants.supprimerDefinitivement(id))
        assertTrue(env.magasin.enfants.isEmpty())
        assertTrue(env.magasin.presences.isEmpty())
    }

    // ------------------------------------------------------------------------------------------
    // Ajout groupé (import CSV)
    // ------------------------------------------------------------------------------------------

    @Test
    fun ajoutGroupe_toutLeMondeSansPhoto_suiviAuProchainDimanche() = runTest {
        val env = Environnement(instantLocal("2026-10-06", 14))
        val n = env.enfants.ajouterPlusieurs(listOf(donnees("Rakoto", "Sarah"), donnees("Rabe", "David", "2015-11-02")))
        assertEquals(2, n)
        assertEquals(2, env.magasin.enfants.size)
        assertTrue(env.magasin.enfants.values.all { it.photoPath == PHOTO_NON_PRISE && it.dateDebutSuivi == d11 && !it.isArchived })
        assertEquals(
            setOf(IdentiteEnfant("Rakoto", "Sarah", LocalDate.parse("2017-03-15")), IdentiteEnfant("Rabe", "David", LocalDate.parse("2015-11-02"))),
            env.enfants.identitesExistantes().toSet(),
        )
        assertEquals(0, env.enfants.ajouterPlusieurs(emptyList()))
    }

    @Test
    fun ajoutGroupe_enCasDePanne_aucunEnfantNEstAjoute() = runTest {
        val env = Environnement(instantLocal("2026-10-06", 14))
        env.magasin.echecSurInsertionEnfant = true
        try {
            env.enfants.ajouterPlusieurs(listOf(donnees("Rakoto", "Sarah"), donnees("Rabe", "David")))
            fail("l'ajout aurait dû échouer")
        } catch (e: IllegalStateException) {
            // attendu
        }
        assertTrue(env.magasin.enfants.isEmpty())
    }

    private fun enregistre(statut: StatutPresence) = ResultatPointage.Enregistre(statut)
}
