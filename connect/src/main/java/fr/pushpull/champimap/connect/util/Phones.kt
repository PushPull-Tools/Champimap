package fr.pushpull.champimap.connect.util

/*
 * Mise en forme des numéros de téléphone pour l'affichage : chiffres groupés par deux avec des espaces
 * (« 05 55 05 61 23 »). Les numéros internationaux français (+33…) sont ramenés au format national.
 */
object Phones {
    /**
     * Normalise un numéro OpenStreetMap (« +33 5 55 05 61 23 », « 0555056123 », « +33-5-55… »).
     * Renvoie null si vide ; renvoie le texte d'origine (nettoyé) s'il n'est pas reconnu.
     */
    fun format(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        // OSM peut contenir plusieurs numéros séparés par « ; » : on garde le premier.
        val first = raw.split(';').first().trim()
        var digits = first.filter { it.isDigit() || it == '+' }
        if (digits.startsWith("+33")) digits = "0" + digits.removePrefix("+33").trimStart('0')
        else if (digits.startsWith("0033")) digits = "0" + digits.removePrefix("0033").trimStart('0')
        if (digits.length == 10 && digits.startsWith("0") && digits.all { it.isDigit() }) {
            return digits.chunked(2).joinToString(" ")
        }
        return first.ifBlank { null }
    }
}
