package fr.pushpull.champimap.feature.groupe

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.journeyapps.barcodescanner.ScanOptions
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiOutlinedButton
import fr.pushpull.champimap.feature.bonus.champiText

/*
 * Petits outils d'interface partagés par les écrans du module groupe :
 * QR codes (création et options de scan), autorisations à l'exécution,
 * choix « à boutons » et carte de message.
 */

// ------------------------------------------------------------------------------- QR codes

/**
 * Fabrique l'image d'un QR code (noir sur blanc, quel que soit le thème, pour que tous les
 * téléphones le lisent bien). Renvoie null si le texte est trop long pour un QR code.
 */
fun qrBitmap(text: String, size: Int = 720): Bitmap? = try {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 2,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) pixels[row + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
} catch (e: Exception) {
    null
}

/** Affiche un QR code carré, net (sans lissage), sur fond blanc. */
@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val bmp = remember(text) { qrBitmap(text) }
    Box(
        modifier.fillMaxWidth().aspectRatio(1f).background(Color.White, RoundedCornerShape(16.dp)).padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = "QR code",
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                filterQuality = FilterQuality.None,
            )
        } else {
            Text("Trop de données pour un QR code.", color = Color.Black, textAlign = TextAlign.Center)
        }
    }
}

/** Options du scanner de QR code (zxing) : QR seulement, portrait, sans bip. */
fun qrScanOptions(prompt: String): ScanOptions = ScanOptions()
    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
    .setPrompt(prompt)
    .setBeepEnabled(false)
    .setOrientationLocked(true)

// ------------------------------------------------------------------------------- Autorisations

/**
 * Autorisations nécessaires à la sortie à plusieurs en Bluetooth :
 * Android 12+ : rechercher, se connecter et s'annoncer (« appareils à proximité ») ;
 * avant : la position précise (exigée par Android pour le scan Bluetooth).
 */
fun bluetoothPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.BLUETOOTH_ADVERTISE,
    ) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

/** Toutes ces autorisations sont-elles accordées ? */
fun hasPermissions(context: Context, perms: Array<String>): Boolean =
    perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

/** État d'un groupe d'autorisations : accordées ? + fonction pour les demander. */
class PermissionGate(val granted: Boolean, val request: () -> Unit)

/** Suit un groupe d'autorisations et permet de les demander (fenêtre système). */
@Composable
fun rememberPermissionGate(perms: Array<String>, onResult: (Boolean) -> Unit = {}): PermissionGate {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasPermissions(context, perms)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = hasPermissions(context, perms)
        onResult(granted)
    }
    return PermissionGate(granted) {
        if (hasPermissions(context, perms)) {
            granted = true
            onResult(true)
        } else launcher.launch(perms)
    }
}

/**
 * Demande d'activation du Bluetooth (fenêtre système « Autoriser Champimap à activer le
 * Bluetooth ? »). Renvoie la fonction qui ouvre la demande ; `onResult(true)` si le
 * Bluetooth est allumé au retour.
 */
@Composable
fun rememberBluetoothEnabler(onResult: (Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        onResult(BleLink.isEnabled(context))
    }
    return {
        if (BleLink.isEnabled(context)) {
            onResult(true)
        } else {
            try {
                launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: Exception) {
                // Autorisation BLUETOOTH_CONNECT absente ou écran système introuvable.
                onResult(false)
            }
        }
    }
}

// ------------------------------------------------------------------------------- Éléments d'écran

/** Choix entre quelques options sous forme de gros boutons côte à côte (le choisi est plein). */
@Composable
fun ChoiceButtons(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { i, label ->
            if (i == selected) ChampiButton(label, onClick = { onSelect(i) }, modifier = Modifier.weight(1f))
            else ChampiOutlinedButton(label, onClick = { onSelect(i) }, modifier = Modifier.weight(1f))
        }
    }
}

/** Carte de message (information, ou erreur en rouge). */
@Composable
fun MessageCard(text: String, error: Boolean = false) {
    ChampiCard(Modifier.fillMaxWidth()) {
        Text(
            champiText(text),
            style = MaterialTheme.typography.bodyMedium,
            color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Texte courant d'un écran. */
@Composable
fun BodyText(text: String, modifier: Modifier = Modifier, center: Boolean = false) {
    Text(
        champiText(text),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (center) TextAlign.Center else TextAlign.Start,
        modifier = modifier,
    )
}
