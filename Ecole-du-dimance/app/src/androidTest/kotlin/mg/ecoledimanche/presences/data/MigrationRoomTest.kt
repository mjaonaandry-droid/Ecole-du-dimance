package mg.ecoledimanche.presences.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import mg.ecoledimanche.presences.data.local.AppDatabase
import mg.ecoledimanche.presences.data.local.CleParametre
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migrations de la base sur un vrai SQLite (appareil ou émulateur ; exécuté par la CI).
 *
 * Chaque test fabrique une base EXACTEMENT comme une ancienne version de l'application l'a laissée, à partir
 * du schéma exporté (app/schemas/.../N.json, inclus dans les ressources des tests), y met des données, puis
 * l'ouvre avec Room comme au démarrage de l'application après une mise à jour. Room applique les
 * migrations de [AppDatabase.MIGRATIONS] puis VÉRIFIE que le schéma obtenu est exactement celui attendu
 * (tables, colonnes, index, clés) : il lève une exception sinon. Les tests vérifient ensuite les données.
 *
 * (Volontairement sans `MigrationTestHelper` de room-testing : sa version de kotlinx-serialization entre en
 * conflit avec celle de l'application dans l'APK de test.)
 *
 * À chaque nouvelle version de la base : ajouter ici un test « N → actuelle » avec des données.
 */
