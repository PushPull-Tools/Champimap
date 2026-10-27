package fr.pushpull.champimap.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Base de données locale de Champimap. Tout reste sur le téléphone.
 * Les énumérations sont stockées par leur nom (convertisseurs Room par défaut).
 */
@Database(
    entities = [
        Person::class, Sortie::class, TrackPoint::class, Spot::class, SpotPosition::class,
        SpotVisit::class, Recolte::class, RecolteItem::class, Photo::class, Species::class,
        WeatherDay::class, IntoxFiche::class, Battue::class, ErrorLog::class, Hospital::class,
        UnlockedBadge::class, // Carnet : succès obtenus
        Vente::class, // Mode vendeur (expérimental) : ventes
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun personDao(): PersonDao
    abstract fun sortieDao(): SortieDao
    abstract fun spotDao(): SpotDao
    abstract fun recolteDao(): RecolteDao
    abstract fun photoDao(): PhotoDao
    abstract fun speciesDao(): SpeciesDao
    abstract fun weatherDao(): WeatherDao
    abstract fun statsDao(): StatsDao
    abstract fun syncDao(): SyncDao
    abstract fun backupDao(): BackupDao
    abstract fun errorLogDao(): ErrorLogDao
    abstract fun intoxDao(): IntoxDao
    abstract fun hospitalDao(): HospitalDao
    abstract fun battueDao(): BattueDao
    abstract fun carnetDao(): CarnetDao // Carnet : niveaux et succès
    abstract fun venteDao(): VenteDao // Mode vendeur (expérimental)

    companion object {
        /** Nom du fichier de base (réutilisé par la sauvegarde / restauration). */
        const val FILE_NAME = "champimap.db"

        /**
         * Version 1 → 2 : ajout de la table `vente` (mode vendeur).
         * Le SQL reprend EXACTEMENT ce que Room génère pour l'entité Vente (colonnes, NOT NULL,
         * clé primaire, index) : sinon Room refuse d'ouvrir la base à la validation du schéma.
         * Aucune donnée existante n'est touchée.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `vente` (`id` TEXT NOT NULL, `soldAt` INTEGER NOT NULL, " +
                        "`weightG` INTEGER NOT NULL, `pricePerKgCents` INTEGER NOT NULL, `speciesId` TEXT, " +
                        "`sortieIds` TEXT, `note` TEXT, `updatedAt` INTEGER NOT NULL, `deletedAt` INTEGER, " +
                        "PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_vente_soldAt` ON `vente` (`soldAt`)")
            }
        }

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, FILE_NAME)
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
