package fr.pushpull.champimap.captures

import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.Battue
import fr.pushpull.champimap.core.db.DepartType
import fr.pushpull.champimap.core.db.ErrorLog
import fr.pushpull.champimap.core.db.Hospital
import fr.pushpull.champimap.core.db.Person
import fr.pushpull.champimap.core.db.PositionSource
import fr.pushpull.champimap.core.db.Recolte
import fr.pushpull.champimap.core.db.RecolteItem
import fr.pushpull.champimap.core.db.Sortie
import fr.pushpull.champimap.core.db.Spot
import fr.pushpull.champimap.core.db.SpotPosition
import fr.pushpull.champimap.core.db.SpotStatus
import fr.pushpull.champimap.core.db.SpotVisit
import fr.pushpull.champimap.core.db.TrackPoint
import fr.pushpull.champimap.core.db.Vente
import fr.pushpull.champimap.core.db.WeatherDay
import fr.pushpull.champimap.core.settings.ThemeMode
import fr.pushpull.champimap.feature.stats.zoneIdFor
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/*
 * Données FACTICES et réalistes pour les captures d'écran : spots imaginaires des Monts de Blond
 * (Haute-Vienne), sorties de la saison avec traces GPS, récoltes, ventes, météo, journal, corbeille.
 * Aucune donnée réelle : noms, positions et poids sont inventés (tirage à graine fixe → captures stables).
 */
object CaptureData {

    /** Identifiant de la personne « moi ». */
    const val MOI = "moi"

    /** Sortie en cours du parcours « sortie ». */
    const val SORTIE_EN_COURS = "sortie-en-cours"

    /** Spot ouvert pendant la sortie (fiche, « Trouvé ! », récolte). */
    const val SPOT_FICHE = "spot-talus-girolles"

    /** Récolte du spot pendant la sortie en cours. */
    const val RECOLTE_EN_COURS = "recolte-en-cours"

    /** Spot utilisé pour l'écran de partage. */
    const val SPOT_PARTAGE = "spot-chataigneraie"

    /** Sortie terminée la plus récente (intoxication, vente liée). */
    const val DERNIERE_SORTIE = "sortie-passee-0"

    /** Centre fictif des spots : Monts de Blond (loin de tout domicile réel). */
    private const val LAT0 = 46.055
    private const val LON0 = 1.005

    private val zone: ZoneId = ZoneId.systemDefault()
    private val rnd = Random(87)

    /** Spot imaginaire : identifiant, nom, décalage (m) par rapport au centre, essences, note. */
    private data class SpotDef(
        val id: String, val name: String, val dxM: Double, val dyM: Double,
        val essence: String, val rating: Int, val repere: String?, val status: SpotStatus = SpotStatus.ACTIF,
    )

    private val SPOTS = listOf(
        SpotDef(SPOT_FICHE, "Talus aux girolles", 120.0, 340.0, "Châtaignier,Chêne", 4, "Talus moussu après le gros rocher"),
        SpotDef(SPOT_PARTAGE, "Châtaigneraie du Ruisseau", -420.0, 210.0, "Châtaignier", 5, "Sous les vieux châtaigniers, côté ruisseau"),
        SpotDef("spot-hetraie", "Hêtraie des Monts de Blond", 650.0, -380.0, "Hêtre", 4, "Pente nord, à 20 m du chemin"),
        SpotDef("spot-lisiere", "Lisière de la Lande", -900.0, -150.0, "Chêne,Bouleau", 3, null),
        SpotDef("spot-pinede", "Pinède du Puy", 1100.0, 520.0, "Pin,Douglas", 3, "Coupe de bois de 2024"),
        SpotDef("spot-chenaie", "Chênaie aux trompettes", -250.0, -720.0, "Chêne,Charme", 5, "Creux humide sous les charmes"),
        SpotDef("spot-bouleaux", "Bouquet de bouleaux", 380.0, 860.0, "Bouleau", 2, null, SpotStatus.EPUISE),
        SpotDef("spot-explorer", "Vallon à explorer", -1300.0, 640.0, "Hêtre,Châtaignier", 0, null, SpotStatus.A_EXPLORER),
    )

    /** Espèces récoltées (identifiants du catalogue species.json) et poids typiques (g). */
    private val RECOLTES = listOf(
        "cantharellus-cibarius" to 280, "boletus-edulis" to 650, "craterellus-cornucopioides" to 220,
        "hydnum-repandum" to 340, "imleria-badia" to 300, "boletus-pinophilus" to 520,
    )

    /** Coordonnées à [dxM] m vers l'est et [dyM] m vers le nord du centre. */
    private fun at(dxM: Double, dyM: Double): Pair<Double, Double> =
        (LAT0 + dyM / 111_320.0) to (LON0 + dxM / (111_320.0 * cos(Math.toRadians(LAT0))))

