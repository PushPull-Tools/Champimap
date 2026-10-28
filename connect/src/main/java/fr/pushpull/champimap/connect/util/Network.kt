package fr.pushpull.champimap.connect.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/*
 * Type de connexion du téléphone (uniquement dans Connect : l'appli principale n'a aucune permission réseau,
 * pas même ACCESS_NETWORK_STATE). Sert à conseiller le Wi-Fi avant les gros téléchargements
 * (cartes, modèle d'identification).
 */
object Network {
    /** Connexion actuelle : aucune, Wi-Fi / Ethernet non facturé, ou données mobiles (ou réseau facturé). */
    enum class Kind { NONE, UNMETERED, METERED }

    /**
     * « Données mobiles » dès que le réseau actif n'est ni Wi-Fi ni Ethernet, ou qu'il est marqué
     * comme facturé (partage de connexion, forfait limité…).
     */
    fun current(context: Context): Kind {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return Kind.NONE
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return Kind.NONE
        val wifiOrEthernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        val notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return if (wifiOrEthernet && notMetered) Kind.UNMETERED else Kind.METERED
    }
}
