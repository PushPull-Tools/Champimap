package fr.pushpull.champimap.feature.bonus

import androidx.compose.runtime.Composable
import fr.pushpull.champimap.core.ui.theme.LocalChampiStyle

/*
 * Transformations des textes affichés selon les options d'affichage :
 *  - « Affichage avec émojis » (toujours actif en psyché) : un émoji lié au texte en fin de phrase ;
 *  - « Tu dis le g toi ? » : champignon(s) → champignong(s) ;
 *  - émoji devant les libellés du menu du bas (emojiFor).
 */

/** Une règle : si le texte contient un des mots (début de mot, sans casse), on ajoute l'émoji. */
private class EmojiRule(val emoji: String, words: String) {
    // Le mot doit commencer après une non-lettre : « sorties » correspond à « sortie », pas « ressortie ».
    val regex = Regex("(?<!\\p{L})(?:$words)", RegexOption.IGNORE_CASE)
}

/**
 * Table mots-clés → émoji, de la plus précise à la plus générale :
 * la première règle trouvée gagne (« cèpe » avant « champignon »).
 */
private val RULES = listOf(
    EmojiRule("🍄‍🟫", "cèpe|cepe|bolet"),
    EmojiRule("🦺", "chasse|battue"),
    EmojiRule("🌧️", "pluie|pleu|averse|orage"),
    EmojiRule("🌦️", "météo|meteo"),
    EmojiRule("🧺", "récolte|recolte|panier|cueillette|cueilli"),
    EmojiRule("🏆", "nouveau record|record|podium"),
    EmojiRule("🍄", "champignon|trouvé|trouve |girolle|morille|trompette|coulemelle|amanite|espèce"),
    EmojiRule("🌲", "forêt|foret|bois\\b|arbre|sapin|châtaign|chataign|mousse"),
    EmojiRule("🥾", "sortie|balade|randonn|démarr|demarr|c'est parti|marche"),
    EmojiRule("📍", "spot|coin|repère|repere|position"),
    EmojiRule("📊", "stat"),
    EmojiRule("🗺️", "carte"),
    EmojiRule("📅", "calendrier|saison"),
    EmojiRule("🌙", "nuit"),
    EmojiRule("🌕", "lune"),
    EmojiRule("🌅", "soleil|coucher"),
    EmojiRule("📷", "photo"),
    EmojiRule("⚖️", "poids|kg\\b"),
    EmojiRule("🍳", "omelette|table|repas|cuisine"),
    EmojiRule("🤫", "secret|discret"),
    EmojiRule("🤝", "plusieurs|groupe|partag|rejoindre|ensemble"),
    EmojiRule("💾", "sauvegard|export|import"),
    EmojiRule("⚙️", "réglage|reglage|paramètre|parametre"),
    EmojiRule("👋", "bonjour|bienvenue|salut"),
)

/**
 * Mots qui interdisent tout émoji : on ne plaisante pas avec la sécurité
 * (en plus de SafetyTheme qui coupe déjà les émojis sur les écrans dédiés).
 */
private val NO_EMOJI = Regex(
    "(?<!\\p{L})(?:intox|toxique|mortel|danger|urgence|samu|antipoison|perdu|112|15 ·|hôpital|hopital|psychotrope|à éviter)",
    RegexOption.IGNORE_CASE,
)

/** Libellés du menu du bas : leur émoji est placé DEVANT par emojiFor, pas à la fin. */
private val MENU_EMOJIS = mapOf(
    "Accueil" to "🏡",
    "Carte" to "🗺️",
    "Stats" to "📊",
    "Espèces" to "🍄",
    "Plus" to "✨",
)

/** Au-delà de cette longueur, un texte peut recevoir 2 émojis (1 seul pour un texte court). */
private const val SHORT_TEXT = 60

/**
 * Transforme un texte affiché selon les options : émojis liés au texte, « champignong ».
 * À appeler sur tout texte visible (les composants partagés le font déjà).
 */
@Composable
fun champiText(text: String): String {
    val style = LocalChampiStyle.current
    var result = text
    if (style.emojis) result = withEmojis(result)
    if (style.champignong) result = champignongify(result)
    return result
}

/** « champignon(s) » → « champignong(s) », en respectant la majuscule. */
fun champignongify(text: String): String =
    text.replace(Regex("([Cc])hampignon(s?)\\b")) { m -> "${m.groupValues[1]}hampignong${m.groupValues[2]}" }

/** Émoji placé devant un libellé du menu quand l'affichage avec émojis est actif (vide sinon). */
@Composable
fun emojiFor(label: String): String {
    if (!LocalChampiStyle.current.emojis) return ""
    val emoji = MENU_EMOJIS[label] ?: return ""
    return "$emoji "
}

/**
 * Ajoute en fin de texte le ou les émojis liés aux mots trouvés, sans doublon.
 * Rien n'est ajouté à un texte vide, purement numérique, déjà décoré d'un émoji,
 * à un libellé du menu ou à un texte de sécurité.
 */
internal fun withEmojis(text: String): String {
    val trimmed = text.trim()
    if (trimmed.isEmpty() || trimmed.none { it.isLetter() }) return text
    if (trimmed in MENU_EMOJIS) return text
    if (containsEmoji(trimmed)) return text
    if (NO_EMOJI.containsMatchIn(trimmed)) return text

    val max = if (trimmed.length <= SHORT_TEXT) 1 else 2
    val found = LinkedHashSet<String>()
    for (rule in RULES) {
        if (found.size >= max) break
        if (rule.regex.containsMatchIn(trimmed)) found.add(rule.emoji)
    }
    if (found.isEmpty()) return text
    return text.trimEnd() + " " + found.joinToString(" ")
}

/**
 * Détection simple d'un émoji déjà présent (paires de substitution UTF-16 ou
 * symboles divers U+2600–U+27BF), pour ne jamais en empiler.
 */
private fun containsEmoji(text: String): Boolean =
    text.any { Character.isSurrogate(it) || it.code in 0x2600..0x27BF }
