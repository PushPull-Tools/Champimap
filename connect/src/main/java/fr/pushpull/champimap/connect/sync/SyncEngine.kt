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
 * Moteur de synchronisation (un seul à la fois, au niveau du processus) :
 *  1. centres antipoison (liste intégrée) ;
 *  2. pour chaque département coché : contour puis hôpitaux avec urgences ;
 *  3. météo de toutes les zones demandées ;
 *  4. cartes des départements demandés ;
 *  5. modèle d'identification, s'il est demandé et qu'une version plus récente (ou choisie) existe.
 * Chaque élément est indépendant : un échec est noté (liste d'erreurs) et la suite continue.
 * Chaque fichier terminé est aussitôt autorisé en lecture pour Champimap et annoncé (diffusion + state.json).
 * Aucune donnée de spot n'est reçue ni envoyée : seulement des départements et des carrés de 0,25°
 * (et, pour le modèle, un simple GET de la liste publique des releases GitHub).
 */
object SyncEngine {
    private const val STATE_FILE = "state.json"
    private const val PUBLISH_INTERVAL_MS = 400L

    // Poids relatifs dans le pourcentage global.
    private const val W_CONTOUR = 2
    private const val W_URGENCES = 2
    private const val W_METEO = 6
    private const val W_ANTIPOISON = 1
    private const val W_CARTE = 40
    private const val W_IA = 40

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

    /** Liste des éléments à traiter, dans l'ordre d'exécution. */
    private fun planItems(p: ParsedRequest): List<SyncItem> {
        val r = p.request
        val items = ArrayList<SyncItem>()
        if (r.poisonCenters) items += SyncItem("antipoison", ItemKind.ANTIPOISON, "Centres antipoison", weight = W_ANTIPOISON)
        for (code in r.departements) {
            val name = Departements.byCode(code)?.name ?: code
            items += SyncItem("contour_$code", ItemKind.CONTOUR, "Contour · $name", code, weight = W_CONTOUR)
            items += SyncItem("urgences_$code", ItemKind.URGENCES, "Urgences · $name", code, weight = W_URGENCES)
        }
        if (r.zones.isNotEmpty()) {
            val n = r.zones.map { it.zoneId }.distinct().size
            items += SyncItem("meteo", ItemKind.METEO, "Météo · $n zone${if (n > 1) "s" else ""}", weight = W_METEO)
        }
        for (code in r.mapDepartements) {
            val name = Departements.byCode(code)?.name ?: code
            items += SyncItem("carte_$code", ItemKind.CARTE, "Carte · $name", code, weight = W_CARTE)
        }
        if (r.ia != null) items += SyncItem("ia", ItemKind.IA, "Modèle d'identification", weight = W_IA)
        return items
    }

    // ------------------------------------------------------------------ Exécution

    /** Déroulé complet de la synchronisation. */
    private suspend fun execute(p: ParsedRequest) {
        val r = p.request
        val dir = File(app.cacheDir, "sync")
        try {
            prepareDir(dir)
            if (p.isEmpty) throw IOException("Rien à télécharger dans la demande")

            // 1. Centres antipoison (aucun réseau).
            if (r.poisonCenters) {
                step("Centres antipoison")
                runItem("antipoison") {
                    writeJson(dir, "antipoison.json", PoisonCenters.toJson())
                    "${PoisonCenters.ALL.size} centres"
                }
            }

            // 2. Contours et urgences, département par département.
            for (code in r.departements) {
                val dept = Departements.byCode(code) ?: continue
                step("Contour et urgences · ${dept.name}")
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

            // 3. Météo.
            if (r.zones.isNotEmpty()) {
                step("Météo")
                runItem("meteo") { downloadWeather(p, dir) }
            }

            // 4. Cartes.
            if (r.mapDepartements.isNotEmpty()) {
                step("Recherche de la carte la plus récente")
                val build = try {
                    runInterruptible { MapDownloader.findLatestBuild() }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    for (code in r.mapDepartements) failItem("carte_$code", reason(e))
                    null
                }
                if (build != null) {
                    for (code in r.mapDepartements) {
                        val dept = Departements.byCode(code) ?: continue
                        step("Carte · ${dept.name}")
                        runItem("carte_$code") {
                            val part = File(dir, "map_$code.pmtiles.part")
                            val stats = MapDownloader.extract(build, dept, p.mapMaxZoom, part) { pct, phase ->
                                setItem("carte_$code") { it.copy(percent = pct, detail = phase) }
                            }
                            val final = File(dir, "map_$code.pmtiles")
                            final.delete()
                            if (!part.renameTo(final)) throw IOException("Écriture de la carte impossible")
                            registerFile(final)
                            "${sizeText(stats.bytes)} · ${stats.tiles} tuiles · zoom ${stats.maxZoom}"
                        }
                    }
                }
            }

            // 5. Modèle d'identification (gros fichier, en dernier).
            r.ia?.let { ia ->
                step("Modèle d'identification")
                runItem("ia") { syncIa(ia, dir) }
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
     * Modèle d'identification :
     *  1. liste publique des versions (GitHub) → version voulue ou la plus récente ;
     *  2. rien à faire si l'appli l'a déjà (sauf actualisation forcée) ;
     *  3. téléchargement reprenable dans cacheDir/ia_download/ (0–85 %), empreinte SHA-256 (85–90 %),
     *     extraction dans sync/ia/<version>/ (90–99 %), statistiques dans stats.json ;
     *  4. lecture accordée à Champimap pour tout le dossier, puis ia_manifest.json annoncé (dernier fichier).
     */
    private suspend fun syncIa(req: IaRequest, dir: File): String {
        setItem("ia") { it.copy(detail = "Recherche de la dernière version…") }
        val releases = runInterruptible { IaModelDownloader.listReleases() }
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
            return "À jour · ${target.summary()}"
        }

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
     * Exécute un élément : statut « en cours », puis « terminé » (avec un détail) ou « erreur ».
     * Une erreur n'arrête PAS la synchronisation ; une annulation, si.
     */
    private suspend fun runItem(id: String, block: suspend () -> String?) {
        setItem(id) { it.copy(status = ItemStatus.RUNNING, percent = 0, detail = "En cours…") }
        try {
            val detail = block()
            setItem(id) { it.copy(status = ItemStatus.DONE, percent = 100, detail = detail) }
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
                if (it.status == ItemStatus.WAITING || it.status == ItemStatus.RUNNING) {
                    it.copy(status = ItemStatus.SKIPPED, detail = if (cancelled) "Annulé" else it.detail)
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

    /** Change l'étape affichée. */
    private fun step(text: String) {
        _state.update { it.copy(step = text) }
        publish(force = true)
    }

    /** Modifie un élément puis recalcule les pourcentages. */
    private fun setItem(id: String, change: (SyncItem) -> SyncItem) {
        _state.update { s -> recompute(s.copy(items = s.items.map { if (it.id == id) change(it) else it })) }
        publish(force = false)
    }

    /** Ajoute une erreur lisible à l'instantané. */
    private fun addError(msg: String) {
        _state.update { it.copy(errors = it.errors + msg) }
    }

    /** Pourcentage global (pondéré) et avancement par département. */
    private fun recompute(s: SyncSnapshot): SyncSnapshot {
        fun eff(i: SyncItem) = if (i.status == ItemStatus.WAITING || i.status == ItemStatus.RUNNING) i.percent else 100
        val totalW = s.items.sumOf { it.weight }
        val global = if (totalW == 0) (if (s.finished) 100 else 0) else s.items.sumOf { eff(it) * it.weight } / totalW
        val depts = s.items.mapNotNull { it.departement }.distinct().map { code ->
            val list = s.items.filter { it.departement == code }
            val w = list.sumOf { it.weight }.coerceAtLeast(1)
            val pct = list.sumOf { eff(it) * it.weight } / w
            val status = when {
                list.any { it.status == ItemStatus.RUNNING } -> ItemStatus.RUNNING
                list.all { it.status == ItemStatus.WAITING } -> ItemStatus.WAITING
                list.any { it.status == ItemStatus.WAITING } -> ItemStatus.RUNNING
                list.any { it.status == ItemStatus.ERROR } -> ItemStatus.ERROR
                list.all { it.status == ItemStatus.SKIPPED } -> ItemStatus.SKIPPED
                else -> ItemStatus.DONE
            }
            DeptProgress(code, Departements.byCode(code)?.name ?: code, pct, status)
        }
        return s.copy(percent = global.coerceIn(0, 100), departements = depts)
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
