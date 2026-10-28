package fr.pushpull.champimap.connect.sync

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import fr.pushpull.champimap.connect.DeptProgress
import fr.pushpull.champimap.connect.IaRequest
import fr.pushpull.champimap.connect.IaSyncInfo
import fr.pushpull.champimap.connect.ItemKind
import fr.pushpull.champimap.connect.ItemStatus
import fr.pushpull.champimap.connect.SyncFile
import fr.pushpull.champimap.connect.SyncItem
import fr.pushpull.champimap.connect.SyncProtocol
import fr.pushpull.champimap.connect.SyncRequest
import fr.pushpull.champimap.connect.SyncSnapshot
import fr.pushpull.champimap.connect.data.Departements
import fr.pushpull.champimap.connect.download.ContourDownloader
import fr.pushpull.champimap.connect.download.HospitalDownloader
import fr.pushpull.champimap.connect.download.IaModelDownloader
import fr.pushpull.champimap.connect.download.MapDownloader
import fr.pushpull.champimap.connect.download.PoisonCenters
import fr.pushpull.champimap.connect.download.TarGzExtractor
import fr.pushpull.champimap.connect.download.WeatherDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import fr.pushpull.champimap.connect.jsonObj
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.util.Locale
import java.util.TreeSet
import java.util.UUID

/*
 * Moteur de synchronisation (un seul à la fois, au niveau du processus).
 * Ordre réel d'exécution, identique à la liste des tâches diffusée (planItems) :
 *  1. département par département : contour, urgences (hôpitaux avec urgences), carte ;
 *     (la recherche du build de carte le plus récent est faite une fois, au début de la première carte) ;
 *  2. « Général » : centres antipoison (liste intégrée), météo de toutes les zones demandées,
 *     liste des versions publiées du modèle (ia_versions.json), puis le modèle d'identification
 *     s'il existe une version plus récente (ou choisie) — sinon tâche « Déjà à jour » (SKIPPED).
 * Chaque tâche est indépendante : un échec est noté (liste d'erreurs) et la suite continue.
 * Pourcentage global pondéré par les octets estimés de chaque tâche, monotone (voir recompute).
 * Chaque fichier terminé est aussitôt autorisé en lecture pour Champimap et annoncé (diffusion + state.json).
 * Aucune donnée de spot n'est reçue ni envoyée : seulement des départements et des carrés de 0,25°
 * (et, pour le modèle, un simple GET de la liste publique des releases GitHub).
 */
object SyncEngine {
    private const val STATE_FILE = "state.json"
    private const val PUBLISH_INTERVAL_MS = 400L

    // Poids dans le pourcentage global : octets estimés de chaque tâche.
    // Les petites tâches réseau ont un poids plancher (le temps de réponse du serveur compte plus que leur taille).
    private const val MO = 1024L * 1024L
    private const val W_CONTOUR = MO / 2
    private const val W_URGENCES = MO           // Overpass : réponse lente
    private const val W_METEO_ZONE = MO / 4      // par zone météo
    private const val W_ANTIPOISON = 16L * 1024  // liste intégrée, aucun réseau
    private const val W_IA_VERSIONS = 64L * 1024
    private const val W_CARTE = 35 * MO          // ≈ 35 Mo par département (zoom 15)
    private const val W_IA_DEFAULT = 140 * MO    // avant de connaître la taille de l'archive publiée

    /** Fichiers obligatoires du paquet du modèle (à la racine de l'archive). */
    private val IA_REQUIRED = listOf("champi-ia.onnx", "labels.json", "especes_ia.json")
    /** Statistiques transmises à l'appli avec le modèle (ajoutées par Connect à côté des fichiers extraits). */
    private const val IA_STATS = "stats.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SyncSnapshot())

    /** Instantané courant, observé par l'écran et la notification. */
    val state: StateFlow<SyncSnapshot> = _state.asStateFlow()

    @Volatile private var job: Job? = null
    @Volatile private var lastPublish = 0L
    private lateinit var app: Context

    /** Vrai si la dernière synchronisation a été annulée. */
    @Volatile var wasCancelled: Boolean = false
        private set

