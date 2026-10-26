package fr.pushpull.champimap.feature.reglages

import android.Manifest
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiOutlinedButton
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.core.ui.VSpace
import fr.pushpull.champimap.feature.bonus.champiText

/*
 * Preuves « hors ligne » : liste des permissions déclarées par l'appli, lue en direct
 * dans le système (PackageManager), avec leur état accordé / refusé, et vérification
 * réelle de l'absence de la permission INTERNET. Lien vers le code source.
 */

const val SOURCE_URL = "https://github.com/PushPull-Tools/Champimap"

/** Une permission déclarée et son état. */
private data class PermLine(val name: String, val label: String, val granted: Boolean)

/** Libellés lisibles des permissions connues (les autres affichent leur nom technique). */
private val LABELS = mapOf(
    Manifest.permission.ACCESS_FINE_LOCATION to "Position précise (spots, trace)",
    Manifest.permission.ACCESS_COARSE_LOCATION to "Position approximative",
    Manifest.permission.FOREGROUND_SERVICE to "Service de sortie en cours",
    "android.permission.FOREGROUND_SERVICE_LOCATION" to "Suivi GPS pendant la sortie",
    "android.permission.POST_NOTIFICATIONS" to "Notifications",
    Manifest.permission.CAMERA to "Appareil photo (photos, QR codes)",
    Manifest.permission.RECORD_AUDIO to "Micro (mémo vocal)",
    Manifest.permission.ACCESS_WIFI_STATE to "État du Wi-Fi (sortie à plusieurs)",
    Manifest.permission.CHANGE_WIFI_STATE to "Wi-Fi Direct (sortie à plusieurs)",
    "android.permission.NEARBY_WIFI_DEVICES" to "Appareils Wi-Fi à proximité",
    Manifest.permission.REQUEST_INSTALL_PACKAGES to "Installer le module Connect",
    Manifest.permission.USE_BIOMETRIC to "Empreinte (verrouillage)",
    Manifest.permission.VIBRATE to "Vibreur",
    Manifest.permission.INTERNET to "Accès Internet",
)

/** Lit les infos du paquet avec la liste des permissions (API 33+ et anciennes). */
@Suppress("DEPRECATION")
private fun packageInfo(context: Context): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
    } else {
        context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
    }

/** Permissions demandées par l'appli, avec l'état réel accordé par Android. */
private fun readPermissions(context: Context): List<PermLine> = try {
    val info = packageInfo(context)
    val names = info.requestedPermissions ?: emptyArray()
    val flags = info.requestedPermissionsFlags ?: IntArray(0)
    names.mapIndexed { i, name ->
        val granted = i < flags.size && (flags[i] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0
        PermLine(name, LABELS[name] ?: name.substringAfterLast('.'), granted)
    }.sortedWith(compareByDescending<PermLine> { it.granted }.thenBy { it.label })
} catch (e: Exception) {
    ErrorLogger.log("Preuves", "Lecture des permissions impossible", e)
    emptyList()
}

/** Preuves « hors ligne ». Signature appelée par AppNav. */
@Composable
fun PreuvesScreen(nav: NavController) {
    val context = LocalContext.current
    // Relecture à chaque retour sur l'écran (l'utilisateur a pu changer une autorisation).
    var refresh by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val perms = remember(refresh) { readPermissions(context) }
    // Vérification réelle : INTERNET ne doit figurer ni dans les permissions demandées ni accordées.
    val internetAbsent = remember(refresh) {
        perms.none { it.name == Manifest.permission.INTERNET } &&
            context.checkSelfPermission(Manifest.permission.INTERNET) != PackageManager.PERMISSION_GRANTED
    }

    ChampiScreen(title = "Preuves « hors ligne »", onBack = { nav.popBackStack() }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            InternetBadge(internetAbsent)
            Text(
                champiText("Ces autorisations sont lues en direct dans Android, pas écrites à la main. Tu peux les retirer à tout moment dans les réglages du téléphone."),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SectionTitle("Autorisations de l'appli")
            ChampiCard(Modifier.fillMaxWidth()) {
                perms.forEachIndexed { i, p ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(champiText(p.label), style = MaterialTheme.typography.bodyLarge)
                            Text(p.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (p.granted) "Accordée" else "Refusée",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (p.granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (i < perms.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
            SectionTitle("Code source")
            Text(
                champiText("Le code de Champimap est public : n'importe qui peut vérifier qu'aucune donnée ne sort du téléphone."),
                style = MaterialTheme.typography.bodyMedium,
            )
            ChampiOutlinedButton("Voir le code sur GitHub", onClick = { openInBrowser(context, SOURCE_URL, "Preuves") })
            VSpace(16.dp)
        }
    }
}

/** Ligne mise en avant : « 0 accès Internet » (ou alerte si la vérification échoue). */
@Composable
private fun InternetBadge(ok: Boolean) {
    val bg = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    val fg = if (ok) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Column(
        Modifier.fillMaxWidth().background(bg, RoundedCornerShape(18.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (ok) "0 accès Internet" else "Attention : accès Internet détecté",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.ExtraBold,
            color = fg,
            textAlign = TextAlign.Center,
        )
        Text(
            champiText(
                if (ok) "La permission INTERNET est absente : Android empêche l'appli de se connecter."
                else "Cette version n'est pas conforme. Signale-le sur GitHub.",
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = fg,
            textAlign = TextAlign.Center,
        )
    }
}
