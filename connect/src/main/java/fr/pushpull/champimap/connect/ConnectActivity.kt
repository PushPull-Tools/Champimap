package fr.pushpull.champimap.connect

import android.app.Activity
import android.os.Bundle

/** SOCLE : activité de synchronisation (implémentée par le module connect). */
class ConnectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        finish()
    }
}
