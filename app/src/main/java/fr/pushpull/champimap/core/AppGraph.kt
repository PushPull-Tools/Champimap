package fr.pushpull.champimap.core

import android.content.Context
import fr.pushpull.champimap.core.db.AppDatabase
import fr.pushpull.champimap.core.db.ErrorLog
import fr.pushpull.champimap.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Point d'accès unique aux services partagés (injection manuelle, sans framework).
 * Initialisé une fois dans ChampimapApp ; utilisable partout via `AppGraph.db`, etc.
 */
object AppGraph {
    lateinit var appContext: Context
        private set
    lateinit var db: AppDatabase
        private set
    lateinit var settings: SettingsRepository
        private set

    /** Portée pour les travaux de fond qui doivent survivre à un écran (enregistrement, journal). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun init(context: Context) {
        appContext = context.applicationContext
        db = AppDatabase.build(appContext)
        settings = SettingsRepository(appContext)
    }
}

/** Nouvel identifiant unique (UUID texte) pour toute donnée. */
fun newId(): String = UUID.randomUUID().toString()

/**
 * Journal des erreurs : n'enregistre QUE ce qui n'a pas fonctionné.
 * Ne JAMAIS passer de coordonnées GPS ni de nom de spot dans `message` ou `detail`.
 */
object ErrorLogger {
    fun log(screen: String, message: String, error: Throwable? = null) {
        AppGraph.appScope.launch {
            runCatching {
                val dao = AppGraph.db.errorLogDao()
                dao.upsert(ErrorLog(screen = screen, message = message, detail = error?.stackTraceToString()?.take(4000)))
                dao.rotate(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)
            }
        }
    }
}
