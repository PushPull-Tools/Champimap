package fr.pushpull.champimap.feature.sortie

import android.app.Service
import android.content.Intent
import android.os.IBinder

/** SOCLE : service de suivi GPS pendant une sortie (implémenté par le module sortie). */
class SortieTrackingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null
}
