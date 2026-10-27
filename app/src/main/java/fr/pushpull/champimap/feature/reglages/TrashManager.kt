package fr.pushpull.champimap.feature.reglages

import androidx.room.withTransaction
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.db.TrashEntry
import java.io.File

/*
 * Corbeille : restauration et suppression définitive (en cascade, fichiers photo compris),
 * purge automatique des éléments supprimés depuis plus de 30 jours.
 */
object TrashManager {

    const val RETENTION_MS = 30L * 24 * 3600 * 1000

    /** Remet l'élément en place (deletedAt = null). */
    suspend fun restore(e: TrashEntry) {
        val dao = AppGraph.db.backupDao()
        val now = System.currentTimeMillis()
        when (e.kind) {
            "SPOT" -> dao.restoreSpot(e.id, now)
            "SORTIE" -> dao.restoreSortie(e.id, now)
            "RECOLTE" -> dao.restoreRecolte(e.id, now)
            "ITEM" -> dao.restoreItem(e.id, now)
            "PHOTO" -> dao.restorePhoto(e.id)
            "VENTE" -> AppGraph.db.venteDao().restore(e.id, now) // mode vendeur
            else -> Unit
        }
    }

    /** Supprime définitivement l'élément et tout ce qui en dépend. */
    suspend fun deleteForever(e: TrashEntry) {
        val dao = AppGraph.db.backupDao()
        val files = mutableListOf<String>()
        AppGraph.db.withTransaction {
            when (e.kind) {
                "SPOT" -> {
                    dao.recolteIdsOfSpot(e.id).forEach { deleteRecolte(it, files) }
                    deletePhotosOf(e.id, files)
                    dao.deletePositionsOfSpot(e.id)
                    dao.deleteVisitsOfSpot(e.id)
                    dao.deleteSpot(e.id)
                }
                "SORTIE" -> {
                    dao.recolteIdsOfSortie(e.id).forEach { deleteRecolte(it, files) }
                    dao.deleteVisitsOfSortie(e.id)
                    dao.deleteTrack(e.id)
                    dao.deleteSortie(e.id)
                }
                "RECOLTE" -> deleteRecolte(e.id, files)
                "ITEM" -> { deletePhotosOf(e.id, files); dao.deleteItem(e.id) }
                "PHOTO" -> {
                    dao.photo(e.id)?.let { files += it.path }
                    dao.deletePhoto(e.id)
                }
                "VENTE" -> AppGraph.db.venteDao().deleteForever(e.id) // mode vendeur
                else -> Unit
            }
        }
        // Fichiers effacés seulement après la réussite de la transaction.
        files.forEach { path -> runCatching { File(path).delete() } }
    }

    /** Récolte + ses espèces + leurs photos. */
    private suspend fun deleteRecolte(recolteId: String, files: MutableList<String>) {
        val dao = AppGraph.db.backupDao()
        dao.itemIdsOfRecolte(recolteId).forEach { itemId ->
            deletePhotosOf(itemId, files)
            dao.deleteItem(itemId)
        }
        dao.deleteRecolte(recolteId)
    }

    private suspend fun deletePhotosOf(ownerId: String, files: MutableList<String>) {
        val dao = AppGraph.db.backupDao()
        dao.photosOfOwner(ownerId).forEach { p ->
            files += p.path
            dao.deletePhoto(p.id)
        }
    }

    /** Purge des éléments supprimés depuis plus de 30 jours. Renvoie le nombre d'éléments purgés. */
    suspend fun purgeOld(): Int {
        val limit = System.currentTimeMillis() - RETENTION_MS
        val old = AppGraph.db.backupDao().trashOnce().filter { it.deletedAt < limit }
        old.forEach { runCatching { deleteForever(it) } }
        return old.size
    }
}