    /** Millisecondes du jour [daysAgo] (avant aujourd'hui) à [hour]:[minute]. */
    private fun day(daysAgo: Long, hour: Int, minute: Int = 0): Long =
        LocalDate.now(zone).minusDays(daysAgo).atTime(LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    /** Trace en boucle irrégulière autour du départ (un point toutes les 20 s). */
    private fun track(sortieId: String, start: Long, end: Long, radiusM: Double): List<TrackPoint> {
        val n = ((end - start) / 20_000L).toInt().coerceIn(20, 400)
        return (0 until n).map { i ->
            val a = 2 * Math.PI * i / n
            val r = radiusM * (0.55 + 0.45 * sin(3 * a)) * (i.toDouble() / n).coerceAtLeast(0.15)
            val (lat, lon) = at(r * cos(a) + rnd.nextDouble(-6.0, 6.0), r * sin(a) + rnd.nextDouble(-6.0, 6.0))
            TrackPoint("$sortieId-p$i", sortieId, MOI, start + i * 20_000L, lat, lon, 4f + rnd.nextFloat() * 6f)
        }
    }

    /** Réglages d'un utilisateur installé (premier lancement fait). */
    suspend fun reglages(sellerMode: Boolean = true) {
        val s = AppGraph.settings
        s.setOnboardingDone(true)
        s.setFirstName("Pierre")
        s.setDepartements(listOf("19", "23", "87"))
        s.setPsycheUnlocked(true)
        s.setThemeMode(ThemeMode.CLAIR)
        s.setSellerMode(sellerMode)
    }

    /**
     * Historique complet : 8 spots, 12 sorties terminées (trace, passages, récoltes pesées),
     * ventes, météo, urgences, journal, corbeille.
     */
    suspend fun historique() {
        val db = AppGraph.db
        db.personDao().upsert(Person(MOI, "Pierre", isMe = true))

        // Spots (position + historique de positions).
        SPOTS.forEachIndexed { i, d ->
            val (lat, lon) = at(d.dxM, d.dyM)
            val created = day(60L - i * 5, 10)
            db.spotDao().upsert(
                Spot(
                    id = d.id, name = d.name, lat = lat, lon = lon, accuracyM = 3.2f + i * 0.4f,
                    essence = d.essence, rating = d.rating.takeIf { it > 0 }, status = d.status, repere = d.repere,
                    note = if (i == 0) "Toujours après 3 jours de pluie. Passer par le chemin du haut." else null,
                    authorId = MOI, createdAt = created, updatedAt = created,
                ),
            )
            db.spotDao().upsertPosition(
                SpotPosition("${d.id}-pos", d.id, lat, lon, 3.2f + i * 0.4f, PositionSource.GPS_PRECIS, created, active = true, authorId = MOI),
            )
        }

        // Sorties terminées de la saison (de 2 à 52 jours avant aujourd'hui).
        val agos = listOf(2L, 5L, 8L, 11L, 15L, 19L, 23L, 27L, 33L, 39L, 45L, 52L)
        agos.forEachIndexed { k, ago ->
            val id = "sortie-passee-$k"
            val start = day(ago, 8 + k % 3, 15)
            val end = start + (95 + k * 7) * 60_000L
            val (sLat, sLon) = at(-60.0, -40.0)
            db.sortieDao().upsert(
                Sortie(id, start, end, if (k % 4 == 3) DepartType.FOOT else DepartType.CAR, sLat, sLon, "Monts de Blond", authorId = MOI),
            )
            db.sortieDao().upsertPoints(track(id, start, end, 700.0 + k * 40))
            // 2 ou 3 spots visités, avec récolte pesée (sauf un « spot vide » de temps en temps).
            val visited = listOf(SPOTS[k % 6], SPOTS[(k + 2) % 6], SPOTS[(k + 4) % 6]).take(2 + k % 2)
            visited.forEachIndexed { j, d ->
                val t = start + (20 + j * 25) * 60_000L
                val empty = (k + j) % 7 == 6
                db.spotDao().upsertVisit(SpotVisit("$id-v$j", d.id, id, t, empty = empty, authorId = MOI))
                if (!empty) {
                    val rid = "$id-r$j"
                    db.recolteDao().upsert(Recolte(rid, d.id, id, t, locked = k > 6, authorId = MOI))
                    val (sp, g) = RECOLTES[(k + j) % RECOLTES.size]
                    db.recolteDao().upsertItem(
                        RecolteItem("$rid-i0", rid, sp, (g * (0.6 + rnd.nextDouble())).toInt(), stade = "BEAU", qualites = "FERME", authorId = MOI),
                    )
                    if ((k + j) % 3 == 0) {
                        val (sp2, g2) = RECOLTES[(k + j + 1) % RECOLTES.size]
                        db.recolteDao().upsertItem(RecolteItem("$rid-i1", rid, sp2, (g2 * 0.5).toInt(), authorId = MOI))
                    }
                }
            }
        }

        // Mode vendeur : quelques ventes liées aux sorties.
        listOf(Triple(2L, 1200, 2400), Triple(8L, 800, 3200), Triple(15L, 1500, 2200), Triple(27L, 600, 3500)).forEachIndexed { i, (ago, g, cents) ->
            db.venteDao().upsert(
                Vente(
                    id = "vente-$i", soldAt = day(ago, 12), weightG = g, pricePerKgCents = cents,
                    speciesId = if (i % 2 == 0) "cantharellus-cibarius" else "boletus-edulis",
                    sortieIds = "sortie-passee-${listOf(0, 2, 4, 7)[i]}", note = if (i == 0) "Marché de Bellac" else null,
                ),
            )
        }

        // Météo des zones larges des spots (60 derniers jours) : stats et corrélations.
        val zones = SPOTS.map { d -> at(d.dxM, d.dyM).let { (la, lo) -> zoneIdFor(la, lo) } }.distinct()
        val days = (0L..60L).flatMap { ago ->
            val date = LocalDate.now(zone).minusDays(ago).toString()
            val rain = if (ago % 6 < 2) 6.0 + (ago % 5) * 3.5 else if (ago % 6 == 2L) 1.2 else 0.0
            zones.map { z -> WeatherDay(z, date, rain, 6.0 + (ago % 4), 15.0 + (ago % 7)) }
        }
        db.weatherDao().upsertAll(days)

        // Urgences (hôpital fictif) et un jour de battue.
        val (hLat, hLon) = at(6000.0, -9000.0)
        db.hospitalDao().upsertAll(listOf(Hospital("hopital-demo", "Centre hospitalier (exemple)", hLat, hLon, null, "87")))
        db.battueDao().upsert(Battue("battue-demo", LocalDate.now(zone).plusDays(3).toString(), "Battue au sanglier, secteur du Puy"))

        // Journal : deux erreurs anodines (jamais de position ni de nom de spot).
        db.errorLogDao().upsert(ErrorLog(time = day(3, 18, 42), screen = "Connect", message = "Synchro interrompue (réseau perdu dans Connect)"))
        db.errorLogDao().upsert(ErrorLog(time = day(9, 9, 5), screen = "Sortie", message = "GPS indisponible au démarrage"))

        // Corbeille : un spot et une vente supprimés récemment.
        val (tLat, tLon) = at(200.0, -1500.0)
        db.spotDao().upsert(Spot("spot-supprime", "Fossé de la route", tLat, tLon, authorId = MOI, deletedAt = day(4, 20)))
        db.venteDao().upsert(Vente("vente-supprimee", day(19, 12), 400, 2000, "hydnum-repandum", deletedAt = day(6, 21)))
    }

    /**
     * Sortie EN COURS (parcours « sortie ») : départ garé il y a 1 h 20, trace, deux spots visités.
     * La récolte du spot de la fiche est créée vide (remplie plus tard par le test).
     */
    suspend fun sortieEnCours(): Sortie {
        val db = AppGraph.db
        val now = System.currentTimeMillis()
        val start = now - 80 * 60_000L
        val (sLat, sLon) = at(-60.0, -40.0)
        val s = Sortie(SORTIE_EN_COURS, start, null, DepartType.CAR, sLat, sLon, "Monts de Blond", authorId = MOI)
        db.sortieDao().upsert(s)
        db.sortieDao().upsertPoints(track(SORTIE_EN_COURS, start, now, 520.0))
        db.spotDao().upsertVisit(SpotVisit("en-cours-v0", "spot-chenaie", SORTIE_EN_COURS, start + 25 * 60_000L, authorId = MOI))
        db.spotDao().upsertVisit(SpotVisit("en-cours-v1", SPOT_FICHE, SORTIE_EN_COURS, now - 4 * 60_000L, authorId = MOI))
        return s
    }

    /** Position GPS factice « actuelle » (près du spot de la fiche). */
    fun positionActuelle(): Pair<Double, Double> = at(110.0, 330.0)

    /** Crée la récolte (vide) du spot de la fiche pour la sortie en cours. */
    suspend fun recolteVide() {
        AppGraph.db.recolteDao().upsert(
            Recolte(RECOLTE_EN_COURS, SPOT_FICHE, SORTIE_EN_COURS, System.currentTimeMillis() - 2 * 60_000L, authorId = MOI),
        )
    }

    /** Remplit la récolte de la sortie en cours : cèpes pesés + girolles (2 espèces). */
    suspend fun recolteDeuxEspeces() {
        val db = AppGraph.db
        recolteVide()
        db.recolteDao().upsertItem(
            RecolteItem("item-cepe", RECOLTE_EN_COURS, "boletus-edulis", 640, stade = "BEAU", qualites = "FERME", authorId = MOI),
        )
        db.recolteDao().upsertItem(
            RecolteItem("item-girolle", RECOLTE_EN_COURS, "cantharellus-cibarius", 310, stade = "JEUNE", qualites = "FERME", authorId = MOI),
        )
    }
}