    /** Vrai pendant une synchronisation. */
    val isRunning: Boolean get() = job?.isActive == true

    /** Lance une synchronisation ; renvoie false si une autre est déjà en cours. */
    @Synchronized
    fun start(context: Context, parsed: ParsedRequest): Boolean {
        if (isRunning) return false
        app = context.applicationContext
        wasCancelled = false
        _state.value = recompute(
            SyncSnapshot(
                syncId = UUID.randomUUID().toString(),
                running = true,
                step = "Préparation…",
                items = planItems(parsed),
                errors = parsed.rejected,
                startedAt = System.currentTimeMillis(),
            ),
        )
        job = scope.launch { execute(parsed) }
        SyncService.start(app)
        return true
    }

    /** Annule la synchronisation en cours (les fichiers déjà terminés restent disponibles). */
    fun cancel() {
        job?.cancel()
    }

    // ------------------------------------------------------------------ Plan

    /**
     * Liste des tâches, dans l'ordre réel d'exécution (même ordre que execute) :
     * un bloc par département (contour, urgences, carte), puis le bloc « Général ».
     */
    private fun planItems(p: ParsedRequest): List<SyncItem> {
        val r = p.request
        val items = ArrayList<SyncItem>()
        for (code in deptOrder(r)) {
            val name = Departements.byCode(code)?.name ?: continue
            val group = "$code · $name"
            // title : libellé court sous le nom du groupe ; label : « Carte · Haute-Vienne », lisible seul.
            fun task(id: String, kind: ItemKind, title: String, short: String, weight: Long) = SyncItem(
                id = id, kind = kind, label = "$short · $name", departement = code,
                weight = weight, title = title, group = group,
            )
            if (code in r.departements) {
                items += task("contour_$code", ItemKind.CONTOUR, "Contour du département", "Contour", W_CONTOUR)
                items += task("urgences_$code", ItemKind.URGENCES, "Urgences les plus proches", "Urgences", W_URGENCES)
            }
            if (code in r.mapDepartements) items += task("carte_$code", ItemKind.CARTE, "Carte", "Carte", W_CARTE)
        }
        // Bloc « Général » : tâches indépendantes des départements.
        fun general(id: String, kind: ItemKind, label: String, weight: Long) =
            SyncItem(id = id, kind = kind, label = label, weight = weight)
        if (r.poisonCenters) items += general("antipoison", ItemKind.ANTIPOISON, "Centres antipoison", W_ANTIPOISON)
        if (r.zones.isNotEmpty()) {
            val n = r.zones.map { it.zoneId }.distinct().size
            items += general("meteo", ItemKind.METEO, "Météo de tes sorties · $n zone${if (n > 1) "s" else ""}", n * W_METEO_ZONE)
        }
        r.ia?.let { ia ->
            items += general("ia_versions", ItemKind.IA, "Liste des versions du modèle", W_IA_VERSIONS)
            if (!ia.listOnly) items += general("ia", ItemKind.IA, "Modèle d'identification", W_IA_DEFAULT)
        }
        return items
    }

    /** Départements dans l'ordre de la demande (contours/urgences d'abord, puis cartes seules), sans doublon. */
    private fun deptOrder(r: SyncRequest): List<String> =
        (r.departements + r.mapDepartements).distinct()

    // ------------------------------------------------------------------ Exécution

