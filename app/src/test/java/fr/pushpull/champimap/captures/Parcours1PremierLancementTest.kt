package fr.pushpull.champimap.captures

import fr.pushpull.champimap.core.ui.StartupWarningScreen
import fr.pushpull.champimap.feature.connect.ConnectClient
import fr.pushpull.champimap.feature.connect.DeptProgress
import fr.pushpull.champimap.feature.connect.ItemKind
import fr.pushpull.champimap.feature.connect.ItemStatus
import fr.pushpull.champimap.feature.connect.SyncItem
import fr.pushpull.champimap.feature.connect.SyncPhase
import fr.pushpull.champimap.feature.connect.SyncSnapshot
import fr.pushpull.champimap.feature.connect.SyncUi
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.nav.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/*
 * Parcours 1 — premier lancement, comme un nouvel utilisateur :
 * avertissement Prudence → Bienvenue (prénom) → Départements → Champimap Connect (installation)
 * → Préparation des cartes → Accueil. Base vide au départ.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = TELEPHONE, application = CaptureApp::class)
class Parcours1PremierLancementTest : CaptureHarness("1-premier-lancement") {

    @Test
    fun premierLancement() {
        grantPermissions()
        start(startOnboarding = true)

        step("01-prudence") {
            CaptureHost.custom = { StartupWarningScreen(onDone = {}) }
            settle()
            capture("01-prudence", "Avertissement « Prudence »")
            CaptureHost.custom = null
            settle()
        }

        step("02-bienvenue") {
            waitText("Bienvenue dans Champimap")
            type("Pierre")
            capture("02-bienvenue", "Bienvenue (prénom)")
            click("Continuer")
            waitText("Où vas-tu aux champignons ?")
        }

        step("03-departements") {
            // Recherche puis coche (la liste est longue : l'élément doit être affiché pour être touché).
            listOf("Haute-Vienne", "Creuse", "Corrèze").forEach { nom ->
                replaceText(nom)
                click(nom, last = true)
            }
            replaceText("")
            capture("03-departements", "Départements")
            click("Continuer", last = true)
            waitText("Champimap ne donnera jamais")
        }

        step("04-connect") {
            capture("04-connect", "Champimap Connect (installation)")
            // Module « installé » (faux paquet) puis retour au premier plan : le bouton Continuer apparaît.
            installFakeConnect()
            compose.runOnIdle { ConnectClient.onResume(app) }
            waitText("Le module est installé")
            capture("05-connect-installe", "Champimap Connect (module installé)")
            click("Continuer", last = true)
            waitText("Préparation de tes cartes")
        }

        step("06-preparation-cartes") {
            // Avancement factice de la synchro (Connect n'existe pas sur la JVM).
            @Suppress("UNCHECKED_CAST")
            val sync = ConnectClient::class.java.getDeclaredField("_sync").apply { isAccessible = true }
                .get(ConnectClient) as MutableStateFlow<SyncUi>
            compose.runOnIdle { sync.value = fakeSync() }
            settle()
            capture("06-preparation-cartes", "Préparation des cartes")
            // Fin du premier lancement comme « Continuer, ça finit en arrière-plan » (réglage + Accueil).
            // Fait directement : sous Robolectric, la reprise de la coroutine de l'écran après DataStore
            // n'a pas lieu sur le fil principal et la navigation est refusée.
            runBlocking { AppGraph.settings.setOnboardingDone(true) }
            compose.runOnIdle {
                CaptureHost.nav!!.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            }
            waitRoute("home")
        }

        step("07-accueil") {
            waitText("Pierre")
            capture("07-accueil", "Accueil (premier lancement terminé)")
        }

        finish()
    }

    /** Synchro en cours : 62 %, Haute-Vienne finie, Corrèze en cours, Creuse en attente. */
    private fun fakeSync(): SyncUi {
        val depts = listOf(
            DeptProgress("87", "Haute-Vienne", 100, ItemStatus.DONE),
            DeptProgress("19", "Corrèze", 64, ItemStatus.RUNNING),
            DeptProgress("23", "Creuse", 0, ItemStatus.WAITING),
        )
        val items = listOf(
            SyncItem("c87", ItemKind.CONTOUR, "Contour · Haute-Vienne", "87", ItemStatus.DONE, 100, null),
            SyncItem("c19", ItemKind.CONTOUR, "Contour · Corrèze", "19", ItemStatus.DONE, 100, null),
            SyncItem("c23", ItemKind.CONTOUR, "Contour · Creuse", "23", ItemStatus.DONE, 100, null),
            SyncItem("u87", ItemKind.URGENCES, "Urgences · Haute-Vienne", "87", ItemStatus.DONE, 100, null),
            SyncItem("u19", ItemKind.URGENCES, "Urgences · Corrèze", "19", ItemStatus.RUNNING, 40, null),
            SyncItem("m87", ItemKind.CARTE, "Carte · Haute-Vienne", "87", ItemStatus.DONE, 100, "212 Mo"),
            SyncItem("m19", ItemKind.CARTE, "Carte · Corrèze", "19", ItemStatus.RUNNING, 64, "128 / 201 Mo"),
            SyncItem("m23", ItemKind.CARTE, "Carte · Creuse", "23", ItemStatus.WAITING, 0, null),
            SyncItem("meteo", ItemKind.METEO, "Météo des zones larges", null, ItemStatus.WAITING, 0, null),
            SyncItem("ia", ItemKind.IA, "Modèle d'identification", null, ItemStatus.WAITING, 0, null),
        )
        return SyncUi(
            phase = SyncPhase.RUNNING,
            snapshot = SyncSnapshot(syncId = "demo", running = true, percent = 62, step = "Cartes", departements = depts, items = items),
        )
    }
}