@RunWith(AndroidJUnit4::class)
class MigrationRoomTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val contexte: Context = instrumentation.targetContext

    @After
    fun nettoyer() {
        contexte.deleteDatabase(NOM)
    }

    /** Base créée avec le schéma exporté de la [version] donnée (comme l'aurait fait cette version). */
    private fun creerBase(version: Int, remplir: (SQLiteDatabase) -> Unit = {}) {
        contexte.deleteDatabase(NOM)
        val fichier = contexte.getDatabasePath(NOM).apply { parentFile?.mkdirs() }
        val texte = instrumentation.context.assets.open("$DOSSIER_SCHEMAS/$version.json").bufferedReader().use { it.readText() }
        val schema = JSONObject(texte).getJSONObject("database")
        assertEquals(version, schema.getInt("version"))
        val base = SQLiteDatabase.openOrCreateDatabase(fichier, null)
        try {
            val entites = schema.getJSONArray("entities")
            for (i in 0 until entites.length()) {
                val entite = entites.getJSONObject(i)
                val table = entite.getString("tableName")
                base.execSQL(entite.getString("createSql").replace("\${TABLE_NAME}", table))
                val index = entite.optJSONArray("indices") ?: continue
                for (j in 0 until index.length()) base.execSQL(index.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val requetes = schema.getJSONArray("setupQueries")
            for (i in 0 until requetes.length()) base.execSQL(requetes.getString(i))
            remplir(base)
            base.version = version
        } finally {
            base.close()
        }
    }

    /** Ouverture comme au démarrage de l'application : migrations, puis validation du schéma par Room. */
    private fun ouvrir(): AppDatabase =
        Room.databaseBuilder(contexte, AppDatabase::class.java, NOM).addMigrations(*AppDatabase.MIGRATIONS).build()

    @Test
    fun schemaExporteActuel_correspondAuCodeDeLApplication() {
        // Une base au schéma 2.json (empreinte comprise) doit s'ouvrir sans migration ni erreur d'intégrité.
        creerBase(AppDatabase.VERSION)
        val base = ouvrir()
        try {
            runBlocking { assertEquals(0, base.maintenanceDao().nombreEnfants()) }
            assertEquals(AppDatabase.VERSION, base.openHelper.readableDatabase.version)
        } finally {
            base.close()
        }
    }

    @Test
    fun migration1versActuelle_conserveLesDonnees_etMarqueLAccueilCommePasse() {
        creerBase(1) { db ->
            db.execSQL(
                "INSERT INTO enfants (id, nom, prenom, dateNaissance, sexe, adresse, nomPere, pereMembre, nomMere, mereMembre, " +
                    "nombreFreresSoeurs, nombreFreresSoeursMembres, photoPath, dateArriveeEglise, dateDebutSuivi, dateFinSuivi, " +
                    "isArchived, archivedAt, createdAt, updatedAt) VALUES (7, 'Rakoto', 'Sarah', '2017-03-15', 'FILLE', " +
                    "'Lot 1 Antananarivo', 'Père Rakoto', 1, NULL, NULL, 2, 1, '', NULL, '2026-10-04', NULL, 0, NULL, 1, 1)",
            )
            db.execSQL(
                "INSERT INTO seances (dateDimanche, zoneId, cloturePrevueAt, cloturee, clotureeAt, createdAt, updatedAt) " +
                    "VALUES ('2026-10-04', 'Indian/Antananarivo', 1, 1, 1, 1, 1)",
            )
            db.execSQL("INSERT INTO presences (enfantId, dateDimanche, statut, createdAt, updatedAt) VALUES (7, '2026-10-04', 'EN_RETARD', 1, 1)")
        }

        val base = ouvrir()
        try {
            runBlocking {
                val sarah = base.enfantDao().trouver(7)
                assertEquals("Sarah", sarah?.prenom)
                assertEquals("Lot 1 Antananarivo", sarah?.adresse)
                assertEquals("Père Rakoto", sarah?.nomPere)
                assertEquals(true, sarah?.pereMembre)
                assertEquals(2, sarah?.nombreFreresSoeurs)
                assertEquals("EN_RETARD", base.presenceDao().toutes().single().statut.name)
                assertTrue(base.seanceDao().trouver(java.time.LocalDate.parse("2026-10-04"))!!.cloturee)
                assertEquals("1", base.parametreDao().lire(CleParametre.ACCUEIL_FAIT))
                assertTrue(base.examenDao().tous().isEmpty())
            }
            assertEquals(AppDatabase.VERSION, base.openHelper.readableDatabase.version)
        } finally {
            base.close()
        }
    }

    @Test
    fun migration2vers3_ajouteExamensEtNotes_sansToucherAuReste() {
        creerBase(2) { db ->
            db.execSQL(
                "INSERT INTO enfants (id, nom, prenom, dateNaissance, sexe, nombreFreresSoeurs, nombreFreresSoeursMembres, " +
                    "photoPath, dateDebutSuivi, isArchived, createdAt, updatedAt) " +
                    "VALUES (4, 'Rabe', 'David', '2015-11-02', 'GARCON', 0, 0, '', '2026-10-04', 0, 1, 1)",
            )
            db.execSQL("INSERT INTO parametres (cle, valeur) VALUES ('${CleParametre.ACCUEIL_FAIT}', '1')")
            db.execSQL("INSERT INTO parametres (cle, valeur) VALUES ('${CleParametre.HORLOGE_DERNIERE}', '1790000000000')")
        }
        val base = ouvrir()
        try {
            runBlocking {
                assertEquals("David", base.enfantDao().trouver(4)?.prenom)
                assertEquals("1790000000000", base.parametreDao().lire(CleParametre.HORLOGE_DERNIERE))
                // Les nouvelles tables fonctionnent, clés étrangères comprises.
                val examen = base.examenDao().inserer(
                    mg.ecoledimanche.presences.data.local.ExamenEntity(
                        intitule = "Examen",
                        date = java.time.LocalDate.parse("2026-11-15"),
                        createdAt = java.time.Instant.EPOCH,
                        updatedAt = java.time.Instant.EPOCH,
                    ),
                )
                base.examenDao().ecrireNote(mg.ecoledimanche.presences.data.local.NoteEntity(examen, 4, 1250, java.time.Instant.EPOCH, java.time.Instant.EPOCH))
                assertEquals(1250, base.examenDao().notesDe(examen).single().centiemes)
            }
            assertEquals(3, base.openHelper.readableDatabase.version)
        } finally {
            base.close()
        }
    }

    @Test
    fun baseVersion1Vide_migree_lAccueilNeSAffichePas() {
        // Même une installation 1.1.0 sans enfant est une installation existante : pas d'écran d'accueil.
        creerBase(1)
        val base = ouvrir()
        try {
            runBlocking { assertEquals("1", base.parametreDao().lire(CleParametre.ACCUEIL_FAIT)) }
        } finally {
            base.close()
        }
    }

    private companion object {
        const val NOM = "test_migration.db"
        const val DOSSIER_SCHEMAS = "mg.ecoledimanche.presences.data.local.AppDatabase"
    }
}