    /** Déroulé complet de la synchronisation. */
    private suspend fun execute(p: ParsedRequest) {
        val r = p.request
        val dir = File(app.cacheDir, "sync")
        try {
            prepareDir(dir)
            if (p.isEmpty) throw IOException("Rien à télécharger dans la demande")

            // 1. Département par département : contour, urgences, carte.
            val maps = MapBuildCache()
            for (code in deptOrder(r)) {
                val dept = Departements.byCode(code) ?: continue
                if (code in r.departements) {
                    runItem("contour_$code") {
                        val bytes = runInterruptible { ContourDownloader.download(dept) }
                        writeBytes(dir, "contour_$code.geojson", bytes)
                        sizeText(bytes.size.toLong())
                    }
                    runItem("urgences_$code") {
                        val json = runInterruptible { HospitalDownloader.download(dept) }
                        writeJson(dir, "hospitals_$code.json", json)
                        val n = json.size
                        "$n hôpita${if (n > 1) "ux" else "l"} avec urgences"
                    }
                    delay(500) // politesse envers le serveur Overpass public
                }
                if (code in r.mapDepartements) {
                    runItem("carte_$code") {
                        // Build Protomaps le plus récent : cherché une seule fois pour toutes les cartes.
                        setItem("carte_$code") { it.copy(detail = "Recherche de la carte la plus récente…") }
                        val build = maps.get()
                        val part = File(dir, "map_$code.pmtiles.part")
                        val stats = MapDownloader.extract(build, dept, p.mapMaxZoom, part) { pct, phase ->
                            setItem("carte_$code") { it.copy(percent = pct, detail = phase) }
                        }
                        val final = File(dir, "map_$code.pmtiles")
                        final.delete()
                        if (!part.renameTo(final)) throw IOException("Écriture de la carte impossible")
                        registerFile(final)
                        setItem("carte_$code") { it.copy(sizeBytes = stats.bytes) }
                        "${sizeText(stats.bytes)} · ${stats.tiles} tuiles · zoom ${stats.maxZoom}"
                    }
                }
            }

            // 2. Général : centres antipoison (aucun réseau), météo, modèle d'identification.
            if (r.poisonCenters) {
                runItem("antipoison") {
                    writeJson(dir, "antipoison.json", PoisonCenters.toJson())
                    "${PoisonCenters.ALL.size} centres"
                }
            }
            if (r.zones.isNotEmpty()) runItem("meteo") { downloadWeather(p, dir) }
            r.ia?.let { ia ->
                var releases: List<IaModelDownloader.IaRelease>? = null
                runItem("ia_versions") {
                    val list = runInterruptible { listIaVersions(dir) }
                    releases = list
                    "${list.size} version${if (list.size > 1) "s" else ""} publiée${if (list.size > 1) "s" else ""}"
                }
                if (!ia.listOnly) {
                    val list = releases
                    if (list == null) failItem("ia", "Impossible sans la liste des versions")
                    else runItem("ia") { syncIa(ia, list, dir) }
                }
            }
            finish(cancelled = false, fatal = null)
        } catch (e: CancellationException) {
            finish(cancelled = true, fatal = null)
            throw e
        } catch (e: Exception) {
            finish(cancelled = false, fatal = reason(e))
        } finally {
            // Nettoyage des fichiers partiels (annulation ou erreur).
            dir.listFiles()?.filter { it.name.endsWith(".part") }?.forEach { it.delete() }
        }
    }

    /**
     * Build Protomaps le plus récent, cherché une seule fois par synchro, au début de la première carte.
     * Un échec est mémorisé : chaque carte échoue alors avec la même raison, sans nouvelle requête.
     */
    private class MapBuildCache {
        private var url: String? = null
        private var failure: String? = null

        suspend fun get(): String {
            url?.let { return it }
            failure?.let { throw IOException(it) }
            return try {
                runInterruptible { MapDownloader.findLatestBuild() }.also { url = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val msg = reason(e)
                failure = msg
                throw IOException(msg)
            }
        }
    }

    /** Météo : fusion des périodes par zone, une requête (ou deux) par zone, weather.json. */
    private suspend fun downloadWeather(p: ParsedRequest, dir: File): String {
        val today = LocalDate.now(WeatherDownloader.PARIS)
        val byZone = LinkedHashMap<String, MutableSet<LocalDate>>()
        for (z in p.request.zones) {
            val dates = WeatherDownloader.datesBetween(LocalDate.parse(z.from), LocalDate.parse(z.to), today)
            byZone.getOrPut(z.zoneId) { TreeSet() }.addAll(dates)
        }
        val rows = ArrayList<JsonObject>()
        var okZones = 0
        var errZones = 0
        val centers = p.request.zones.associateBy { it.zoneId }
        var i = 0
        for ((zoneId, dates) in byZone) {
            val zr = centers.getValue(zoneId)
            setItem("meteo") { it.copy(detail = "Zone ${i + 1} sur ${byZone.size}") }
            try {
                val days = runInterruptible { WeatherDownloader.downloadZone(zr.centerLat, zr.centerLon, dates, today) }
                rows += WeatherDownloader.rows(zoneId, days)
                okZones++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errZones++
                addError("Météo · zone $zoneId : ${reason(e)}")
            }
            i++
            setItem("meteo") { it.copy(percent = i * 100 / byZone.size) }
        }
        if (okZones == 0) throw IOException("Aucune zone météo n'a pu être téléchargée")
        writeJson(dir, "weather.json", JsonArray(rows))
        return if (errZones == 0) "$okZones zone(s) · ${rows.size} jours" else "$okZones zone(s), $errZones en échec"
    }

