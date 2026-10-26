package fr.pushpull.champimap.feature.sortie

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.util.Consumer
import androidx.navigation.NavController
import fr.pushpull.champimap.nav.Routes
import kotlinx.coroutines.flow.MutableStateFlow

/*
 * Appuis sur la notification de sortie : « ouvrir la sortie » ou « Marquer un spot ».
 * La notification ouvre MainActivity avec deux extras ; `SortieIntentHandler(nav)` les lit
 * (au lancement ET quand l'appli est déjà ouverte) et navigue vers l'écran de sortie.
 * La demande « Marquer un spot » est gardée dans `pendingMarkSpot` jusqu'à ce que
 * SortieScreen l'affiche.
 */
object SortieIntents {
    const val EXTRA_ACTION = "fr.pushpull.champimap.sortie.ACTION"
    const val EXTRA_SORTIE_ID = "fr.pushpull.champimap.sortie.SORTIE_ID"
    const val OPEN = "open"
    const val MARK_SPOT = "mark_spot"

    /** Sortie pour laquelle « Marquer un spot » a été demandé depuis la notification. */
    val pendingMarkSpot = MutableStateFlow<String?>(null)

    /**
     * Lit un Intent ; renvoie l'identifiant de sortie à ouvrir (ou null si l'Intent ne nous concerne pas).
     * Les extras sont retirés pour ne pas traiter deux fois la même demande.
     */
    fun consume(intent: Intent?): String? {
        val action = intent?.getStringExtra(EXTRA_ACTION) ?: return null
        val id = intent.getStringExtra(EXTRA_SORTIE_ID)
        intent.removeExtra(EXTRA_ACTION)
        intent.removeExtra(EXTRA_SORTIE_ID)
        if (id.isNullOrBlank()) return null
        if (action == MARK_SPOT) pendingMarkSpot.value = id
        return id
    }
}

/** Remonte d'un Context Compose jusqu'à l'activité. */
fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Traite les appuis sur la notification de sortie et navigue vers la sortie concernée.
 * Appelé par les écrans du module sortie ; à appeler aussi une fois dans AppNav (voir rapport)
 * pour que l'appui fonctionne depuis n'importe quel écran.
 * @param currentSortieId sortie déjà affichée (aucune navigation si c'est la même).
 */
@Composable
fun SortieIntentHandler(nav: NavController, currentSortieId: String? = null) {
    val activity = LocalContext.current.findActivity() as? ComponentActivity ?: return
    DisposableEffect(activity, currentSortieId) {
        fun handle(intent: Intent?) {
            val id = SortieIntents.consume(intent) ?: return
            if (id != currentSortieId) {
                runCatching { nav.navigate(Routes.sortie(id)) { launchSingleTop = true } }
            }
        }
        handle(activity.intent)
        val listener = Consumer<Intent> { handle(it) }
        activity.addOnNewIntentListener(listener)
        onDispose { activity.removeOnNewIntentListener(listener) }
    }
}
