package fr.pushpull.champimap.feature.sortie

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.core.graphics.ColorUtils
import fr.pushpull.champimap.core.db.DepartType

/*
 * Images des repères de carte, dessinées en Bitmap (aucune image téléchargée, aucun sprite distant) :
 *  - épingle colorée selon l'espèce (disque clair au centre) ;
 *  - épingle grise + cercle pointillé pour un spot sans récolte ;
 *  - rond de départ : voiture (garé) ou bottes (à pied), avec badge « chaîne » si sortie partagée.
 *
 * Pictos de l'easter egg (markerStyle = MUSHROOMS ou TREES) :
 * TODO(bonus) : quand le module bonus fournira ses pictos, dessiner le picto dans le disque central
 *  de l'épingle (ex. via une fonction `pictoBitmap(key, sizePx)` du module bonus, à convertir depuis
 *  `Picto(key, modifier)`). Point d'extension : paramètre `pictoKey` de `pin()` ci-dessous,
 *  aujourd'hui ignoré (le disque clair reste vide).
 */
object MapIcons {

    /** Gris des spots sans récolte. */
    const val EMPTY_COLOR = "#8D948F"

    /** Nom de l'image d'épingle pour une couleur donnée (ex. « pin-9a6a42 »). */
    fun pinName(colorHex: String?, pictoKey: String? = null): String =
        if (colorHex == null) "pin-vide" else "pin-" + colorHex.removePrefix("#").lowercase() + (pictoKey?.let { "-$it" } ?: "")

    /** Nom de l'image de départ. */
    fun departName(type: DepartType, shared: Boolean): String =
        "depart-" + type.name.lowercase() + if (shared) "-chaine" else ""

    /** Convertit « #RRGGBB » en couleur Android (gris par défaut si illisible). */
    fun parseColor(hex: String?): Int =
        runCatching { android.graphics.Color.parseColor(hex ?: EMPTY_COLOR) }.getOrDefault(android.graphics.Color.GRAY)

    /**
     * Épingle (goutte inversée) de 34 × 46 dp. Spot vide : gris + cercle pointillé autour.
     * @param pictoKey réservé aux pictos de l'easter egg (voir TODO en tête de fichier).
     */
    @Suppress("UNUSED_PARAMETER")
    fun pin(context: Context, colorHex: String?, pictoKey: String? = null): Bitmap {
        val d = context.resources.displayMetrics.density
        val empty = colorHex == null
        val w = ((if (empty) 46 else 34) * d).toInt()
        val h = ((if (empty) 52 else 46) * d).toInt()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = w / 2f
        val r = 13f * d
        val cy = r + 3f * d + if (empty) 3f * d else 0f
        val tipY = h - 2f * d

        // Cercle pointillé des spots vides.
        if (empty) {
            val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 1.8f * d
                color = parseColor(EMPTY_COLOR)
                pathEffect = DashPathEffect(floatArrayOf(4f * d, 3f * d), 0f)
            }
            c.drawCircle(cx, cy, r + 7f * d, dash)
        }

        // Corps de l'épingle : cercle + pointe.
        val body = Path().apply {
            addCircle(cx, cy, r, Path.Direction.CW)
            moveTo(cx - r * 0.72f, cy + r * 0.69f)
            lineTo(cx, tipY)
            lineTo(cx + r * 0.72f, cy + r * 0.69f)
            close()
        }
        val fillColor = parseColor(colorHex ?: EMPTY_COLOR)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = fillColor }
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f * d
            color = ColorUtils.blendARGB(fillColor, android.graphics.Color.BLACK, 0.35f)
        }
        c.drawPath(body, fill)
        c.drawPath(body, stroke)

        // Disque clair au centre (accueillera le picto de l'easter egg).
        val inner = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFDFBF5.toInt() }
        c.drawCircle(cx, cy, r * 0.48f, inner)
        return bmp
    }

    /** Rond de départ (42 dp) : voiture ou bottes, badge chaîne si partagé. */
    fun depart(context: Context, type: DepartType, shared: Boolean): Bitmap {
        val d = context.resources.displayMetrics.density
        val size = (42 * d).toInt()
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val cx = size / 2f
        val r = 17f * d
        val mousse = 0xFF2F5D46.toInt()
        c.drawCircle(cx, cx, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
        c.drawCircle(cx, cx, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * d; color = mousse })
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mousse; style = Paint.Style.FILL }
        if (type == DepartType.CAR) drawCar(c, cx, cx, d, ink) else drawBoots(c, cx, cx, d, ink)
        if (shared) drawChainBadge(c, size - 9f * d, 9f * d, d)
        return bmp
    }

    /** Voiture vue de profil : caisse, habitacle, deux roues. */
    private fun drawCar(c: Canvas, cx: Float, cy: Float, d: Float, p: Paint) {
        c.drawRoundRect(RectF(cx - 11f * d, cy - 1f * d, cx + 11f * d, cy + 6f * d), 2.5f * d, 2.5f * d, p)
        val cabin = Path().apply {
            moveTo(cx - 7f * d, cy - 1f * d)
            lineTo(cx - 4f * d, cy - 7f * d)
            lineTo(cx + 5f * d, cy - 7f * d)
            lineTo(cx + 8f * d, cy - 1f * d)
            close()
        }
        c.drawPath(cabin, p)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
        c.drawRect(cx - 3.5f * d, cy - 5.5f * d, cx + 0f * d, cy - 1.5f * d, white)
        c.drawRect(cx + 1f * d, cy - 5.5f * d, cx + 5f * d, cy - 1.5f * d, white)
        c.drawCircle(cx - 6f * d, cy + 6.5f * d, 3f * d, p)
        c.drawCircle(cx + 6f * d, cy + 6.5f * d, 3f * d, p)
        c.drawCircle(cx - 6f * d, cy + 6.5f * d, 1.2f * d, white)
        c.drawCircle(cx + 6f * d, cy + 6.5f * d, 1.2f * d, white)
    }

    /** Paire de bottes : tige + pied, deux fois. */
    private fun drawBoots(c: Canvas, cx: Float, cy: Float, d: Float, p: Paint) {
        fun boot(x: Float) {
            val path = Path().apply {
                moveTo(x - 3f * d, cy - 9f * d)
                lineTo(x + 2f * d, cy - 9f * d)
                lineTo(x + 2f * d, cy + 2f * d)
                lineTo(x + 7f * d, cy + 4f * d)
                lineTo(x + 7f * d, cy + 8f * d)
                lineTo(x - 3f * d, cy + 8f * d)
                close()
            }
            c.drawPath(path, p)
        }
        boot(cx - 7f * d)
        boot(cx + 1f * d)
    }

    /** Petit badge orange avec deux maillons de chaîne (sortie partagée). */
    private fun drawChainBadge(c: Canvas, x: Float, y: Float, d: Float) {
        c.drawCircle(x, y, 8f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE08A2E.toInt() })
        val link = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.6f * d
            color = android.graphics.Color.WHITE
        }
        c.drawRoundRect(RectF(x - 5.5f * d, y - 2f * d, x + 0.5f * d, y + 2f * d), 2f * d, 2f * d, link)
        c.drawRoundRect(RectF(x - 0.5f * d, y - 2f * d, x + 5.5f * d, y + 2f * d), 2f * d, 2f * d, link)
    }
}
