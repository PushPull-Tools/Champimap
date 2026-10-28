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

    /**
     * Synchro en cours (format des tâches de Connect 4), dans l'ordre réel : Haute-Vienne finie, Corrèze en
     * cours (carte à 64 %), Creuse en attente, puis le bloc « Général ». 24 % pondéré par les octets estimés
     * (cartes ≈ 35 Mo, modèle ≈ 140 Mo).
     */
    private fun fakeSync(): SyncUi {
        val depts = listOf(
            DeptProgress("87", "Haute-Vienne", 100, ItemStatus.DONE),
            DeptProgress("19", "Corrèze", 65, ItemStatus.RUNNING),
            DeptProgress("23", "Creuse", 0, ItemStatus.WAITING),
        )
        /** Tâche d'un département (libellé court + groupe « 87 · Haute-Vienne »). */
        fun dept(
            id: String, kind: ItemKind, title: String, short: String, code: String, name: String,
            status: ItemStatus, percent: Int, detail: String?, size: Long? = null,
        ) = SyncItem(id, kind, "$short · $name", code, status, percent, detail, title, "$code · $name", size)
        /** Tâche du bloc « Général ». */
        fun general(id: String, kind: ItemKind, label: String) =
            SyncItem(id, kind, label, null, ItemStatus.WAITING, 0, null)
        val items = listOf(
            dept("contour_87", ItemKind.CONTOUR, "Contour du département", "Contour", "87", "Haute-Vienne", ItemStatus.DONE, 100, "212 Ko"),
            dept("urgences_87", ItemKind.URGENCES, "Urgences les plus proches", "Urgences", "87", "Haute-Vienne", ItemStatus.DONE, 100, "4 hôpitaux avec urgences"),
            dept("carte_87", ItemKind.CARTE, "Carte", "Carte", "87", "Haute-Vienne", ItemStatus.DONE, 100, "34,1 Mo · 18 412 tuiles · zoom 15", 35_756_032L),
            dept("contour_19", ItemKind.CONTOUR, "Contour du département", "Contour", "19", "Corrèze", ItemStatus.DONE, 100, "187 Ko"),
            dept("urgences_19", ItemKind.URGENCES, "Urgences les plus proches", "Urgences", "19", "Corrèze", ItemStatus.DONE, 100, "3 hôpitaux avec urgences"),
            dept("carte_19", ItemKind.CARTE, "Carte", "Carte", "19", "Corrèze", ItemStatus.RUNNING, 64, "Tuiles 11 802 sur 18 440"),
            dept("contour_23", ItemKind.CONTOUR, "Contour du département", "Contour", "23", "Creuse", ItemStatus.WAITING, 0, null),
            dept("urgences_23", ItemKind.URGENCES, "Urgences les plus proches", "Urgences", "23", "Creuse", ItemStatus.WAITING, 0, null),
            dept("carte_23", ItemKind.CARTE, "Carte", "Carte", "23", "Creuse", ItemStatus.WAITING, 0, null),
            general("antipoison", ItemKind.ANTIPOISON, "Centres antipoison"),
            general("meteo", ItemKind.METEO, "Météo de tes sorties · 2 zones"),
            general("ia_versions", ItemKind.IA, "Liste des versions du modèle"),
            general("ia", ItemKind.IA, "Modèle d'identification"),
        )
        return SyncUi(
            phase = SyncPhase.RUNNING,
            message = "Carte · Corrèze",
            snapshot = SyncSnapshot(
                syncId = "demo", running = true, percent = 24, step = "Carte · Corrèze",
                departements = depts, items = items, format = 2,
            ),
        )
    }
}
