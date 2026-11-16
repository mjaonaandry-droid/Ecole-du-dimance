package mg.ecoledimanche.presences.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import mg.ecoledimanche.presences.data.repository.ExecuteurTransaction

/**
 * Base Room de l'application.
 *
 * Historique des versions (à compléter à chaque évolution, avec le schéma `app/schemas/.../N.json`) :
 * - 1 (application 1.0.0 et 1.1.0) : enfants, seances, presences ;
 * - 2 (application 1.2.0) : + table `parametres` (accueil passé, garde-fou de l'horloge).
 */
@Database(
    entities = [EnfantEntity::class, SeanceEntity::class, PresenceEntity::class, ParametreEntity::class],
    version = AppDatabase.VERSION,
    exportSchema = true,
)
@TypeConverters(Convertisseurs::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun enfantDao(): EnfantDao
    abstract fun seanceDao(): SeanceDao
    abstract fun presenceDao(): PresenceDao
    abstract fun parametreDao(): ParametreDao
    abstract fun maintenanceDao(): MaintenanceDao

    companion object {
        const val NOM_FICHIER = "ecole_dimanche.db"

        /** Version actuelle du schéma. La sauvegarde complète l'enregistre (voir `FormatSauvegarde`). */
        const val VERSION = 2

        /**
         * 1 → 2 : création de la table `parametres`. Une base qui existe déjà vient d'une installation en
         * service : l'écran d'accueil (« commencer vide / restaurer ») ne doit pas s'y afficher.
         * Le SQL est identique, au caractère près, à celui du schéma exporté 2.json (vérifié par MigrationRoomTest).
         */
        val MIGRATION_1_2: Migration = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `parametres` (`cle` TEXT NOT NULL, `valeur` TEXT NOT NULL, PRIMARY KEY(`cle`))",
                )
                db.execSQL("INSERT OR IGNORE INTO `parametres` (`cle`, `valeur`) VALUES ('${CleParametre.ACCUEIL_FAIT}', '1')")
            }
        }

        /**
         * Migrations explicites, dans l'ordre. Toute évolution du schéma :
         * 1. incrémente [VERSION] ;
         * 2. ajoute ici une migration N → N+1 (jamais de migration destructive : les données doivent survivre) ;
         * 3. versionne le schéma `app/schemas/.../N+1.json` généré par la compilation ;
         * 4. ajoute un test dans `MigrationRoomTest` (exécuté sur émulateur par la CI).
         * Room enchaîne les migrations : une base 1 passe en 2 puis en 3, etc., à la première ouverture
         * après la mise à jour de l'application (ou à la restauration d'une ancienne sauvegarde).
         */
        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)

        fun creer(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NOM_FICHIER)
                .addMigrations(*MIGRATIONS)
                .build()
    }
}

/** Exécute un bloc dans une transaction Room (validée en bloc ou annulée en bloc). */
class ExecuteurTransactionRoom(private val base: RoomDatabase) : ExecuteurTransaction {
    override suspend fun <T> executer(bloc: suspend () -> T): T = base.withTransaction { bloc() }
}
