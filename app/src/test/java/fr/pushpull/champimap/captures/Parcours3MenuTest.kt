package fr.pushpull.champimap.captures

import fr.pushpull.champimap.feature.carnet.CarnetTextes
import fr.pushpull.champimap.feature.spot.SpeciesLoader
import fr.pushpull.champimap.nav.Routes
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/*
 * Parcours 3 — le menu : onglets du bas (Accueil, Carte, Stats, Espèces, Plus) et tous les écrans
 * qu'on ouvre depuis eux. Utilisateur installé avec une saison de données factices, mode vendeur actif.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = TELEPHONE, application = CaptureApp::class)
class Parcours3MenuTest : CaptureHarness("3-menu") {

    @Test
    fun menu() {
        grantPermissions()
        installFakeConnect()
        // Liste des versions du modèle reçue de Connect (menu déroulant de l'écran Connect).
        File(app.filesDir, "ia_versions.json").writeText(
            """{"versions":[
              {"version":"ia-v3","especes":2140,"top1":78.4,"tailleModeleMo":38},
              {"version":"ia-v2","especes":1520,"top1":75.1,"tailleModeleMo":36},
              {"version":"ia-v1","especes":1032,"top1":73.8,"tailleModeleMo":35}]}""",
        )
        runBlocking {
            SpeciesLoader.ensureLoaded(app)
            CaptureData.reglages(sellerMode = true)
            CaptureData.historique()
        }
        start(startOnboarding = false)

        step("01-accueil") {
            waitText("Pierre")
            captureLong("01-accueil", "Accueil", maxPages = 2)
        }
        step("02-carte") {
            go(Routes.CARTE)
            capture("02-carte", "Carte (zone neutre à la place du fond de carte)")
        }
        step("03-stats") {
            go(Routes.STATS)
            settle()
            listOf("Jour" to "jour", "Semaine" to "semaine", "Mois" to "mois", "Saison" to "saison").forEach { (label, key) ->
                clickExact(label)
                capture("03-stats-$key", "Stats : $label")
            }
            captureLong("03-stats-saison-detail", "Stats : Saison (suite)", maxPages = 3)
        }
        step("04-calendrier") {
            go(Routes.CALENDRIER)
            capture("04-calendrier", "Calendrier")
        }
        step("05-tresors") {
            go(Routes.TRESORS)
            capture("05-tresors", "Carte aux trésors")
        }
        step("06-especes") {
            go(Routes.CATALOGUE)
            waitText("Amanite")
            capture("06-especes", "Espèces (liste)")
        }
        step("07-fiche-espece") {
            go(Routes.ficheEspece("boletus-edulis"))
            waitText("Cèpe de Bordeaux")
            captureLong("07-fiche-espece", "Fiche espèce (photos absentes)", maxPages = 2)
        }
        step("08-carnet") {
            go(Routes.CARNET)
            settle()
            captureLong("08-carnet", "Mon carnet", maxPages = 2)
            click(CarnetTextes.INFO_XP_TITRE)
            capture("09-carnet-xp", "Mon carnet : ce qui rapporte de l'XP")
            back()
        }
        step("10-parametres") {
            go(Routes.PARAMETRES)
            waitText("Profil")
            captureLong("10-parametres", "Plus / Paramètres (sections dépliées)", maxPages = 6)
        }
        step("11-connect") {
            go(Routes.CONNECT)
            waitText("Installé")
            captureLong("11-connect", "Champimap Connect (module installé)", maxPages = 3)
            click("La plus récente (automatique)")
            waitText("ia-v2")
            capture("12-connect-versions", "Champimap Connect : menu des versions du modèle")
            back()
        }
        step("13-preuves") {
            go(Routes.PREUVES)
            capture("13-preuves", "Preuves « hors ligne »")
        }
        step("14-journal") {
            go(Routes.JOURNAL)
            capture("14-journal", "Journal")
        }
        step("15-sauvegarde") {
            go(Routes.SAUVEGARDE)
            capture("15-sauvegarde", "Sauvegarde")
        }
        step("16-corbeille") {
            go(Routes.CORBEILLE)
            waitText("Fossé de la route")
            capture("16-corbeille", "Corbeille")
        }
        step("17-ventes") {
            go(Routes.VENTES)
            settle()
            captureLong("17-ventes", "Mes ventes (mode vendeur)", maxPages = 2)
        }
        step("18-credits") {
            go(Routes.CREDITS_IA)
            capture("18-credits", "Crédits des photos et du modèle")
        }
        step("19-intoxication") {
            go(Routes.intox(CaptureData.DERNIERE_SORTIE))
            captureLong("19-intoxication", "Intoxication", maxPages = 3)
            go(Routes.INTOX_LIST)
            capture("20-intoxication-fiches", "Intoxication : liste des fiches")
        }
        step("21-partage-spot") {
            go(Routes.partageSpot(CaptureData.SPOT_PARTAGE))
            settle()
            capture("21-partage-spot", "Partage d'un spot")
        }
        step("22-sortie-a-plusieurs") {
            runBlocking { CaptureData.sortieEnCours() }
            go(Routes.groupeCreer(CaptureData.SORTIE_EN_COURS))
            settle()
            capture("22-sortie-a-plusieurs", "Sortie à plusieurs (QR code)")
            go(Routes.GROUPE_REJOINDRE)
            capture("23-rejoindre", "Sortie à plusieurs : rejoindre")
        }

        finish()
    }
}
