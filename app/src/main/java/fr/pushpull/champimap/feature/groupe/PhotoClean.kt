package fr.pushpull.champimap.feature.groupe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.OutputStream

/*
 * Nettoyage des photos avant partage : retire la position GPS (et toutes les autres
 * métadonnées) en RÉENCODANT l'image : BitmapFactory décode les pixels seuls, compress()
 * écrit un JPEG neuf sans aucune donnée EXIF.
 *
 * Le réencodage perdrait l'information « tourner de 90° » : on lit donc l'orientation
 * (ExifInterface du système Android, pas la bibliothèque androidx) et on applique la
 * rotation aux pixels avant d'écrire.
 */
internal object PhotoClean {

    /** Taille maximale (plus grand côté) des photos partagées sans métadonnées. */
    private const val MAX_SIDE = 2560

    /** Qualité JPEG du réencodage. */
    private const val QUALITY = 88

    /**
     * Écrit une copie de `source` sans aucune métadonnée dans `out`.
     * @return false si l'image n'a pas pu être lue (rien n'est écrit dans ce cas).
     */
    fun writeWithoutMetadata(source: File, out: OutputStream): Boolean {
        // 1. Dimensions seulement, pour choisir un facteur de réduction (économie de mémoire).
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIDE) sample *= 2

        // 2. Décodage des pixels (sans métadonnées).
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val decoded = try {
            BitmapFactory.decodeFile(source.absolutePath, opts)
        } catch (e: OutOfMemoryError) {
            null
        } ?: return false

        // 3. Orientation d'origine appliquée aux pixels.
        val rotated = applyOrientation(decoded, readOrientation(source))
        return try {
            rotated.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
        } finally {
            if (rotated !== decoded) rotated.recycle()
            decoded.recycle()
        }
    }

    /** Orientation EXIF de la photo (ORIENTATION_NORMAL si illisible). */
    private fun readOrientation(file: File): Int = try {
        ExifInterface(file.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    } catch (e: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }

    /** Tourne / retourne l'image selon l'orientation EXIF. */
    private fun applyOrientation(src: Bitmap, orientation: Int): Bitmap {
        val m = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> m.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> m.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { m.postRotate(90f); m.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { m.postRotate(270f); m.postScale(-1f, 1f) }
            else -> return src
        }
        return try {
            Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        } catch (e: OutOfMemoryError) {
            src
        }
    }
}
