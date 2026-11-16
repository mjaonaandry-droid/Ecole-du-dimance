package mg.ecoledimanche.presences.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Import qui met à jour des enfants existants (version 1.2.0). */
class ImportMiseAJourTest {
    private val aujourdhui = LocalDate.parse("2026-11-16")

    private val sarah = EnfantConnu(
        7,
        DonneesEnfant(
            nom = "Rakoto",
            prenom = "Sarah",
            dateNaissance = LocalDate.parse("2017-03-15"),
            sexe = Sexe.FILLE,
            adresse = "Lot 1 Antananarivo",
            nomPere = "Jean Rakoto",
            pereMembre = true,
            nomMere = null,
            mereMembre = null,
            nombreFreresSoeurs = 2,
            nombreFreresSoeursMembres = 1,
            dateArrivee = LocalDate.parse("2020-01-05"),
        ),
    )
    private val david = EnfantConnu(
        9,
        sarah.donnees.copy(nom = "Rabe", prenom = "David", dateNaissance = LocalDate.parse("2015-11-02"), sexe = Sexe.GARCON, adresse = null),
    )

    private fun pret(vararg lignes: String, connus: List<EnfantConnu> = listOf(sarah, david)): AnalyseImport.Pret =
        ImportCsv.analyser(lignes.joinToString("\r\n"), aujourdhui, connus) as AnalyseImport.Pret

    @Test
    fun celluleVide_neChangeRien_celluleRemplie_remplace() {
        val r = pret(
            "Nom;Prénom;Sexe;Date de naissance;Adresse;Nom du père;Nom de la mère;Mère membre de l'église",
            "Rakoto;Sarah;Fille;15/03/2017;;;Hanta Rasoa;Oui",
        )
        val maj = r.aMettreAJour.single()
        assertEquals(7L, maj.id)
        assertEquals(listOf(ChampImport.NOM_MERE, ChampImport.MERE_MEMBRE), maj.champsModifies)
        assertEquals("Lot 1 Antananarivo", maj.apres.adresse) // vide dans le fichier : conservée
        assertEquals("Jean Rakoto", maj.apres.nomPere)
        assertEquals("Hanta Rasoa", maj.apres.nomMere)
        assertEquals(true, maj.apres.mereMembre)
        assertTrue(r.aAjouter.isEmpty())
        assertTrue(r.rejets.isEmpty())
    }

    @Test
    fun parIdentifiant_onPeutCorrigerLaDateDeNaissance() {
        val r = pret("Identifiant;Nom;Prénom;Sexe;Date de naissance", "7;Rakoto;Sarah;Fille;16/03/2017")
        assertEquals(LocalDate.parse("2017-03-16"), r.aMettreAJour.single().apres.dateNaissance)
        assertEquals(listOf(ChampImport.NAISSANCE), r.aMettreAJour.single().champsModifies)
    }

    @Test
    fun sansIdentifiant_uneDateDifferente_estUnNouvelEnfant() {
        val r = pret("Nom;Prénom;Sexe;Date de naissance", "Rakoto;Sarah;Fille;16/03/2017")
        assertEquals(1, r.aAjouter.size)
        assertTrue(r.aMettreAJour.isEmpty())
    }

    @Test
    fun identifiantDUnAutreTelephone_deuxDifferences_refuse() {
        // L'identifiant 7 existe, mais désigne Sarah Rakoto : « Paul Rabe » ne lui correspond pas.
        val r = pret("Identifiant;Nom;Prénom;Sexe;Date de naissance", "7;Rabe;Paul;Garçon;01/01/2016")
        assertEquals(MotifRejet.IDENTIFIANT_NE_CORRESPOND_PAS, r.rejets.single().motif)
        assertTrue(r.aMettreAJour.isEmpty() && r.aAjouter.isEmpty())
    }

    @Test
    fun identifiantInconnu_reconnaissanceParIdentite_sinonAjout() {
        val r = pret(
            "Identifiant;Nom;Prénom;Sexe;Date de naissance;Adresse",
            "450;Rakoto;Sarah;Fille;15/03/2017;Lot 2", // identifiant inconnu ici, mais identité connue : mise à jour
            "451;Ravelo;Fanja;Fille;10/05/2018;", // inconnue : ajoutée
        )
        assertEquals(7L, r.aMettreAJour.single().id)
        assertEquals("Lot 2", r.aMettreAJour.single().apres.adresse)
        assertEquals("Fanja", r.aAjouter.single().prenom)
    }

    @Test
    fun ligneSansChangement_compteeInchangee() {
        val r = pret("Identifiant;Nom;Prénom;Sexe;Date de naissance;Âge;Statut", "7;Rakoto;Sarah;Fille;15/03/2017;9;Actif")
        assertEquals(1, r.inchanges)
        assertTrue(r.aMettreAJour.isEmpty() && r.aAjouter.isEmpty() && r.rejets.isEmpty())
    }

    @Test
    fun enfantArchiveConnu_misAJour_inconnuIgnore() {
        val r = pret(
            "Nom;Prénom;Sexe;Date de naissance;Statut;Adresse",
            "Rabe;David;Garçon;02/11/2015;Archivé;Lot 9", // connu : mis à jour (l'archivage ne change pas)
            "Andry;Lova;Fille;20/01/2016;Archivé;", // inconnu : non recréé
        )
        assertEquals(9L, r.aMettreAJour.single().id)
        assertEquals(MotifRejet.ARCHIVE, r.rejets.single().motif)
    }