    /**
     * Liste publique des versions du modèle (GitHub), remise à l'appli à chaque fois (ia_versions.json,
     * menu de choix de version). Renvoie les versions, de la plus récente à la plus ancienne.
     */
    private fun listIaVersions(dir: File): List<IaModelDownloader.IaRelease> {
        val releases = IaModelDownloader.listReleases()
        writeJson(dir, SyncProtocol.IA_VERSIONS, versionsJson(releases))
        return releases
    }

    /**
     * Modèle d'identification (la liste des versions a déjà été lue par listIaVersions) :
     *  1. version voulue ou la plus récente ;
     *  2. rien à faire si l'appli l'a déjà (sauf actualisation forcée) : tâche « Déjà à jour » ;
     *  3. téléchargement reprenable dans cacheDir/ia_download/ (0–85 %), empreinte SHA-256 (85–90 %),
     *     extraction dans sync/ia/<version>/ (90–99 %), statistiques dans stats.json ;
     *  4. lecture accordée à Champimap pour tout le dossier, puis ia_manifest.json annoncé (dernier fichier).
     */
    private suspend fun syncIa(req: IaRequest, releases: List<IaModelDownloader.IaRelease>, dir: File): String {
        if (releases.isEmpty()) throw IOException("Aucun modèle publié pour le moment")
        val target = if (req.wanted != null) {
            releases.firstOrNull { it.tag == req.wanted } ?: throw IOException("Version ${req.wanted} introuvable")
        } else releases.first()

        // Déjà installé dans l'appli ?
        val installedN = IaModelDownloader.versionNumber(req.installed)
        val upToDate = when {
            req.force || installedN == null -> false
            req.wanted != null -> installedN == target.number
            else -> installedN >= target.number
        }
        if (upToDate) {
            _state.update { it.copy(ia = IaSyncInfo(version = req.installed ?: target.tag, upToDate = true)) }
            throw AlreadyUpToDate("Déjà à jour · ${target.summary()}")
        }
        // Taille réelle connue : elle remplace l'estimation dans le pourcentage global.
        if (target.size > 0) setItem("ia") { it.copy(weight = target.size, sizeBytes = target.size) }

        // Téléchargement reprenable (le partiel survit à une annulation ou à une coupure).
        val cache = File(app.cacheDir, "ia_download").apply { mkdirs() }
        val part = File(cache, "${target.tag}.tar.gz.part")
        cache.listFiles()?.filter { it.name != part.name }?.forEach { it.deleteRecursively() }
        runInterruptible {
            IaModelDownloader.download(target, part) { done, total ->
                val pct = if (total > 0) (done * 85 / total).toInt() else 0
                setItem("ia") {
                    it.copy(percent = pct.coerceIn(0, 85), detail = "${target.tag} · ${sizeText(done)} sur ${sizeText(total)}")
                }
            }
        }

        // Empreinte SHA-256 : comparée à celle publiée par GitHub, sinon calculée et transmise.
        setItem("ia") { it.copy(percent = 85, detail = "Vérification de l'empreinte…") }
        val sha = runInterruptible {
            IaModelDownloader.sha256(part) { done, total ->
                val pct = 85 + if (total > 0) (done * 5 / total).toInt() else 0
                setItem("ia") { it.copy(percent = pct.coerceIn(85, 90)) }
            }
        }
        val expected = target.sha256
        if (expected != null && !sha.equals(expected, ignoreCase = true)) {
            part.delete()
            throw IOException("Empreinte du modèle incorrecte : fichier supprimé, relance la synchro")
        }

        // Extraction (chemins vérifiés : rien ne peut sortir du dossier).
        setItem("ia") { it.copy(percent = 90, detail = "Extraction…") }
        val iaRoot = File(dir, "ia")
        iaRoot.deleteRecursively()
        val out = File(iaRoot, target.tag)
        val entries = runInterruptible {
            TarGzExtractor.extract(part, out) { done, total ->
                val pct = 90 + if (total > 0) (done * 9 / total).toInt() else 0
                setItem("ia") { it.copy(percent = pct.coerceIn(90, 99)) }
            }
        }.filter { it.path != IA_STATS }.toMutableList()
        for (f in IA_REQUIRED) {
            if (entries.none { it.path == f }) throw IOException("Fichier $f absent du modèle ${target.tag}")
        }
        part.delete() // l'archive ne sert plus : place libérée

        // Statistiques publiées avec la release (stats.json, même vide : version et date au minimum).
        val statsJson = JsonObject(
            (target.stats?.raw ?: JsonObject(emptyMap())) +
                mapOf("version" to JsonPrimitive(target.tag), "datePublication" to JsonPrimitive(target.date)),
        )
        val statsBytes = SyncProtocol.json.encodeToString(JsonElement.serializer(), statsJson).toByteArray(Charsets.UTF_8)
        File(out, IA_STATS).writeBytes(statsBytes)
        entries += TarGzExtractor.Entry(
            IA_STATS, statsBytes.size.toLong(),
            IaModelDownloader.hex(java.security.MessageDigest.getInstance("SHA-256").digest(statsBytes)),
        )

        // Lecture accordée pour tout le dossier de la version (préfixe d'URI), plus chaque fichier hors photos.
        val base = Uri.parse("content://${SyncProtocol.FILES_AUTHORITY}/sync/ia/${target.tag}/")
        runCatching {
            app.grantUriPermission(
                SyncProtocol.MAIN_PACKAGE, base,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
            )
        }
        entries.filter { '/' !in it.path }.forEach { grant(uriFor(File(out, it.path))) }

        val totalBytes = entries.sumOf { it.size }
        _state.update {
            it.copy(
                ia = IaSyncInfo(
                    version = target.tag, upToDate = false, sha256 = sha, digestVerified = expected != null,
                    fileCount = entries.size, totalBytes = totalBytes,
                ),
            )
        }
        // Manifeste en dernier : sa présence signifie « tout est prêt ».
        val manifest = jsonObj(
            "version" to target.tag,
            "sha256" to sha,
            "digestVerified" to (expected != null),
            "archiveSize" to target.size,
            "exact" to (req.wanted != null),
            "files" to JsonArray(entries.map { jsonObj("path" to it.path, "size" to it.size, "sha256" to it.sha256) }),
        )
        writeJson(dir, SyncProtocol.IA_MANIFEST, manifest)
        return "${target.summary()} · ${entries.size} fichiers · ${sizeText(totalBytes)}"
    }

