package fr.pushpull.champimap

import android.app.Application
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.feature.spot.SpeciesLoader
import kotlinx.coroutines.launch

/** Application : initialise les services et capture les plantages dans le journal. */
class ChampimapApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)

        // Plantage non rattrapé : on l'inscrit au journal, puis on laisse Android gérer.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                AppGraph.db.errorLogDao().let { /* écriture synchrone impossible ici sans risque */ }
                ErrorLogger.log("Plantage", "L'appli s'est arrêtée de façon inattendue", e)
            }
            previous?.uncaughtException(t, e)
        }

        // Premier lancement : chargement de la liste des espèces depuis les assets.
        AppGraph.appScope.launch { SpeciesLoader.ensureLoaded(this@ChampimapApp) }
    }
}
