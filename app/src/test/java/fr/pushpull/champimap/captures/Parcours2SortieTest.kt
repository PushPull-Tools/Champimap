package fr.pushpull.champimap.captures

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.location.Location
import android.location.LocationManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.Species
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.feature.bonus.SortieTransitions
import fr.pushpull.champimap.feature.sortie.GpsFix
import fr.pushpull.champimap.feature.sortie.SortieTracker
import fr.pushpull.champimap.feature.spot.IaResultsPanel
import fr.pushpull.champimap.feature.spot.SpeciesLoader
import fr.pushpull.champimap.nav.Routes
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.hasText
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/*
 * Parcours 2 — une sortie complète :
 * Accueil → Départ → transition de démarrage → Sortie en cours → Marquer un spot → fiche Spot
 * → « Trouvé ! » → choix d'espèce → propositions de l'IA (factices) → récolte de 2 espèces
 * → Je suis perdu → fin de sortie (récap, XP, succès).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = TELEPHONE, application = CaptureApp::class)
class Parcours2SortieTest : CaptureHarness("2-sortie") {

    @Test
    fun sortie() {
        grantPermissions()
        installFakeConnect()
        runBlocking {
            SpeciesLoader.ensureLoaded(app)
            CaptureData.reglages()
            CaptureData.historique()
        }
        start(startOnboarding = false)
        val id = CaptureData.SORTIE_EN_COURS

        step("01-accueil") {
            waitText("Pierre")
            capture("01-accueil", "Accueil")
        }

        step("02-depart") {
            go(Routes.DEPART)
            waitText("Je suis garé")
            capture("02-depart", "Départ (garé / à pied)")
        }

        step("03-transition") {
            // Sortie créée comme par « C'est parti ! », position GPS factice.
            runBlocking { CaptureData.sortieEnCours() }
            val (lat, lon) = CaptureData.positionActuelle()
            SortieTracker.publishFix(GpsFix(lat, lon, 5f, System.currentTimeMillis()))
            SortieTransitions.start.request(id)
            // Horloge arrêtée : la phrase de départ est figée à mi-parcours.
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { CaptureHost.nav!!.navigate(Routes.sortie(id)) { popUpTo(Routes.HOME) } }
            advanceFrozen(1200)
            capture("03-transition-depart", "Transition de démarrage (phrase de départ)", frozen = true)
            compose.mainClock.autoAdvance = true
            waitGone("Touche l'écran pour passer", 15_000)
        }

        step("04-sortie-en-cours") {
            waitText("Marquer un spot")
            capture("04-sortie-en-cours", "Sortie en cours (carte remplacée par une zone neutre)")
        }

        step("05-marquer-spot") {
            click("Marquer un spot")
            simulateGps()
            capture("05-marquer-spot", "Marquer un spot (mesure de précision)")
        }

        step("06-fiche-spot") {
            go(Routes.spot(CaptureData.SPOT_FICHE, id))
            waitText("Talus aux girolles")
            captureLong("06-fiche-spot", "Fiche spot (infos, actions)", maxPages = 2)
        }

        step("07-trouve") {
            click("Trouvé !")
            waitText("Liste")
            capture("07-trouve", "« Trouvé ! » (Photo / Liste)")
        }

        step("08-choix-espece") {
            runBlocking { CaptureData.recolteVide() }
            go(Routes.especesPicker(CaptureData.RECOLTE_EN_COURS))
            waitText("Complète ta cueillette")
            capture("08-choix-espece", "Choix d'espèce (liste A–Z)")
            back()
        }

        val photo = fakePhoto(app)
        step("09-ia-propositions") {
            CaptureHost.custom = { IaScreen(photo, PISTES_CONFIANTES) }
            waitText("Cèpe de Bordeaux")
            capture("09-ia-propositions", "Propositions de l'IA (5 pistes, une MORTEL)")
        }
        step("10-ia-incertaine") {
            CaptureHost.custom = { IaScreen(photo, PISTES_INCERTAINES) }
            waitText("incertaine")
            capture("10-ia-incertaine", "Propositions de l'IA (identification incertaine)")
            CaptureHost.custom = null
            settle()
        }

        step("11-recolte") {
            runBlocking { CaptureData.recolteDeuxEspeces() }
            go(Routes.spot(CaptureData.SPOT_FICHE, id))
            waitText("Girolle")
            // Déplie la ligne des girolles (la ligne des cèpes reste repliée).
            settle(6)
            // Dépliée = nom latin affiché ; sinon on touche la ligne des girolles.
            val ouverte = compose.onAllNodes(hasText("Cantharellus cibarius"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
            if (!ouverte) click("Girolle", last = true)
            scrollDown(900f)
            capture("11-recolte", "Récolte : 2 espèces (une dépliée)")
        }

        step("12-perdu") {
            go(Routes.perdu(id))
            settle()
            capture("12-perdu", "Je suis perdu")
        }

        step("13-fin-sortie") {
            // Retour sur la sortie puis fin : fermeture en cercle → récap animé (joué jusqu'au bout).
            compose.runOnIdle { CaptureHost.nav!!.navigate(Routes.sortie(id)) { popUpTo(Routes.HOME) } }
            waitText("Marquer un spot")
            runBlocking { AppGraph.db.sortieDao().end(id, System.currentTimeMillis()) }
            waitRoute("fin", 15_000)
            settle(8)
            // Un toucher saute à l'état final des animations (XP de la sortie, succès débloqués).
            click("Sortie terminée")
            settle(4)
            captureLong("13-fin-sortie", "Fin de sortie (récap, XP, succès)", maxPages = 3)
        }

        finish()
    }

    /** Positions GPS successives de plus en plus précises (jauge de la mesure). */
    private fun simulateGps() {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val (lat, lon) = CaptureData.positionActuelle()
        val accuracies = listOf(14f, 11f, 9f, 8f, 7f, 6f, 5.5f, 5f, 4.6f, 4.2f, 4f, 3.8f)
        accuracies.forEachIndexed { i, acc ->
            val loc = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = lat + (i % 3 - 1) * 0.00002
                longitude = lon + (i % 2) * 0.00002
                accuracy = acc
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
            }
            shadowOf(lm).simulateLocation(loc)
            settle(1)
        }
        settle()
    }

    private companion object {
        /** Pistes factices « confiantes » : la 1re au-dessus du seuil, une MORTEL parmi les 5. */
        val PISTES_CONFIANTES = listOf(
            "boletus-edulis" to 0.71f, "boletus-pinophilus" to 0.12f, "imleria-badia" to 0.07f,
            "amanita-phalloides" to 0.04f, "boletus-aereus" to 0.02f,
        )

        /** Pistes factices « incertaines » : la 1re sous 60 %, pourcentages masqués. */
        val PISTES_INCERTAINES = listOf(
            "cantharellus-cibarius" to 0.41f, "omphalotus-olearius" to 0.22f, "amanita-phalloides" to 0.15f,
            "hydnum-repandum" to 0.09f, "cantharellus-pallens" to 0.05f,
        )
    }
}

