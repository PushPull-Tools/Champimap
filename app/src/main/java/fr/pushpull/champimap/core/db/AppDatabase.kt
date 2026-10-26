package fr.pushpull.champimap.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * Base de données locale de Champimap. Tout reste sur le téléphone.
 * Les énumérations sont stockées par leur nom (convertisseurs Room par défaut).
 */
@Database(
    entities = [
        Person::class, Sortie::class, TrackPoint::class, Spot::class, SpotPosition::class,
        SpotVisit::class, Recolte::class, RecolteItem::class, Photo::class, Species::class,
        WeatherDay::class, IntoxFiche::class, Battue::class, ErrorLog::class, Hospital::class,
    ],
    version = 1,
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

    companion object {
        /** Nom du fichier de base (réutilisé par la sauvegarde / restauration). */
        const val FILE_NAME = "champimap.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, FILE_NAME).build()
    }
}
