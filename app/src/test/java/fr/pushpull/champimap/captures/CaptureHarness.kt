package fr.pushpull.champimap.captures

import android.Manifest
import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.settings.ThemeMode
import fr.pushpull.champimap.core.ui.theme.ChampimapTheme
import fr.pushpull.champimap.feature.connect.ConnectProtocol
import fr.pushpull.champimap.nav.AppNav
import org.junit.Rule
import org.robolectric.Shadows.shadowOf
import java.io.File

/*
 * Socle des captures d'écran automatiques (Robolectric, rendu natif, + Roborazzi).
 *
 * Principe : UN contenu Compose par parcours (setContent n'est possible qu'une fois par test), qui affiche
 * la vraie navigation de l'appli (AppNav) dans le vrai thème (ChampimapTheme). Le test ouvre les écrans
 * (routes ou clics comme un utilisateur) et, pour chaque état intéressant, enregistre 3 PNG :
 * thème clair, sombre et Psyché (party) — le thème est changé sans recréer l'écran (état conservé).
 * Un écran qui échoue est noté dans _manquants.txt et le parcours continue.
 *
 * Sorties (dossier du module app) : build/captures/<parcours>/<NN-nom>-<theme>.png
 *  + _manifeste.txt (ordre et libellés pour la planche index.html) + _manquants.txt.
 */

/** Taille d'un téléphone typique : 1080 × 2400 px à 420 dpi (Pixel 7). */
const val TELEPHONE = "w411dp-h914dp-normal-long-notround-any-420dpi-keyshidden-nonav"

/** Thèmes capturés et suffixes des fichiers. */
private val THEMES = listOf(ThemeMode.CLAIR to "clair", ThemeMode.SOMBRE to "sombre", ThemeMode.PSYCHE to "psyche")

/** État partagé entre le test et le contenu Compose. */
object CaptureHost {
    /** Thème affiché (remplace celui des réglages, sans toucher à l'écran courant). */
    var theme by mutableStateOf(ThemeMode.CLAIR)

    /** Écran affiché par-dessus la navigation (écrans hors navigation : avertissement, résultat de l'IA). */
    var custom by mutableStateOf<(@Composable () -> Unit)?>(null)

    /** Contrôleur de navigation de l'appli (fourni à AppNav). */
    var nav: NavHostController? = null
}

/** Contenu racine des captures : même structure que MainActivity, sans verrou ni avertissement. */
@Composable
fun CaptureRoot(startOnboarding: Boolean) {
    val settings by AppGraph.settings.flow.collectAsState(initial = null)
    val s = settings ?: return
    ChampimapTheme(s.copy(themeMode = CaptureHost.theme, psycheUnlocked = true)) {
        val nav = rememberNavController()
        SideEffect { CaptureHost.nav = nav }
        Box(Modifier.fillMaxSize()) {
            AppNav(startOnboarding = startOnboarding, importPending = false, nav = nav)
            CaptureHost.custom?.let { screen -> Box(Modifier.fillMaxSize()) { screen() } }
        }
    }
}

/** Base des tests de parcours : règle Compose, outils de navigation, de clic et de capture. */
@OptIn(ExperimentalRoborazziApi::class)
abstract class CaptureHarness(private val parcours: String) {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    protected val app: Application get() = ApplicationProvider.getApplicationContext()

    private val outDir: File by lazy { File("build/captures/$parcours").absoluteFile.apply { mkdirs() } }
    private val manifest = mutableListOf<String>()
    private val failures = mutableListOf<String>()

    // --- Démarrage / fin -----------------------------------------------------------------------

    /** Autorisations Android accordées d'office (position, appareil photo, notifications, Bluetooth). */
    protected fun grantPermissions() {
        shadowOf(app).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    }

    /** Simule un Champimap Connect installé (version 0.3.0, code 3) dans le gestionnaire de paquets. */
    @Suppress("DEPRECATION")
    protected fun installFakeConnect() {
        val info = PackageInfo().apply {
            packageName = ConnectProtocol.CONNECT_PACKAGE
            versionName = "0.3.0"
            versionCode = 3
            longVersionCode = 3
            applicationInfo = ApplicationInfo().apply { packageName = ConnectProtocol.CONNECT_PACKAGE }
        }
        shadowOf(app.packageManager).installPackage(info)
    }

