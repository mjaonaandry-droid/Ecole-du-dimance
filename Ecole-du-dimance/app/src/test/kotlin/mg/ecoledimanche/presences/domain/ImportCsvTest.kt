package mg.ecoledimanche.presences.domain

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportCsvTest {
    private val aujourdhui = LocalDate.parse("2026-10-12")

    private fun pret(texte: String, existants: Collection<IdentiteEnfant> = emptyList()): AnalyseImport.Pret =
        ImportCsv.analyser(texte, aujourdhui, existants) as AnalyseImport.Pret

    @Test
    fun relitLeFichierProduitParLExport_sansLesArchives() {
        val tableau = TableauExport(
            dimanches = listOf(LocalDate.parse("2026-10-11")),
            lignes = listOf(
                LigneExport("Rakoto", "Sarah", Sexe.FILLE, LocalDate.parse("2017-03-15"), false, mapOf(LocalDate.parse("2026-10-11") to StatutPresence.PRESENT), CompteursAssiduite(1, 0, 0)),
                LigneExport("Rabe", "David", Sexe.GARCON, LocalDate.parse("2015-11-02"), false, emptyMap(), CompteursAssiduite.VIDE),
                LigneExport("Andry", "Lova", Sexe.FILLE, LocalDate.parse("2016-01-20"), true, emptyMap(), CompteursAssiduite.VIDE),
                // Un nom qui commence par « - » est protégé par une apostrophe dans l'export.
                LigneExport("-Razafy", "Hery", Sexe.GARCON, LocalDate.parse("2018-07-09"), false, emptyMap(), CompteursAssiduite.VIDE),
            ),
        )
        val csv = ExportCsv.construire(tableau, aujourdhui)
        val resultat = pret(csv)
        assertEquals(
            listOf(
                Triple("Rakoto", "Sarah", Sexe.FILLE),
                Triple("Rabe", "David", Sexe.GARCON),
                Triple("-Razafy", "Hery", Sexe.GARCON),
            ),
            resultat.aAjouter.map { Triple(it.nom, it.prenom, it.sexe) },
        )
        assertEquals(LocalDate.parse("2017-03-15"), resultat.aAjouter.first().dateNaissance)
        assertEquals(listOf(LigneRejetee(4, "Lova Andry", MotifRejet.ARCHIVE)), resultat.rejets)
        // Les champs absents de l'export restent vides.
        assertTrue(resultat.aAjouter.all { it.adresse == null && it.nomPere == null && it.nombreFreresSoeurs == 0 })
    }

    @Test
    fun fichierMinimal_enTetesSansAccentsNiCasse_virguleEtFinsDeLigneUnix() {
        val resultat = pret("NOM,PRENOM,SEXE,DATE DE NAISSANCE\nRakoto,Sarah,F,15/03/2017\nRabe,David,G,2/11/2015\n")
        assertEquals(listOf("Sarah", "David"), resultat.aAjouter.map { it.prenom })
        assertEquals(listOf(Sexe.FILLE, Sexe.GARCON), resultat.aAjouter.map { it.sexe })
        assertEquals(LocalDate.parse("2015-11-02"), resultat.aAjouter[1].dateNaissance)
        assertTrue(resultat.rejets.isEmpty())
    }

    @Test
    fun colonnesDansUnAutreOrdre_etColonnesInconnuesIgnorees() {
        val resultat = pret("Date de naissance;Remarque;Sexe;Prénom;Nom\r\n15/03/2017;rien;Fille;Sarah;Rakoto\r\n")
        val sarah = resultat.aAjouter.single()
        assertEquals("Rakoto", sarah.nom)
        assertEquals("Sarah", sarah.prenom)
    }

    @Test
    fun colonnesManquantes_sontNommees() {
        val resultat = ImportCsv.analyser("Nom;Prénom;Âge\nRakoto;Sarah;9\n", aujourdhui, emptyList())
        assertEquals(AnalyseImport.ColonnesManquantes(listOf("Sexe", "Date de naissance")), resultat)
    }

    @Test
    fun fichierVide_ouEnTeteSeul() {
        assertEquals(AnalyseImport.FichierVide, ImportCsv.analyser("", aujourdhui, emptyList()))
        assertEquals(AnalyseImport.FichierVide, ImportCsv.analyser("\r\n ; \r\n", aujourdhui, emptyList()))
        val seul = pret("Nom;Prénom;Sexe;Date de naissance\r\n")
        assertTrue(seul.aAjouter.isEmpty() && seul.rejets.isEmpty())
    }

    @Test
    fun lignesInvalides_motifEtNumeroDeLigne_lesLignesVidesComptent() {
        val texte = listOf(
            "Nom;Prénom;Sexe;Date de naissance",
            "Rakoto;Sarah;Fille;15/03/2017", // 2 : valide
            "", // 3 : vide, ignorée sans erreur
            ";Paul;Garçon;01/01/2016", // 4
            "Rabe;;Garçon;01/01/2016", // 5
            "Rabe;Jean;;01/01/2016", // 6
            "Rabe;Marc;X;01/01/2016", // 7
            "Rabe;Luc;Garçon;", // 8
            "Rabe;Tina;Fille;31/02/2016", // 9
            "Rabe;Noa;Fille;01/01/2030", // 10
            "Rabe;Mia;Fille;hier", // 11
            "${"R".repeat(101)};Long;Fille;01/01/2016", // 12
        ).joinToString("\r\n")
        val resultat = pret(texte)
        assertEquals(listOf("Sarah"), resultat.aAjouter.map { it.prenom })
        assertEquals(
            listOf(
                4 to MotifRejet.NOM_MANQUANT,
                5 to MotifRejet.PRENOM_MANQUANT,
                6 to MotifRejet.SEXE_MANQUANT,
                7 to MotifRejet.SEXE_INVALIDE,
                8 to MotifRejet.NAISSANCE_MANQUANTE,
                9 to MotifRejet.NAISSANCE_INVALIDE,
                10 to MotifRejet.NAISSANCE_FUTURE,
                11 to MotifRejet.NAISSANCE_INVALIDE,
                12 to MotifRejet.TEXTE_TROP_LONG,
            ),
            resultat.rejets.map { it.numeroLigne to it.motif },
        )
        assertEquals("Paul", resultat.rejets.first().libelle)
    }

    @Test
    fun doublons_dejaInscrits_etDansLeFichier_casseEtAccentsIgnores() {
        val existants = listOf(IdentiteEnfant("Rakoto", "Éva", LocalDate.parse("2017-03-15")))
        val resultat = pret(
            "Nom;Prénom;Sexe;Date de naissance\n" +
                "RAKOTO;Eva;Fille;15/03/2017\n" + // déjà inscrite
                "Rabe;David;Garçon;02/11/2015\n" +
                "rabe ; david ;garcon;2/11/2015\n" + // même identité plus haut dans le fichier
                "Rabe;David;Garçon;02/11/2016\n", // homonyme né un autre jour : accepté
            existants,
        )
        assertEquals(2, resultat.aAjouter.size)
        assertEquals(listOf(2 to MotifRejet.DEJA_INSCRIT, 4 to MotifRejet.DOUBLON_DANS_LE_FICHIER), resultat.rejets.map { it.numeroLigne to it.motif })
    }

    @Test
    fun guillemets_separateurEtSautDeLigneDansUnChamp() {
        val resultat = pret("Nom;Prénom;Sexe;Date de naissance\n\"Rakoto; Rabe\";\"Sa\"\"rah\";Fille;15/03/2017\n\"Rabe\nJr\";Paul;Garçon;01/01/2016\nRabe;Bad;X;01/01/2016\n")
        assertEquals(listOf("Rakoto; Rabe", "Rabe\nJr"), resultat.aAjouter.map { it.nom })
        assertEquals("Sa\"rah", resultat.aAjouter.first().prenom)
        assertEquals(5, resultat.rejets.single().numeroLigne) // le saut de ligne dans le champ compte
    }

    @Test
    fun formatsDeDate() {
        val attendu = LocalDate.parse("2017-03-05")
        for (texte in listOf("05/03/2017", "5/3/2017", "05-03-2017", "05.03.2017", "2017-03-05", "05/03/17", "05/03/2017 00:00")) {
            assertEquals(texte, attendu, ImportCsv.lireDate(texte, aujourdhui))
        }
        assertEquals(LocalDate.parse("1990-03-05"), ImportCsv.lireDate("05/03/90", aujourdhui))
        assertNull(ImportCsv.lireDate("31/02/2017", aujourdhui))
        assertNull(ImportCsv.lireDate("2017", aujourdhui))
        assertNull(ImportCsv.lireDate("45000", aujourdhui))
    }

    @Test
    fun encodages_utf8AvecBom_etWindows1252DExcel() {
        val texte = "Nom;Prénom;Sexe;Date de naissance\r\nRakoto;Hélène;Fille;15/03/2017\r\n"
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals(texte, ImportCsv.decoder(bom + texte.toByteArray(Charsets.UTF_8)))
        assertEquals(texte, ImportCsv.decoder(texte.toByteArray(Charsets.UTF_8)))
        assertEquals(texte, ImportCsv.decoder(texte.toByteArray(charset("windows-1252"))))
        assertEquals("Hélène", pret(ImportCsv.decoder(texte.toByteArray(charset("windows-1252")))).aAjouter.single().prenom)
    }

    @Test
    fun separateur_detecteSurLEnTete() {
        assertEquals(';', ImportCsv.detecterSeparateur("Nom;Prénom;Sexe\na,b,c,d,e"))
        assertEquals(',', ImportCsv.detecterSeparateur("Nom,Prénom,Sexe"))
        assertEquals('\t', ImportCsv.detecterSeparateur("Nom\tPrénom\tSexe"))
        assertEquals(';', ImportCsv.detecterSeparateur("Nom"))
        assertEquals(',', ImportCsv.detecterSeparateur("\"a;b;c\",x,y"))
    }

    @Test
    fun apostropheDeProtection_retireeSeulementDevantUnCaractereDeFormule() {
        assertEquals("=1+1", ImportCsv.nettoyer("'=1+1"))
        assertEquals("-Rakoto", ImportCsv.nettoyer(" '-Rakoto "))
        assertEquals("'Sarah", ImportCsv.nettoyer("'Sarah"))
        assertEquals("N'Dri", ImportCsv.nettoyer("N'Dri"))
    }
}
