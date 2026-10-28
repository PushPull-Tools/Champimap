package fr.pushpull.champimap.captures

import android.app.Application
import androidx.room.Room
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.AppDatabase

/*
 * Application utilisée UNIQUEMENT par les tests de captures d'écran (Robolectric).
 * Remplace ChampimapApp : la base est une base Room EN MÉMOIRE (vide au départ, remplie par
 * CaptureData selon le parcours), et la carte MapLibre est remplacée par une zone neutre
 * (AppGraph.testMode). Le vrai fichier champimap.db n'est jamais créé.
 */
class CaptureApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Requêtes autorisées sur le fil principal : les tests remplissent la base directement.
        val db = Room.inMemoryDatabaseBuilder(this, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        AppGraph.initForTests(this, db)
    }
}