    /** Affiche l'appli (une seule fois par test). */
    protected fun start(startOnboarding: Boolean) {
        compose.setContent { CaptureRoot(startOnboarding) }
        settle()
    }

    /** Écrit le manifeste et la liste des écrans manquants (appelé en fin de parcours). */
    protected fun finish() {
        File(outDir, "_manifeste.txt").writeText(manifest.joinToString("\n", postfix = "\n"))
        File(outDir, "_manquants.txt").writeText(
            if (failures.isEmpty()) "" else failures.joinToString("\n", postfix = "\n"),
        )
        println("[captures] $parcours : ${manifest.size} écrans capturés, ${failures.size} manquants")
        failures.forEach { println("[captures] MANQUANT $it") }
    }

    /** Exécute une étape ; une erreur est notée (écran manquant) sans arrêter le parcours. */
    protected fun step(name: String, block: () -> Unit) {
        println("[captures] $parcours / $name …")
        try {
            block()
        } catch (t: Throwable) {
            failures += "$name : ${t.javaClass.simpleName} : ${t.message?.lineSequence()?.firstOrNull()?.take(300)}"
            t.printStackTrace()
            runCatching {
                compose.mainClock.autoAdvance = true
                CaptureHost.custom = null
                CaptureHost.theme = ThemeMode.CLAIR
            }
        }
    }

    // --- Attente -------------------------------------------------------------------------------

    /** Laisse l'interface se stabiliser : animations terminées, données Room et images chargées. */
    protected fun settle(rounds: Int = 4) {
        repeat(rounds) {
            compose.waitForIdle()
            Thread.sleep(60)
            shadowOf(Looper.getMainLooper()).idle()
        }
        compose.waitForIdle()
    }

    /** Avance l'horloge de Compose à la main (animations figées à mi-parcours). */
    protected fun advanceFrozen(ms: Long, stepMs: Long = 50) {
        var t = 0L
        while (t < ms) {
            Thread.sleep(15)
            shadowOf(Looper.getMainLooper()).idle()
            compose.mainClock.advanceTimeBy(stepMs)
            t += stepMs
        }
    }

