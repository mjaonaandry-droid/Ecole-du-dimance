package mg.ecoledimanche.presences.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportCsvTest {
    private val aujourdhui = LocalDate.parse("2026-10-19")
    private val d1 = LocalDate.parse("2026-10-11")
    private val d2 = LocalDate.parse("2026-10-18")

    /** Marque UTF-8 attendue en tête du fichier (voir ExportCsv). */
    private val BOM: String = 0xFEFF.toChar().toString()

    private fun ligne(
        nom: String = "Rakoto",
        prenom: String = "Sarah",
        sexe: Sexe = Sexe.FILLE,
        naissance: String = "2017-03-15",
        archive: Boolean = false,
        statuts: Map<LocalDate, StatutPresence> = emptyMap(),
        compteurs: CompteursAssiduite = CompteursAssiduite.VIDE,
    ) = LigneExport(nom, prenom, sexe, LocalDate.parse(naissance), archive, statuts, compteurs)

    /** Lignes du fichier, sans le BOM, en vérifiant que le séparateur de ligne est bien CRLF. */
    private fun lignes(csv: String): List<String> {
        assertTrue("BOM UTF-8 attendu pour Excel", csv.startsWith(BOM))
        assertTrue("fin de ligne CRLF attendue", csv.endsWith("\r\n"))
        return csv.removePrefix(BOM).removeSuffix("\r\n").split("\r\n")
    }

    @Test
    fun entete_infosDeBase_puisUneColonneParDimanche_puisLesComptes() {
        val csv = ExportCsv.construire(TableauExport(listOf(d1, d2), emptyList()), aujourdhui)
        assertEquals(
            listOf("Nom;Prénom;Sexe;Date de naissance;Âge;Statut;11/10/2026;18/10/2026;Présences;Retards;Absences;Assiduité"),
            lignes(csv),
        )
    }

    @Test
    fun uneLigne_infosDeBaseStatutsEtAssiduite() {
        val enfant = ligne(
            statuts = mapOf(d1 to StatutPresence.PRESENT, d2 to StatutPresence.EN_RETARD),
            compteurs = CompteursAssiduite(1, 1, 0),
        )
        val csv = ExportCsv.construire(TableauExport(listOf(d1, d2), listOf(enfant)), aujourdhui)
        assertEquals("Rakoto;Sarah;Fille;15/03/2017;9;Actif;Présent;En retard;1;1;0;100 %", lignes(csv)[1])
    }

    @Test
    fun garcon_archive_absent_etAssiduiteDecimaleAvecVirgule() {
        val enfant = ligne(
            nom = "Rabe", prenom = "Tiana", sexe = Sexe.GARCON, naissance = "2015-12-31", archive = true,
            statuts = mapOf(d1 to StatutPresence.ABSENT),
            compteurs = CompteursAssiduite(6, 1, 1),
        )
        val csv = ExportCsv.construire(TableauExport(listOf(d1), listOf(enfant)), aujourdhui)
        assertEquals("Rabe;Tiana;Garçon;31/12/2015;10;Archivé;Absent;6;1;1;87,5 %", lignes(csv)[1])
    }

    @Test
    fun cellulesVides_enfantNonSuiviCeJourLa_seanceOuverteNonPointee_etAucuneSeanceCloturee() {
        val enfant = ligne(statuts = mapOf(d2 to StatutPresence.NON_ENREGISTRE)) // d1 : non suivi, d2 : pas encore pointé
        val csv = ExportCsv.construire(TableauExport(listOf(d1, d2), listOf(enfant)), aujourdhui)
        assertEquals("Rakoto;Sarah;Fille;15/03/2017;9;Actif;;;0;0;0;", lignes(csv)[1]) // pas de « — » ni de faux 0 %
    }

    @Test
    fun sansDimanche_seulesLesInfosDeBaseEtLesComptesSontExportes() {
        val csv = ExportCsv.construire(TableauExport(emptyList(), listOf(ligne())), aujourdhui)
        assertEquals(
            listOf("Nom;Prénom;Sexe;Date de naissance;Âge;Statut;Présences;Retards;Absences;Assiduité", "Rakoto;Sarah;Fille;15/03/2017;9;Actif;0;0;0;"),
            lignes(csv),
        )
    }

    @Test
    fun leFichierNeContientQueLesInfosDeBase_pasDeParentsNiDAdresseNiDePhoto() {
        val entete = lignes(ExportCsv.construire(TableauExport(listOf(d1), emptyList()), aujourdhui)).single()
        for (interdit in listOf("père", "mère", "Adresse", "photo", "frères", "arrivée")) {
            assertFalse("« $interdit » ne doit pas être exporté", entete.contains(interdit, ignoreCase = true))
        }
    }

    @Test
    fun champsAvecSeparateurGuillemetOuSautDeLigne_sontProteges() {
        assertEquals("\"Rakoto; Jean\"", ExportCsv.champ("Rakoto; Jean"))
        assertEquals("\"Ra\"\"koto\"\"\"", ExportCsv.champ("Ra\"koto\""))
        assertEquals("\"a\nb\"", ExportCsv.champ("a\nb"))
        assertEquals("Jean-Pierre", ExportCsv.champ("Jean-Pierre"))
        assertEquals("", ExportCsv.champ(""))
    }

    @Test
    fun valeurQuiRessembleAUneFormule_estNeutralisee() {
        for (debut in listOf("=1+1", "+33", "-2", "@SOMME(A1)", "\tx")) {
            assertEquals("'$debut", ExportCsv.champ(debut))
        }
        val csv = ExportCsv.construire(TableauExport(emptyList(), listOf(ligne(nom = "=CMD()"))), aujourdhui)
        assertTrue(lignes(csv)[1].startsWith("'=CMD();Sarah;"))
    }

    @Test
    fun leFichierCommenceParLesOctetsUtf8DuBom_EF_BB_BF_etLesAccentsSontEncodesEnUtf8() {
        val csv = ExportCsv.construire(TableauExport(emptyList(), listOf(ligne(prenom = "Éloïse"))), aujourdhui)
        val octets = csv.toByteArray(Charsets.UTF_8)
        assertEquals(listOf(0xEF, 0xBB, 0xBF), octets.take(3).map { it.toInt() and 0xFF })
        assertTrue(String(octets, Charsets.UTF_8).contains("Éloïse"))
    }

    @Test
    fun nomDeFichier_contientLaDateIso() {
        assertEquals("presences-ecole-du-dimanche-2026-10-09.csv", ExportCsv.nomFichier(LocalDate.parse("2026-10-09")))
    }

    // --- Export complet ou partiel (version 1.2.0) ------------------------------------------------

    private val complet = ligne(statuts = mapOf(d1 to StatutPresence.PRESENT), compteurs = CompteursAssiduite(1, 0, 0)).copy(
        details = DetailsExport(
            id = 7,
            adresse = "Lot 1; Antananarivo",
            nomPere = "Jean Rakoto",
            pereMembre = true,
            nomMere = "Hanta",
            mereMembre = null,
            nombreFreresSoeurs = 2,
            nombreFreresSoeursMembres = 1,
            dateArrivee = LocalDate.parse("2020-01-05"),
            debutSuivi = LocalDate.parse("2026-10-04"),
            finSuivi = null,
        ),
    )

    @Test
    fun exportComplet_toutesLesColonnes_dansLOrdre() {
        val csv = lignes(ExportCsv.construire(TableauExport(listOf(d1), listOf(complet)), aujourdhui, GroupeExport.TOUS))
        assertEquals(
            "Identifiant;Nom;Prénom;Sexe;Date de naissance;Âge;Statut;Adresse;Nom du père;Père membre de l'église;" +
                "Nom de la mère;Mère membre de l'église;Frères et sœurs;Frères et sœurs membres de l'église;" +
                "Date d'arrivée dans l'église;Début du suivi;Fin du suivi;11/10/2026;Présences;Retards;Absences;Assiduité;" +
                "Total des notes;Moyenne sur 20",
            csv[0],
        )
        assertEquals(
            "7;Rakoto;Sarah;Fille;15/03/2017;9;Actif;\"Lot 1; Antananarivo\";Jean Rakoto;Oui;Hanta;;2;1;05/01/2020;04/10/2026;;Présent;1;0;0;100 %;;",
            csv[1],
        )
    }

    @Test
    fun exportPartiel_seulementLesGroupesChoisis_identiteToujoursPresente() {
        val csv = lignes(ExportCsv.construire(TableauExport(listOf(d1), listOf(complet)), aujourdhui, setOf(GroupeExport.PARENTS)))
        assertEquals("Nom;Prénom;Sexe;Date de naissance;Nom du père;Père membre de l'église;Nom de la mère;Mère membre de l'église", csv[0])
        assertEquals("Rakoto;Sarah;Fille;15/03/2017;Jean Rakoto;Oui;Hanta;", csv[1])

        val minimal = lignes(ExportCsv.construire(TableauExport(listOf(d1), listOf(complet)), aujourdhui, emptySet()))
        assertEquals(listOf("Nom;Prénom;Sexe;Date de naissance", "Rakoto;Sarah;Fille;15/03/2017"), minimal)
    }

    @Test
    fun exportPartiel_seRelitAvecLImport() {
        val csv = ExportCsv.construire(TableauExport(listOf(d1), listOf(complet)), aujourdhui, setOf(GroupeExport.IDENTIFIANT, GroupeExport.ADRESSE))
        val r = ImportCsv.analyser(csv, aujourdhui, emptyList()) as AnalyseImport.Pret
        assertEquals("Lot 1; Antananarivo", r.aAjouter.single().adresse)
    }
}
