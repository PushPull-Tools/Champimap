package fr.pushpull.champimap.feature.connect

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.room.withTransaction
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.Hospital
import fr.pushpull.champimap.core.db.WeatherDay
import fr.pushpull.champimap.feature.stats.WeatherCorrelation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.File
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/*
 * Pont vers l'appli séparée Champimap Connect (seule à avoir Internet).
 *
 * Ce fichier ne fait AUCUN accès réseau : il
 *  1. installe l'APK de Connect embarquée dans les assets (PackageInstaller) ;
 *  2. construit l'Intent explicite de synchro (départements + zones météo larges, jamais un spot) ;
 *  3. reçoit l'avancement (diffusion protégée par permission « signature ») et le résultat ;
 *  4. importe les fichiers renvoyés : cartes, contours, urgences, météo, centres antipoison.
 */

/** État de l'installation du module. */
sealed interface InstallState {
    data object Idle : InstallState
    /**
     * L'utilisateur doit autoriser Champimap à installer des applis.
     * Le réglage Android n'est JAMAIS ouvert tout seul : l'écran affiche une explication
     * et un bouton « Ouvrir le réglage Android ».
     */
    data object NeedsPermission : InstallState
    data object Installing : InstallState
    data object Installed : InstallState
    data class Failed(val message: String) : InstallState
}

/** Étape de la synchro vue par l'appli. */
enum class SyncPhase { IDLE, RUNNING, DONE, FAILED, CANCELLED }

/** État complet de la synchro affiché par les écrans. */
data class SyncUi(
    val phase: SyncPhase = SyncPhase.IDLE,
    val snapshot: SyncSnapshot? = null,
    val message: String? = null,
    /** Nombre de fichiers déjà importés pour la synchro en cours. */
    val importedFiles: Int = 0,
)

object ConnectClient {

    // -----------------------------------------------------------------------------------------
    // Constantes locales
    // -----------------------------------------------------------------------------------------

    /** APK embarquée par la CI. */
    private const val ASSET_APK = "connect/champimap-connect.apk"
    /** Diffusion interne (non exportée) du statut de PackageInstaller. */
    private const val ACTION_INSTALL_STATUS = "fr.pushpull.champimap.CONNECT_INSTALL_STATUS"
    /** Préférences privées du module. */
    private const val PREFS = "connect"
    private const val KEY_LAST_SYNC = "lastSync"
    private const val KEY_LAST_ERRORS = "lastErrors"
    private const val KEY_LAST_SYNC_ID = "lastSyncId"

    /** Fichier local des centres antipoison mis à jour par Connect. */
    fun antipoisonFile(context: Context): File = File(context.filesDir, ConnectProtocol.FILE_ANTIPOISON)
    /** Dossier des cartes lues par la carte hors ligne. */
    fun mapsDir(context: Context): File = File(context.filesDir, "maps")
    /** Dossier des contours de départements. */
    fun contoursDir(context: Context): File = File(context.filesDir, "contours")

    // -----------------------------------------------------------------------------------------
    // États observables
    // -----------------------------------------------------------------------------------------

    private val _install = MutableStateFlow<InstallState>(InstallState.Idle)
    val installState: StateFlow<InstallState> = _install.asStateFlow()

    private val _sync = MutableStateFlow(SyncUi())
    val syncState: StateFlow<SyncUi> = _sync.asStateFlow()

    /** Date de la dernière synchro réussie (0 = jamais). */
    private val _lastSync = MutableStateFlow(0L)
    val lastSync: StateFlow<Long> = _lastSync.asStateFlow()

    @Volatile private var receiversRegistered = false
    /** Session PackageInstaller en cours (-1 = aucune) : seule celle-ci peut afficher la confirmation. */
    @Volatile private var currentSessionId = -1

    /** Fichiers déjà importés (« syncId|nom|taille ») : un fichier n'est jamais importé deux fois. */
    private val imported = mutableSetOf<String>()
    private val importMutex = Mutex()

    // -----------------------------------------------------------------------------------------
    // Module installé ?
    // -----------------------------------------------------------------------------------------

    /** Vrai si Champimap Connect est installé. */
    fun isInstalled(context: Context): Boolean = installedVersion(context) != null