    @Test
    fun valeursInvalides_etIncoherences() {
        val r = pret(
            "Identifiant;Nom;Prénom;Sexe;Date de naissance;Père membre de l'église;Frères et sœurs;Frères et sœurs membres de l'église;Date d'arrivée dans l'église",
            "abc;Rakoto;Sarah;Fille;15/03/2017;;;;", // 2
            "7;Rakoto;Sarah;Fille;15/03/2017;Peut-être;;;", // 3
            "7;Rakoto;Sarah;Fille;15/03/2017;;-1;;", // 4
            "7;Rakoto;Sarah;Fille;15/03/2017;;;;demain", // 5
            "7;Rakoto;Sarah;Fille;15/03/2017;;1;3;", // 6 : plus de membres que de frères et sœurs
            "7;Rakoto;Sarah;Fille;15/03/2017;;;;01/01/2010", // 7 : arrivée avant la naissance
        )
        assertEquals(
            listOf(
                2 to MotifRejet.IDENTIFIANT_INVALIDE,
                3 to MotifRejet.OUI_NON_INVALIDE,
                4 to MotifRejet.NOMBRE_INVALIDE,
                5 to MotifRejet.DATE_ARRIVEE_INVALIDE,
                6 to MotifRejet.DONNEES_INCOHERENTES,
                7 to MotifRejet.DONNEES_INCOHERENTES,
            ),
            r.rejets.map { it.numeroLigne to it.motif },
        )
    }

    @Test
    fun memeEnfantDeuxFois_ouRenommeEnUnAutre_refuse() {
        val r = pret(
            "Identifiant;Nom;Prénom;Sexe;Date de naissance;Adresse",
            "7;Rakoto;Sarah;Fille;15/03/2017;A", // 2 : mise à jour
            "7;Rakoto;Sarah;Fille;15/03/2017;B", // 3 : même enfant une seconde fois
            "9;Rabe;Sarah;Garçon;15/03/2017;", // 4 : prénom et date diffèrent de David → refusé
            "9;Rakoto;David;Garçon;15/03/2017;", // 5 : nom et date diffèrent de David → refusé
        )
        assertEquals("A", r.aMettreAJour.single().apres.adresse)
        assertEquals(
            listOf(3 to MotifRejet.DOUBLON_DANS_LE_FICHIER, 4 to MotifRejet.IDENTIFIANT_NE_CORRESPOND_PAS, 5 to MotifRejet.IDENTIFIANT_NE_CORRESPOND_PAS),
            r.rejets.map { it.numeroLigne to it.motif },
        )
    }

    @Test
    fun renommageQuiCreeraitUnDoublon_refuseDejaInscrit() {
        // « Sara » (id 11) corrigée en « Sarah » : un seul écart, mais elle deviendrait le double exact de Sarah (id 7).
        val sarah2 = EnfantConnu(11, sarah.donnees.copy(prenom = "Sara"))
        val r = pret("Identifiant;Nom;Prénom;Sexe;Date de naissance", "11;Rakoto;Sarah;Fille;15/03/2017", connus = listOf(sarah, sarah2))
        assertEquals(MotifRejet.DEJA_INSCRIT, r.rejets.single().motif)
    }

    @Test
    fun allerRetour_exportCompletPuisImport_rienNeChange() {
        val tableau = TableauExport(
            dimanches = listOf(LocalDate.parse("2026-11-15")),
            lignes = listOf(sarah, david).map { e ->
                val d = e.donnees
                LigneExport(
                    d.nom, d.prenom, d.sexe, d.dateNaissance, false,
                    mapOf(LocalDate.parse("2026-11-15") to StatutPresence.PRESENT), CompteursAssiduite(1, 0, 0),
                    DetailsExport(
                        id = e.id, adresse = d.adresse, nomPere = d.nomPere, pereMembre = d.pereMembre, nomMere = d.nomMere,
                        mereMembre = d.mereMembre, nombreFreresSoeurs = d.nombreFreresSoeurs,
                        nombreFreresSoeursMembres = d.nombreFreresSoeursMembres, dateArrivee = d.dateArrivee,
                        debutSuivi = LocalDate.parse("2026-10-04"),
                    ),
                )
            },
        )
        val csv = ExportCsv.construire(tableau, aujourdhui, GroupeExport.TOUS)
        val r = ImportCsv.analyser(csv, aujourdhui, listOf(sarah, david)) as AnalyseImport.Pret
        assertEquals(2, r.inchanges)
        assertTrue(r.aAjouter.isEmpty() && r.aMettreAJour.isEmpty() && r.rejets.isEmpty())

        // Le même fichier sur un téléphone vide : les deux enfants sont ajoutés avec TOUTE leur fiche.
        val vierge = ImportCsv.analyser(csv, aujourdhui, emptyList()) as AnalyseImport.Pret
        assertEquals(listOf(sarah.donnees, david.donnees), vierge.aAjouter)
    }
}