    /**
     * ia_versions.json : tableau des versions publiées (plus récente d'abord) avec leurs statistiques.
     * Les statistiques inconnues sont omises (l'appli ne les affiche pas).
     */
    private fun versionsJson(releases: List<IaModelDownloader.IaRelease>): JsonArray = JsonArray(
        releases.map { r ->
            val fields = LinkedHashMap<String, Any?>()
            fields["version"] = r.tag
            r.stats?.especes?.let { fields["especes"] = it }
            r.stats?.top1?.let { fields["top1"] = it }
            r.stats?.top5?.let { fields["top5"] = it }
            r.stats?.tailleModeleMo?.let { fields["tailleModeleMo"] = it }
            fields["size"] = r.size
            fields["date"] = r.date
            jsonObj(*fields.map { it.key to it.value }.toTypedArray())
        },
    )

    /** Levée par une tâche qui n'a rien à faire (déjà à jour) : elle devient SKIPPED, jamais DONE. */
    private class AlreadyUpToDate(message: String) : Exception(message)

    /**
     * Exécute une tâche : statut « en cours » (et étape affichée = son libellé), puis « fait » (avec un
     * détail), « déjà à jour » (AlreadyUpToDate) ou « erreur ».
     * Une erreur n'arrête PAS la synchronisation ; une annulation, si.
     */
    private suspend fun runItem(id: String, block: suspend () -> String?) {
        val label = _state.value.items.firstOrNull { it.id == id }?.label ?: return
        _state.update { it.copy(step = label) }
        setItem(id) { it.copy(status = ItemStatus.RUNNING, percent = 0, detail = "En cours…") }
        publish(force = true)
        try {
            val detail = block()
            setItem(id) { it.copy(status = ItemStatus.DONE, percent = 100, detail = detail) }
        } catch (e: AlreadyUpToDate) {
            setItem(id) { it.copy(status = ItemStatus.SKIPPED, percent = 100, detail = e.message) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failItem(id, reason(e))
        }
        publish(force = true)
    }

    /** Marque un élément en erreur et ajoute la raison à la liste des erreurs. */
    private fun failItem(id: String, msg: String) {
        var label = id
        setItem(id) {
            label = it.label
            it.copy(status = ItemStatus.ERROR, detail = msg)
        }
        addError("$label : $msg")
    }

    /** Fin de synchronisation : instantané final, publication, arrêt du service. */
    private fun finish(cancelled: Boolean, fatal: String?) {
        wasCancelled = cancelled
        val snap = _state.updateAndGet { s ->
            val items = s.items.map {
                // Tâche non commencée ou interrompue : « pas faite », jamais « faite » ni « déjà à jour ».
                if (it.status == ItemStatus.WAITING || it.status == ItemStatus.RUNNING) {
                    it.copy(status = ItemStatus.CANCELLED, detail = if (cancelled) "Annulé" else "Pas fait")
                } else it
            }
            val errors = s.errors.toMutableList()
            if (fatal != null) errors += fatal
            if (cancelled) errors += "Synchronisation annulée"
            val step = when {
                cancelled -> "Annulé"
                !s.hasResult -> "Échec"
                errors.isNotEmpty() -> "Terminé, avec des erreurs"
                else -> "Terminé"
            }
            recompute(
                s.copy(
                    running = false, finished = true, items = items, errors = errors,
                    step = step, endedAt = System.currentTimeMillis(),
                ),
            )
        }
        publish(force = true, snapshot = snap)
    }

    // ------------------------------------------------------------------ État

    /** Modifie un élément puis recalcule les pourcentages. */
    private fun setItem(id: String, change: (SyncItem) -> SyncItem) {
        _state.update { s -> recompute(s.copy(items = s.items.map { if (it.id == id) change(it) else it })) }
        publish(force = false)
    }

    /** Ajoute une erreur lisible à l'instantané. */
    private fun addError(msg: String) {
        _state.update { it.copy(errors = it.errors + msg) }
    }

    /**
     * Pourcentage global et avancement par département, pondérés par les octets estimés.
     * Une tâche faite, déjà à jour ou en erreur compte pour 100 % (plus rien à attendre) ;
     * en attente, en cours ou annulée : son avancement réel.
     * Le pourcentage global ne recule jamais (le poids du modèle peut changer quand sa taille est connue).
     */
    private fun recompute(s: SyncSnapshot): SyncSnapshot {
        fun eff(i: SyncItem): Long = if (i.isProcessed) 100L else i.percent.coerceIn(0, 100).toLong()
        val totalW = s.items.sumOf { it.weight.coerceAtLeast(1) }
        val global = if (s.items.isEmpty()) (if (s.finished) 100 else 0)
        else (s.items.sumOf { eff(it) * it.weight.coerceAtLeast(1) } / totalW).toInt()
        val depts = s.items.mapNotNull { it.departement }.distinct().map { code ->
            val list = s.items.filter { it.departement == code }
            val w = list.sumOf { it.weight.coerceAtLeast(1) }
            val pct = (list.sumOf { eff(it) * it.weight.coerceAtLeast(1) } / w).toInt()
            val status = when {
                list.any { it.status == ItemStatus.RUNNING } -> ItemStatus.RUNNING
                list.all { it.status == ItemStatus.WAITING } -> ItemStatus.WAITING
                list.any { it.status == ItemStatus.WAITING } -> ItemStatus.RUNNING
                list.any { it.status == ItemStatus.ERROR } -> ItemStatus.ERROR
                list.any { it.status == ItemStatus.CANCELLED } -> ItemStatus.CANCELLED
                list.all { it.status == ItemStatus.SKIPPED } -> ItemStatus.SKIPPED
                else -> ItemStatus.DONE
            }
            DeptProgress(code, Departements.byCode(code)?.name ?: code, pct, status)
        }
        return s.copy(percent = maxOf(s.percent, global.coerceIn(0, 100)), departements = depts)
    }

    // ------------------------------------------------------------------ Fichiers et diffusion

    /** Vide le dossier d'échange (résultats de la synchro précédente). */
    private fun prepareDir(dir: File) {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.deleteRecursively() } // dont sync/ia/ (modèle de la synchro précédente)
    }