    /** Attend qu'un texte apparaisse (données chargées en arrière-plan). */
    protected fun waitText(text: String, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMs) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodes(matcher(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        settle()
    }

    /** Attend qu'un texte disparaisse. */
    protected fun waitGone(text: String, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMs) {
            shadowOf(Looper.getMainLooper()).idle()
            compose.onAllNodes(matcher(text), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()
        }
        settle()
    }

    /** Attend que la route courante commence par [prefix]. */
    protected fun waitRoute(prefix: String, timeoutMs: Long = 10_000) {
        compose.waitUntil(timeoutMs) {
            shadowOf(Looper.getMainLooper()).idle()
            CaptureHost.nav?.currentDestination?.route?.startsWith(prefix) == true
        }
        settle()
    }

    // --- Actions -------------------------------------------------------------------------------

    private fun matcher(text: String): SemanticsMatcher =
        hasText(text, substring = true) or hasContentDescription(text, substring = true)

    /** Ouvre une route de l'appli. */
    protected fun go(route: String) {
        compose.runOnIdle { CaptureHost.nav!!.navigate(route) }
        settle()
    }

    /** Revient à l'écran précédent. */
    protected fun back() {
        compose.runOnIdle { CaptureHost.nav!!.popBackStack() }
        settle()
    }

    /**
     * Touche l'élément qui contient [text] (texte ou description) : bouton, carte, onglet…
     * [last] = dernier trouvé (sinon le premier).
     */
    protected fun click(text: String, last: Boolean = false) {
        val merged = compose.onAllNodes(matcher(text))
        val count = merged.fetchSemanticsNodes().size
        val nodes = if (count > 0) merged else compose.onAllNodes(matcher(text), useUnmergedTree = true)
        val n = nodes.fetchSemanticsNodes().size
        check(n > 0) { "Élément introuvable : « $text »" }
        val node = nodes[if (last) n - 1 else 0]
        runCatching { node.performScrollTo() }
        node.performClick()
        settle()
    }

    /** Touche l'élément dont le texte est EXACTEMENT [text] (onglets, pastilles de période). */
    protected fun clickExact(text: String) {
        val nodes = compose.onAllNodes(hasText(text, substring = false), useUnmergedTree = true)
        check(nodes.fetchSemanticsNodes().isNotEmpty()) { "Élément introuvable : « $text »" }
        nodes.onFirst().performClick()
        settle()
    }

    /** Saisit [text] dans le premier champ de saisie. */
    protected fun type(text: String) {
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextInput(text)
        settle()
    }

    /** Remplace le contenu du premier champ de saisie (recherche). */
    protected fun replaceText(text: String) {
        compose.onAllNodes(hasSetTextAction()).onFirst().performTextReplacement(text)
        settle()
    }

    /** Fait défiler la plus grande zone défilante verticale de [px] pixels ; faux si déjà en bas. */
    protected fun scrollDown(px: Float = 1700f): Boolean {
        val m = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)
        val all = compose.onAllNodes(m)
        val nodes = all.fetchSemanticsNodes()
        if (nodes.isEmpty()) return false
        val i = nodes.indices.maxBy { nodes[it].boundsInRoot.height * nodes[it].boundsInRoot.width }
        val range = nodes[i].config[SemanticsProperties.VerticalScrollAxisRange]
        if (range.value() >= range.maxValue() - 1f) return false
        val before = range.value()
        all[i].performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, px) }
        settle()
        // Position relue sur un nœud neuf (l'ancien peut garder une valeur périmée).
        // Moins de 300 px parcourus (bas atteint) : pas de capture presque en double.
        val now = all.fetchSemanticsNodes()[i].config[SemanticsProperties.VerticalScrollAxisRange].value()
        return now > before + 300f
    }

    /** Remonte tout en haut de la plus grande zone défilante. */
    protected fun scrollTop() {
        val m = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
            SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy)
        val all = compose.onAllNodes(m)
        val nodes = all.fetchSemanticsNodes()
        if (nodes.isEmpty()) return
        val i = nodes.indices.maxBy { nodes[it].boundsInRoot.height * nodes[it].boundsInRoot.width }
        all[i].performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, -100_000f) }
        settle()
    }

    // --- Captures ------------------------------------------------------------------------------

    /**
     * Enregistre l'écran courant en 3 thèmes : <name>-clair.png, -sombre.png, -psyche.png.
     * [label] = nom lisible sous l'image dans la planche. [frozen] = horloge arrêtée (transition en cours).
     */
    protected fun capture(name: String, label: String, frozen: Boolean = false) {
        for ((mode, suffix) in THEMES) {
            compose.runOnUiThread { CaptureHost.theme = mode }
            if (frozen) {
                compose.mainClock.advanceTimeByFrame()
                shadowOf(Looper.getMainLooper()).idle()
            } else {
                settle(2)
            }
            captureScreenRoboImage(File(outDir, "$name-$suffix.png").absolutePath)
        }
        compose.runOnUiThread { CaptureHost.theme = ThemeMode.CLAIR }
        if (frozen) compose.mainClock.advanceTimeByFrame() else settle(2)
        manifest += "$name\t$label"
    }

    /**
     * Capture un écran long en plusieurs hauteurs (« -1 », « -2 »…), en faisant défiler entre deux,
     * jusqu'à [maxPages] ou jusqu'en bas.
     */
    protected fun captureLong(name: String, label: String, maxPages: Int = 3) {
        capture("$name-1", "$label (1)")
        for (p in 2..maxPages) {
            if (!scrollDown()) break
            capture("$name-$p", "$label ($p)")
        }
        scrollTop()
    }
}
