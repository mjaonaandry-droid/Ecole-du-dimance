package mg.ecoledimanche.presences.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import mg.ecoledimanche.presences.data.local.AppDatabase
import mg.ecoledimanche.presences.data.local.CleParametre
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Migrations de la base sur un vrai SQLite (appareil ou émulateur ; exécuté par la CI).
 *
 * Chaque test part d'une base créée avec le schéma EXPORTÉ d'une ancienne version (app/schemas/.../N.json),
 * y met des données, applique les migrations déclarées dans [AppDatabase.MIGRATIONS], puis vérifie :
 * - que le schéma obtenu est exactement celui de la version actuelle (tables, colonnes, index, clés) ;
 * - que les données sont intactes.
 * À chaque nouvelle version de la base : ajouter ici un test « N → actuelle ».
 */
@RunWith(AndroidJUnit4::class)
class MigrationRoomTest {
    @get:Rule
    val aide = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun schemaActuel_estValide() {
        aide.createDatabase(NOM, AppDatabase.VERSION).close()
        aide.runMigrationsAndValidate(NOM, AppDatabase.VERSION, true, *AppDatabase.MIGRATIONS).close()
    }

    @Test
    fun migration1vers2_conserveLesDonnees_etMarqueLAccueilCommePasse() {
        aide.createDatabase(NOM, 1).apply {
            execSQL(
                "INSERT INTO enfants (id, nom, prenom, dateNaissance, sexe, adresse, nomPere, pereMembre, nomMere, mereMembre, " +
                    "nombreFreresSoeurs, nombreFreresSoeursMembres, photoPath, dateArriveeEglise, dateDebutSuivi, dateFinSuivi, " +
                    "isArchived, archivedAt, createdAt, updatedAt) VALUES (7, 'Rakoto', 'Sarah', '2017-03-15', 'FILLE', " +
                    "'Lot 1 Antananarivo', 'Père Rakoto', 1, NULL, NULL, 2, 1, '', NULL, '2026-10-04', NULL, 0, NULL, 1, 1)",
            )
            execSQL(
                "INSERT INTO seances (dateDimanche, zoneId, cloturePrevueAt, cloturee, clotureeAt, createdAt, updatedAt) " +
                    "VALUES ('2026-10-04', 'Indian/Antananarivo', 1, 1, 1, 1, 1)",
            )
            execSQL("INSERT INTO presences (enfantId, dateDimanche, statut, createdAt, updatedAt) VALUES (7, '2026-10-04', 'EN_RETARD', 1, 1)")
            close()
        }

        val base = aide.runMigrationsAndValidate(NOM, 2, true, AppDatabase.MIGRATION_1_2)
        base.query("SELECT nom, prenom, adresse, nombreFreresSoeurs FROM enfants WHERE id = 7").use { c ->
            assertEquals(true, c.moveToFirst())
            assertEquals("Rakoto", c.getString(0))
            assertEquals("Sarah", c.getString(1))
            assertEquals("Lot 1 Antananarivo", c.getString(2))
            assertEquals(2, c.getInt(3))
        }
        base.query("SELECT statut FROM presences WHERE enfantId = 7").use { c ->
            assertEquals(true, c.moveToFirst())
            assertEquals("EN_RETARD", c.getString(0))
        }
        base.query("SELECT valeur FROM parametres WHERE cle = '${CleParametre.ACCUEIL_FAIT}'").use { c ->
            assertEquals(true, c.moveToFirst())
            assertEquals("1", c.getString(0))
        }
        base.close()
    }

    /** La base migrée s'ouvre avec Room (même chemin qu'au démarrage de l'application après une mise à jour). */
    @Test
    fun baseVersion1_ouverteParLApplication_estMigreeAutomatiquement() {
        aide.createDatabase(NOM, 1).apply {
            execSQL(
                "INSERT INTO enfants (id, nom, prenom, dateNaissance, sexe, nombreFreresSoeurs, nombreFreresSoeursMembres, " +
                    "photoPath, dateDebutSuivi, isArchived, createdAt, updatedAt) " +
                    "VALUES (3, 'Rabe', 'David', '2015-11-02', 'GARCON', 0, 0, '', '2026-10-04', 0, 1, 1)",
            )
            close()
        }
        val contexte = InstrumentationRegistry.getInstrumentation().targetContext
        val base = Room.databaseBuilder(contexte, AppDatabase::class.java, NOM).addMigrations(*AppDatabase.MIGRATIONS).build()
        try {
            runBlocking {
                assertEquals("David", base.enfantDao().trouver(3)?.prenom)
                assertEquals("1", base.parametreDao().lire(CleParametre.ACCUEIL_FAIT))
            }
        } finally {
            base.close()
        }
    }

    private companion object {
        const val NOM = "test_migration.db"
    }
}