    /** Version installée de Connect (versionName), ou null si absent. */
    @Suppress("DEPRECATION")
    fun installedVersion(context: Context): String? = try {
        context.packageManager.getPackageInfo(ConnectProtocol.CONNECT_PACKAGE, 0).versionName ?: "?"
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /** Vrai si l'APK de Connect est bien incluse dans cette version de l'appli. */
    fun hasEmbeddedApk(context: Context): Boolean =
        runCatching { context.assets.open(ASSET_APK).use { true } }.getOrDefault(false)

    /** Vrai si Champimap a l'autorisation Android « Installer des applis inconnues ». */
    fun canInstallPackages(context: Context): Boolean =
        context.applicationContext.packageManager.canRequestPackageInstalls()

    /** Intent du réglage Android « Installer des applis inconnues », directement sur Champimap. */
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))

    /**
     * À appeler au retour au premier plan (ON_RESUME) : met à jour l'état d'installation
     * et rattrape une synchro terminée pendant que l'appli était fermée.
     * Ne lance JAMAIS d'installation : c'est l'écran qui l'enchaîne, et seulement si
     * l'utilisateur l'a demandée (bouton « Installer le module »).
     */
    fun onResume(context: Context) {
        val app = context.applicationContext
        loadLastSync(app)
        if (isInstalled(app)) {
            // Module présent = installation réussie (même si la diffusion de succès s'est perdue).
            _install.value = InstallState.Installed
            currentSessionId = -1
        } else if (_install.value is InstallState.Installed) {
            _install.value = InstallState.Idle // désinstallé entre-temps
        }
        ensureListening(app)
        if (isInstalled(app)) AppGraph.appScope.launch { catchUp(app) }
    }

    // -----------------------------------------------------------------------------------------
    // Installation de l'APK embarquée
    // -----------------------------------------------------------------------------------------

    /**
     * Installe Champimap Connect depuis l'APK embarquée. À appeler UNIQUEMENT sur action de
     * l'utilisateur (bouton « Installer le module » / « Réessayer »).
     * Si Champimap n'a pas le droit d'installer des applis : état NeedsPermission, sans rien
     * ouvrir (l'écran propose le bouton « Ouvrir le réglage Android »).
     */
    suspend fun install(context: Context) {
        val app = context.applicationContext
        if (!hasEmbeddedApk(app)) {
            _install.value = InstallState.Failed(
                "Le module n'est pas inclus dans cette version de Champimap. " +
                    "Installe la version complète de l'appli (avec le module) puis réessaie.",
            )
            return
        }
        if (!canInstallPackages(app)) {
            _install.value = InstallState.NeedsPermission
            return
        }
        // Une ancienne tentative restée en suspens est abandonnée avant d'en créer une nouvelle.
        abandonCurrentSession(app)
        _install.value = InstallState.Installing
        ensureListening(app)
        try {
            withContext(Dispatchers.IO) {
                val apk = copyEmbeddedApk(app)
                val installer = app.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                params.setAppPackageName(ConnectProtocol.CONNECT_PACKAGE)
                val sessionId = installer.createSession(params)
                currentSessionId = sessionId
                installer.openSession(sessionId).use { session ->
                    // Écriture de l'APK dans la session d'installation.
                    apk.inputStream().use { input ->
                        session.openWrite("champimap-connect.apk", 0, apk.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                    // Le statut revient par une diffusion interne (récepteur non exporté).
                    val statusIntent = Intent(ACTION_INSTALL_STATUS).setPackage(app.packageName)
                    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                    val pending = PendingIntent.getBroadcast(app, sessionId, statusIntent, flags)
                    session.commit(pending.intentSender)
                }
            }
        } catch (e: Exception) {
            ErrorLogger.log("Connect", "Installation du module impossible", e)
            currentSessionId = -1
            _install.value = InstallState.Failed("L'installation du module a échoué. Réessaie dans un instant.")
        }
    }

    /**
     * « Plus tard » : abandonne la tentative en cours et revient à l'état de départ
     * (ou « installé » si le module est déjà là).
     */
    fun cancelInstall(context: Context) {
        val app = context.applicationContext
        abandonCurrentSession(app)
        _install.value = if (isInstalled(app)) InstallState.Installed else InstallState.Idle
    }

    /** Abandonne la session PackageInstaller en cours, s'il y en a une (erreurs ignorées : session déjà finie). */
    private fun abandonCurrentSession(context: Context) {
        val id = currentSessionId
        currentSessionId = -1
        if (id < 0) return
        runCatching { context.packageManager.packageInstaller.abandonSession(id) }
    }

    /** Copie l'APK des assets dans filesDir/connect/ (dossier partagé par le FileProvider « connect_apk »). */
    private fun copyEmbeddedApk(context: Context): File {
        val dir = File(context.filesDir, "connect").apply { mkdirs() }
        val target = File(dir, "champimap-connect.apk")
        context.assets.open(ASSET_APK).use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    /** Statut renvoyé par PackageInstaller. */
    private val installReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Statut d'une ancienne session (abandonnée par « Réessayer » ou « Plus tard ») : ignoré.
            val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
            if (sessionId != -1 && sessionId != currentSessionId) return
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    // Android demande la confirmation de l'utilisateur : on affiche sa fenêtre.
                    val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    if (confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(confirm) }.onFailure {
                            _install.value = InstallState.Failed("Android n'a pas pu afficher la confirmation d'installation.")
                        }
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    currentSessionId = -1
                    _install.value = InstallState.Installed
                }
                PackageInstaller.STATUS_FAILURE_ABORTED -> _install.value = InstallState.Failed("Installation annulée.")
                PackageInstaller.STATUS_FAILURE_STORAGE -> _install.value = InstallState.Failed("Pas assez de place pour installer le module.")
                PackageInstaller.STATUS_FAILURE_CONFLICT,
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE,
                -> _install.value = InstallState.Failed(
                    "Une autre version du module est déjà installée. Désinstalle « Champimap Connect » puis réessaie.",
                )
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                    ErrorLogger.log("Connect", "Installation refusée : ${msg ?: "raison inconnue"}")
                    _install.value = InstallState.Failed("L'installation du module a échoué.")
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------------
    // Écoute de l'avancement (diffusions de Connect)
    // -----------------------------------------------------------------------------------------

    /** Avancement diffusé par Connect (seule une appli signée avec la même clé peut l'envoyer). */
    private val progressReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val snap = SyncSnapshot.parse(intent.getStringExtra(ConnectProtocol.EXTRA_SNAPSHOT)) ?: return
            AppGraph.appScope.launch { onSnapshot(context.applicationContext, snap) }
        }
    }

    /** Enregistre (une seule fois pour la vie du processus) les deux récepteurs. */
    @Synchronized
    fun ensureListening(context: Context) {
        if (receiversRegistered) return
        val app = context.applicationContext
        ContextCompat.registerReceiver(
            app, installReceiver, IntentFilter(ACTION_INSTALL_STATUS), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        ContextCompat.registerReceiver(
            app, progressReceiver, IntentFilter(ConnectProtocol.ACTION_PROGRESS),
            ConnectProtocol.PERMISSION, null, ContextCompat.RECEIVER_EXPORTED,
        )
        receiversRegistered = true
    }

    // -----------------------------------------------------------------------------------------
    // Demande de synchro
    // -----------------------------------------------------------------------------------------

    /**
     * Construit l'Intent explicite vers Connect.
     * @param refreshMaps true = retélécharger aussi les cartes déjà présentes.
     * @param background true = Connect rend la main tout de suite (avancement par diffusion).
     */
    suspend fun buildSyncIntent(context: Context, refreshMaps: Boolean = false, background: Boolean = false): Intent {
        val app = context.applicationContext
        val departements = AppGraph.settings.flow.first().departements
            .filter { ConnectProtocol.DEPT_REGEX.matches(it) }
        val mapDepartements = departements.filter { code ->
            refreshMaps || !File(mapsDir(app), "$code.pmtiles").let { it.isFile && it.length() > 127 }
        }
        val zones = weatherZones()
        return Intent(ConnectProtocol.ACTION_SYNC)
            .setPackage(ConnectProtocol.CONNECT_PACKAGE)
            .putStringArrayListExtra(ConnectProtocol.EXTRA_DEPARTEMENTS, ArrayList(departements))
            .putStringArrayListExtra(ConnectProtocol.EXTRA_MAP_DEPARTEMENTS, ArrayList(mapDepartements))
            .putStringArrayListExtra(ConnectProtocol.EXTRA_WEATHER_ZONES, ArrayList(zones))
            .putExtra(ConnectProtocol.EXTRA_POISON_CENTERS, true)
            .putExtra(ConnectProtocol.EXTRA_BACKGROUND, background)
    }

    /**
     * Zones météo à demander : « zoneId|du|au », uniquement les carrés de 0,25° (jamais un spot)
     * et seulement si des jours manquent en base.
     */
    private suspend fun weatherZones(): List<String> = withContext(Dispatchers.IO) {
        val dao = AppGraph.db.weatherDao()
        runCatching { WeatherCorrelation.weatherRequests() }.getOrDefault(emptyList())
            .filter { ConnectProtocol.ZONE_REGEX.matches(it.zoneId) }
            .filter { ConnectProtocol.DATE_REGEX.matches(it.from) && ConnectProtocol.DATE_REGEX.matches(it.to) }
            .filter { r ->
                val days = runCatching {
                    ChronoUnit.DAYS.between(LocalDate.parse(r.from), LocalDate.parse(r.to)) + 1
                }.getOrDefault(1L)
                dao.countInRange(r.zoneId, r.from, r.to) < days
            }
            .map { "${it.zoneId}|${it.from}|${it.to}" }
    }

    /** Signale qu'une synchro vient d'être lancée (état « en cours » immédiat). */
    fun markStarted() {
        _sync.value = SyncUi(phase = SyncPhase.RUNNING, message = "Champimap Connect prépare tes cartes…")
    }

    /** Lancement impossible (module absent ou refus d'Android). */
    fun markLaunchFailed(message: String) {
        _sync.value = SyncUi(phase = SyncPhase.FAILED, message = message)
    }

    // -----------------------------------------------------------------------------------------
    // Résultat et import des fichiers
    // -----------------------------------------------------------------------------------------

    /** Résultat de l'activité Connect (ActivityResultContracts.StartActivityForResult). */
    fun handleResult(context: Context, resultCode: Int, data: Intent?) {
        val app = context.applicationContext
        val snap = SyncSnapshot.parse(data?.getStringExtra(ConnectProtocol.EXTRA_SNAPSHOT))
        AppGraph.appScope.launch {
            // Sinon, on tente l'état écrit par Connect (synchro peut-être finie en arrière-plan).
            if (snap != null) onSnapshot(app, snap) else catchUp(app)
            // Retour sans synchro terminée ni en cours chez Connect : synchro interrompue.
            val current = _sync.value
            val stillRunning = current.snapshot?.let { it.running && !it.finished } ?: false
            if (current.phase == SyncPhase.RUNNING && !stillRunning && resultCode != Activity.RESULT_OK) {
                _sync.update { it.copy(phase = SyncPhase.CANCELLED, message = "Synchro interrompue. Tu peux la relancer quand tu veux.") }
            }
        }
    }

    /** Lit sync/state.json chez Connect pour rattraper une synchro. Vrai si un état a été lu. */
    suspend fun catchUp(context: Context): Boolean {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(Uri.parse(ConnectProtocol.STATE_URI))?.use { s ->
                    s.bufferedReader().readText()
                }
            }.getOrNull()
        }
        val snap = SyncSnapshot.parse(text) ?: return false
        // Une synchro déjà traitée et terminée n'est pas réaffichée.
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (snap.finished && snap.syncId == prefs.getString(KEY_LAST_SYNC_ID, null) && _sync.value.snapshot?.syncId != snap.syncId) {
            return true
        }
        onSnapshot(context, snap)
        return true
    }

    /** Traite un instantané : affichage, import des nouveaux fichiers, fin éventuelle. */
    private suspend fun onSnapshot(context: Context, snap: SyncSnapshot) {
        _sync.update {
            it.copy(
                phase = if (snap.finished) it.phase else SyncPhase.RUNNING,
                snapshot = snap,
                message = snap.step.ifBlank { it.message },
            )
        }
        val count = importFiles(context, snap)
        if (snap.finished) finish(context, snap, count)
    }

    /** Importe les fichiers pas encore importés. Renvoie le nombre total importé pour cette synchro. */
    private suspend fun importFiles(context: Context, snap: SyncSnapshot): Int = importMutex.withLock {
        for (f in snap.files) {
            val key = "${snap.syncId}|${f.name}|${f.size}"
            if (key in imported) continue
            val ok = runCatching { importFile(context, f) }
                .onFailure { ErrorLogger.log("Connect", "Import impossible : ${f.name}", it) }
                .getOrDefault(false)
            if (ok) imported += key
        }
        val total = imported.count { it.startsWith("${snap.syncId}|") }
        _sync.update { it.copy(importedFiles = total) }
        total
    }

    /** Fin de synchro : mémorise la date et l'état. */
    private fun finish(context: Context, snap: SyncSnapshot, imported: Int) {
        val failed = snap.errors.isNotEmpty() && imported == 0
        val now = if (snap.endedAt > 0) snap.endedAt else System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit().putString(KEY_LAST_SYNC_ID, snap.syncId).putInt(KEY_LAST_ERRORS, snap.errors.size)
        if (!failed) edit.putLong(KEY_LAST_SYNC, now)
        edit.apply()
        if (!failed) _lastSync.value = now
        _sync.update {
            it.copy(
                phase = if (failed) SyncPhase.FAILED else SyncPhase.DONE,
                message = when {
                    failed -> "La synchro a échoué. Vérifie ta connexion puis relance-la."
                    snap.errors.isNotEmpty() -> "Synchro terminée, avec ${snap.errors.size} élément(s) à refaire plus tard."
                    else -> "Tout est prêt !"
                },
            )
        }
    }

    /** Charge la date de dernière synchro depuis les préférences privées. */
    fun loadLastSync(context: Context): Long {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_SYNC, 0L)
        _lastSync.value = v
        return v
    }

    /** Nombre d'éléments en erreur lors de la dernière synchro. */
    fun lastErrors(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LAST_ERRORS, 0)

    // -----------------------------------------------------------------------------------------
    // Import d'un fichier selon son nom
    // -----------------------------------------------------------------------------------------

    /** Importe un fichier reçu. Faux si le nom ou l'URI n'est pas reconnu. */
    private suspend fun importFile(context: Context, f: SyncFile): Boolean = withContext(Dispatchers.IO) {
        val uri = Uri.parse(f.uri)
        // Sécurité : on n'accepte que des fichiers du FileProvider de Connect.
        if (uri.scheme != "content" || uri.authority != ConnectProtocol.FILES_AUTHORITY) return@withContext false
        val name = f.name
        when {
            name.startsWith(ConnectProtocol.FILE_MAP_PREFIX) && name.endsWith(".pmtiles") -> {
                val code = deptOf(name, ConnectProtocol.FILE_MAP_PREFIX, ".pmtiles") ?: return@withContext false
                copyUri(context, uri, File(mapsDir(context), "$code.pmtiles"))
            }
            name.startsWith(ConnectProtocol.FILE_CONTOUR_PREFIX) && name.endsWith(".geojson") -> {
                val code = deptOf(name, ConnectProtocol.FILE_CONTOUR_PREFIX, ".geojson") ?: return@withContext false
                copyUri(context, uri, File(contoursDir(context), "$code.geojson"))
            }
            name.startsWith(ConnectProtocol.FILE_HOSPITALS_PREFIX) && name.endsWith(".json") -> {
                val code = deptOf(name, ConnectProtocol.FILE_HOSPITALS_PREFIX, ".json") ?: return@withContext false
                importHospitals(code, readText(context, uri))
            }
            name == ConnectProtocol.FILE_WEATHER -> importWeather(readText(context, uri))
            name == ConnectProtocol.FILE_ANTIPOISON -> {
                val text = readText(context, uri)
                // On ne remplace la liste que si elle contient au moins un centre lisible.
                if (PoisonCenters.parse(text).isEmpty()) return@withContext false
                writeAtomic(antipoisonFile(context)) { it.write(text.toByteArray()) }
                true
            }
            else -> false
        }
    }

    /** Code département extrait du nom (validé : jamais de chemin). */
    private fun deptOf(name: String, prefix: String, suffix: String): String? =
        name.removePrefix(prefix).removeSuffix(suffix).takeIf { ConnectProtocol.DEPT_REGEX.matches(it) }

    private fun readText(context: Context, uri: Uri): String =
        context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: error("Fichier illisible")

    /** Copie un contenu dans un fichier (via un fichier temporaire, pour ne jamais laisser de carte à moitié écrite). */
    private fun copyUri(context: Context, uri: Uri, target: File): Boolean {
        val input = context.contentResolver.openInputStream(uri) ?: return false
        input.use { s -> writeAtomic(target) { out -> s.copyTo(out) } }
        return true
    }

    private fun writeAtomic(target: File, write: (java.io.OutputStream) -> Unit) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.outputStream().use(write)
        if (target.exists()) target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /**
     * Urgences d'un département : remplace celles déjà connues.
     * Format toléré : tableau d'objets {id, name, lat, lon, phone} ou {"hospitals": [...]}.
     */
    private suspend fun importHospitals(code: String, text: String): Boolean {
        val root = ConnectProtocol.json.parseToJsonElement(text)
        val list = root.objectList("hospitals", "items", "elements").mapNotNull { o ->
            val lat = o.dblOf("lat", "latitude") ?: return@mapNotNull null
            val lon = o.dblOf("lon", "lng", "longitude") ?: return@mapNotNull null
            val name = o.strOf("name", "nom") ?: "Urgences"
            Hospital(
                id = o.strOf("id") ?: "$code-${"%.5f".format(java.util.Locale.US, lat)}-${"%.5f".format(java.util.Locale.US, lon)}",
                name = name,
                lat = lat,
                lon = lon,
                phone = o.strOf("phone", "telephone", "tel"),
                departement = code,
            )
        }
        // Remplace les urgences du département par la liste à jour (une seule transaction).
        val dao = AppGraph.db.hospitalDao()
        AppGraph.db.withTransaction {
            dao.deleteDepartement(code)
            if (list.isNotEmpty()) dao.upsertAll(list)
        }
        return true
    }

    /**
     * Météo : formats tolérés
     *  - liste de jours {zoneId, date, rainMm, tMinC, tMaxC} (à la racine ou sous "days") ;
     *  - zones façon Open-Meteo {zones: [{zoneId, daily: {time, precipitation_sum, temperature_2m_min, temperature_2m_max}}]}.
     */
    private suspend fun importWeather(text: String): Boolean {
        val root = ConnectProtocol.json.parseToJsonElement(text)
        val days = mutableListOf<WeatherDay>()
        // Format « jours ».
        root.objectList("days", "weather").forEach { o ->
            val zone = o.strOf("zoneId", "zone") ?: return@forEach
            val date = o.strOf("date", "day") ?: return@forEach
            if (!ConnectProtocol.ZONE_REGEX.matches(zone) || !ConnectProtocol.DATE_REGEX.matches(date)) return@forEach
            days += WeatherDay(
                zoneId = zone,
                date = date,
                rainMm = o.dblOf("rainMm", "rain", "precipitation") ?: return@forEach,
                tMinC = o.dblOf("tMinC", "tmin", "tMin") ?: return@forEach,
                tMaxC = o.dblOf("tMaxC", "tmax", "tMax") ?: return@forEach,
            )
        }
        // Format « zones + daily ».
        root.objectList("zones").forEach { z ->
            val zone = z.strOf("zoneId", "zone") ?: return@forEach
            if (!ConnectProtocol.ZONE_REGEX.matches(zone)) return@forEach
            val daily = z["daily"] as? JsonObject ?: return@forEach
            val time = (daily["time"] as? JsonArray)?.map { (it as? JsonPrimitive)?.content } ?: return@forEach
            fun col(vararg keys: String): List<Double?> = keys.firstNotNullOfOrNull { daily[it] as? JsonArray }
                ?.map { (it as? JsonPrimitive)?.doubleOrNull } ?: emptyList()
            val rain = col("precipitation_sum", "rain_sum", "rainMm")
            val tMin = col("temperature_2m_min", "tMinC")
            val tMax = col("temperature_2m_max", "tMaxC")
            time.forEachIndexed { i, d ->
                if (d == null || !ConnectProtocol.DATE_REGEX.matches(d)) return@forEachIndexed
                val r = rain.getOrNull(i) ?: return@forEachIndexed
                val lo = tMin.getOrNull(i) ?: return@forEachIndexed
                val hi = tMax.getOrNull(i) ?: return@forEachIndexed
                days += WeatherDay(zoneId = zone, date = d, rainMm = r, tMinC = lo, tMaxC = hi)
            }
        }
        if (days.isEmpty()) return false
        days.chunked(500).forEach { AppGraph.db.weatherDao().upsertAll(it) }
        return true
    }
}
