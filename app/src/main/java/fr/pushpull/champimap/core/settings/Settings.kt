package fr.pushpull.champimap.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Thèmes disponibles. PSYCHE n'apparaît dans le menu qu'une fois découvert. */
enum class ThemeMode { AUTO, CLAIR, SOMBRE, PSYCHE }

/** Carnet : ton des messages du carnet (succès, niveaux). Sarcastique = taquin, mais bon esprit. */
enum class MessageTone { NORMAL, SARCASTIQUE }

/** Instantané de tous les réglages, observé par l'interface. */
data class AppSettings(
    val onboardingDone: Boolean = false,
    val firstName: String = "",
    /** Codes des départements cochés, ex. ["19","23","87"]. */
    val departements: List<String> = emptyList(),
    val themeMode: ThemeMode = ThemeMode.AUTO,
    /** Mode Mushroom party découvert (geste caché) → « Psyché » apparaît dans Thème. */
    val psycheUnlocked: Boolean = false,
    /** Easter egg trouvé (bouton invisible à côté du prénom) → pictos débloqués. */
    val easterUnlocked: Boolean = false,
    /** Champignon choisi dans l'easter egg (clé de picto), vide si aucun. */
    val avatarKey: String = "",
    /** Ajoute des émojis liés aux textes. */
    val emojis: Boolean = false,
    /** « Tu dis le g toi ? » : champignon → champignong. */
    val champignong: Boolean = false,
    val intoxLinkVisible: Boolean = true,
    val lockEnabled: Boolean = false,
    val discreetMode: Boolean = false,
    val seasonAlerts: Boolean = true,
    /** Sections repliées dans les paramètres (clés), mémorisées. */
    val collapsedSections: Set<String> = emptySet(),
    /** Pioche des messages de départ restants (indices séparés par des virgules). */
    val startMessageBag: String = "",
    val lastStartMessage: Int = -1,
    /** Carnet : ton des messages (Normal / Sarcastique). */
    val messageTone: MessageTone = MessageTone.NORMAL,
    /** Carnet : pluies cachées déjà vues, parmi "leaves", "rain", "mushrooms" (succès secrets). */
    val rainsSeen: Set<String> = emptySet(),
    /** Mode vendeur (expérimental) : saisie des ventes et stats de ventes. Désactivé par défaut. */
    val sellerMode: Boolean = false,
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Lecture et écriture des réglages (DataStore, local au téléphone). */
class SettingsRepository(private val context: Context) {

    private object K {
        val onboardingDone = booleanPreferencesKey("onboardingDone")
        val firstName = stringPreferencesKey("firstName")
        val departements = stringPreferencesKey("departements")
        val themeMode = stringPreferencesKey("themeMode")
        val psycheUnlocked = booleanPreferencesKey("psycheUnlocked")
        val easterUnlocked = booleanPreferencesKey("easterUnlocked")
        val avatarKey = stringPreferencesKey("avatarKey")
        val emojis = booleanPreferencesKey("emojis")
        val champignong = booleanPreferencesKey("champignong")
        val intoxLinkVisible = booleanPreferencesKey("intoxLinkVisible")
        val lockEnabled = booleanPreferencesKey("lockEnabled")
        val discreetMode = booleanPreferencesKey("discreetMode")
        val seasonAlerts = booleanPreferencesKey("seasonAlerts")
        val collapsedSections = stringPreferencesKey("collapsedSections")
        val startMessageBag = stringPreferencesKey("startMessageBag")
        val lastStartMessage = stringPreferencesKey("lastStartMessage")
        // Carnet : ton des messages et pluies cachées vues (ensemble séparé par des virgules).
        val messageTone = stringPreferencesKey("messageTone")
        val rainsSeen = stringPreferencesKey("rainsSeen")
        // Mode vendeur (expérimental).
        val sellerMode = booleanPreferencesKey("sellerMode")
    }

    val flow: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            onboardingDone = p[K.onboardingDone] ?: false,
            firstName = p[K.firstName] ?: "",
            departements = (p[K.departements] ?: "").split(',').filter { it.isNotBlank() },
            themeMode = runCatching { ThemeMode.valueOf(p[K.themeMode] ?: "AUTO") }.getOrDefault(ThemeMode.AUTO),
            psycheUnlocked = p[K.psycheUnlocked] ?: false,
            easterUnlocked = p[K.easterUnlocked] ?: false,
            avatarKey = p[K.avatarKey] ?: "",
            emojis = p[K.emojis] ?: false,
            champignong = p[K.champignong] ?: false,
            intoxLinkVisible = p[K.intoxLinkVisible] ?: true,
            lockEnabled = p[K.lockEnabled] ?: false,
            discreetMode = p[K.discreetMode] ?: false,
            seasonAlerts = p[K.seasonAlerts] ?: true,
            collapsedSections = (p[K.collapsedSections] ?: "").split(',').filter { it.isNotBlank() }.toSet(),
            startMessageBag = p[K.startMessageBag] ?: "",
            lastStartMessage = (p[K.lastStartMessage] ?: "-1").toIntOrNull() ?: -1,
            messageTone = runCatching { MessageTone.valueOf(p[K.messageTone] ?: "NORMAL") }.getOrDefault(MessageTone.NORMAL),
            rainsSeen = (p[K.rainsSeen] ?: "").split(',').filter { it.isNotBlank() }.toSet(),
            sellerMode = p[K.sellerMode] ?: false,
        )
    }

    suspend fun setOnboardingDone(v: Boolean) = context.dataStore.edit { it[K.onboardingDone] = v }
    suspend fun setFirstName(v: String) = context.dataStore.edit { it[K.firstName] = v.trim() }
    suspend fun setDepartements(v: List<String>) = context.dataStore.edit { it[K.departements] = v.joinToString(",") }
    suspend fun setThemeMode(v: ThemeMode) = context.dataStore.edit { it[K.themeMode] = v.name }
    suspend fun setPsycheUnlocked(v: Boolean) = context.dataStore.edit { it[K.psycheUnlocked] = v }
    suspend fun setEasterUnlocked(v: Boolean) = context.dataStore.edit { it[K.easterUnlocked] = v }
    suspend fun setAvatarKey(v: String) = context.dataStore.edit { it[K.avatarKey] = v }
    suspend fun setEmojis(v: Boolean) = context.dataStore.edit { it[K.emojis] = v }
    suspend fun setChampignong(v: Boolean) = context.dataStore.edit { it[K.champignong] = v }
    suspend fun setIntoxLinkVisible(v: Boolean) = context.dataStore.edit { it[K.intoxLinkVisible] = v }
    suspend fun setLockEnabled(v: Boolean) = context.dataStore.edit { it[K.lockEnabled] = v }
    suspend fun setDiscreetMode(v: Boolean) = context.dataStore.edit { it[K.discreetMode] = v }
    suspend fun setSeasonAlerts(v: Boolean) = context.dataStore.edit { it[K.seasonAlerts] = v }
    suspend fun setCollapsedSections(v: Set<String>) = context.dataStore.edit { it[K.collapsedSections] = v.joinToString(",") }
    suspend fun setStartMessageBag(bag: String, last: Int) = context.dataStore.edit {
        it[K.startMessageBag] = bag
        it[K.lastStartMessage] = last.toString()
    }

    // Carnet : ton des messages, et ajout d'une pluie cachée vue ("leaves", "rain" ou "mushrooms").
    suspend fun setMessageTone(v: MessageTone) = context.dataStore.edit { it[K.messageTone] = v.name }
    suspend fun addRainSeen(kind: String) = context.dataStore.edit {
        val seen = (it[K.rainsSeen] ?: "").split(',').filter { k -> k.isNotBlank() }.toSet()
        if (kind !in seen) it[K.rainsSeen] = (seen + kind).sorted().joinToString(",")
    }

    // Mode vendeur (expérimental) : active / coupe la saisie des ventes.
    suspend fun setSellerMode(v: Boolean) = context.dataStore.edit { it[K.sellerMode] = v }
}