    /** Écrit un JSON de façon atomique (fichier temporaire puis renommage) et l'annonce. */
    private fun writeJson(dir: File, name: String, json: JsonElement) =
        writeBytes(dir, name, SyncProtocol.json.encodeToString(JsonElement.serializer(), json).toByteArray(Charsets.UTF_8))

    /** Écrit des octets de façon atomique et annonce le fichier. */
    private fun writeBytes(dir: File, name: String, bytes: ByteArray) {
        val part = File(dir, "$name.part")
        part.writeBytes(bytes)
        val final = File(dir, name)
        final.delete()
        if (!part.renameTo(final)) throw IOException("Écriture impossible : $name")
        registerFile(final)
    }

    /** URI FileProvider d'un fichier du dossier d'échange. */
    fun uriFor(file: File): Uri = FileProvider.getUriForFile(app, SyncProtocol.FILES_AUTHORITY, file)

    /** Autorise la lecture du fichier par Champimap et l'ajoute à la liste des fichiers prêts. */
    private fun registerFile(file: File) {
        val uri = uriFor(file)
        grant(uri)
        val f = SyncFile(file.name, uri.toString(), file.length())
        _state.update { s -> s.copy(files = s.files.filterNot { it.name == f.name } + f) }
    }

    /** Lecture accordée à l'appli principale (sans effet si elle n'est pas installée). */
    private fun grant(uri: Uri) {
        runCatching { app.grantUriPermission(SyncProtocol.MAIN_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /**
     * Diffuse l'instantané (réservé aux applis ayant la permission CONNECT) et l'écrit dans state.json.
     * Limité à une publication toutes les 400 ms, sauf `force`.
     */
    private fun publish(force: Boolean, snapshot: SyncSnapshot? = null) {
        val now = System.currentTimeMillis()
        if (!force && now - lastPublish < PUBLISH_INTERVAL_MS) return
        lastPublish = now
        val snap = snapshot ?: _state.value
        val text = snap.toJson()
        runCatching {
            val dir = File(app.cacheDir, "sync").apply { mkdirs() }
            val tmp = File(dir, "$STATE_FILE.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            val final = File(dir, STATE_FILE)
            final.delete()
            tmp.renameTo(final)
            grant(Uri.parse(SyncProtocol.STATE_URI))
        }
        runCatching {
            val intent = Intent(SyncProtocol.ACTION_PROGRESS)
                .setPackage(SyncProtocol.MAIN_PACKAGE)
                .putExtra(SyncProtocol.EXTRA_SNAPSHOT, text)
            app.sendBroadcast(intent, SyncProtocol.PERMISSION)
        }
    }

    // ------------------------------------------------------------------ Textes

    /** Message d'erreur lisible (sans pile technique). */
    private fun reason(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "Pas de connexion Internet"
        is java.net.SocketTimeoutException -> "Le serveur ne répond pas (délai dépassé)"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    /** Taille lisible : « 12,4 Mo » ou « 830 Ko ». */
    private fun sizeText(bytes: Long): String =
        if (bytes >= 1024 * 1024) String.format(Locale.FRANCE, "%.1f Mo", bytes / 1048576.0)
        else String.format(Locale.FRANCE, "%d Ko", (bytes + 1023) / 1024)
}