/** Écran de résultat de l'IA tel qu'affiché par « Complète ta cueillette », avec des pistes factices. */
@Composable
private fun IaScreen(photo: File, pistes: List<Pair<String, Float>>) {
    val all by remember { AppGraph.db.speciesDao().all() }.collectAsState(initial = emptyList())
    val byId = remember(all) { all.associateBy(Species::id) }
    val proposals = remember(byId) { pistes.mapNotNull { (id, p) -> byId[id]?.let { it to p } } }
    ChampiScreen(title = "Complète ta cueillette", onBack = {}) {
        if (proposals.isNotEmpty()) {
            IaResultsPanel(
                photo = photo, proposals = proposals, all = all,
                onPick = {}, onOpenSpecies = {}, onShowList = {}, modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Photo factice (dessin d'un champignon sur fond de mousse), enregistrée dans le dossier privé. */
private fun fakePhoto(context: Context): File {
    val file = File(context.filesDir, "photo-demo.jpg")
    val bmp = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    c.drawColor(Color.rgb(74, 98, 52))
    p.color = Color.rgb(96, 122, 66)
    for (i in 0 until 40) c.drawCircle((i * 97 % 800).toFloat(), (i * 53 % 600).toFloat(), 30f + i % 5 * 6, p)
    p.color = Color.rgb(232, 222, 196)
    c.drawRoundRect(RectF(355f, 300f, 445f, 540f), 30f, 30f, p)
    p.color = Color.rgb(122, 74, 38)
    c.drawArc(RectF(230f, 170f, 570f, 400f), 180f, 180f, true, p)
    file.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    return file
}
