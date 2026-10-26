package fr.pushpull.champimap.feature.reglages

import android.app.KeyguardManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import android.view.WindowManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.VSpace

/*
 * Verrouillage de l'appli (option « Verrouillage code ou empreinte ») :
 * BiometricPrompt (empreinte forte ou code du téléphone) à l'ouverture et au retour
 * après plus d'1 minute en arrière-plan. Le contenu reste en place sous un écran opaque,
 * pour ne pas perdre une sortie en cours. Applique aussi le mode discret (FLAG_SECURE).
 */

/** État du verrou, en mémoire seulement : un redémarrage de l'appli redemande toujours. */
private object LockState {
    @Volatile var unlocked: Boolean = false
    /** Moment (horloge depuis le démarrage) du passage en arrière-plan, 0 si au premier plan. */
    @Volatile var backgroundAt: Long = 0L
    const val RELOCK_AFTER_MS = 60_000L
}

/** Outils du verrou, partagés avec les paramètres. */
object LockHelper {

    /** Combinaison autorisée : STRONG + code n'est pas acceptée par Android 9 et 10. */
    fun authenticators(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
        else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    /** Le téléphone a-t-il un code / schéma / empreinte ? Sinon le verrou ne pourrait jamais s'ouvrir. */
    fun deviceSecure(context: Context): Boolean =
        (context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager)?.isDeviceSecure == true

    /** Raison qui empêche d'activer le verrou, ou null si tout va bien. */
    fun unavailableReason(context: Context): String? =
        if (!deviceSecure(context)) "Ton téléphone n'a ni code ni empreinte. Ajoute un verrouillage d'écran dans les réglages Android, puis réessaie."
        else null

    /** Retrouve la FragmentActivity derrière un Context Compose (qui peut être enveloppé). */
    fun findActivity(context: Context): FragmentActivity? {
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is FragmentActivity) return c
            c = c.baseContext
        }
        return null
    }
}

/** Écran de verrouillage affiché par-dessus l'appli si l'option est active. Signature fixe (MainActivity). */
@Composable
fun LockGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { LockHelper.findActivity(context) }
    val settings by AppGraph.settings.flow.collectAsState(initial = null)
    val s = settings
    var unlocked by remember { mutableStateOf(LockState.unlocked) }
    var promptOpen by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // Mode discret : pas d'aperçu dans les applis récentes ni de capture d'écran.
    val discreet = s?.discreetMode == true
    DisposableEffect(activity, discreet) {
        if (discreet) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { }
    }

    // Arrière-plan > 1 min → on reverrouille au retour.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> LockState.backgroundAt = SystemClock.elapsedRealtime()
                Lifecycle.Event.ON_START -> {
                    val at = LockState.backgroundAt
                    if (at != 0L && SystemClock.elapsedRealtime() - at > LockState.RELOCK_AFTER_MS) {
                        LockState.unlocked = false
                        unlocked = false
                    }
                    LockState.backgroundAt = 0L
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    /** Ouvre la demande d'empreinte / de code. */
    fun prompt() {
        val act = activity ?: run { error = "Verrouillage indisponible sur cet écran."; return }
        if (promptOpen) return
        // Verrou d'écran retiré du téléphone entre-temps : on laisse passer plutôt que de bloquer pour toujours.
        if (!LockHelper.deviceSecure(act)) {
            ErrorLogger.log("Verrouillage", "Aucun verrouillage d'écran sur le téléphone : appli ouverte sans contrôle")
            LockState.unlocked = true; unlocked = true; return
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                promptOpen = false; error = null
                LockState.unlocked = true; unlocked = true
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                promptOpen = false
                error = if (errorCode == BiometricPrompt.ERROR_USER_CANCELED || errorCode == BiometricPrompt.ERROR_CANCELED) null
                else errString.toString()
            }
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Déverrouiller Champimap")
            .setSubtitle("Empreinte ou code du téléphone")
            .setAllowedAuthenticators(LockHelper.authenticators())
            .build()
        try {
            promptOpen = true
            BiometricPrompt(act, ContextCompat.getMainExecutor(act), callback).authenticate(info)
        } catch (e: Exception) {
            promptOpen = false
            error = "Impossible d'ouvrir le déverrouillage."
            ErrorLogger.log("Verrouillage", "BiometricPrompt n'a pas pu s'ouvrir", e)
        }
    }

    val locked = s == null || (s.lockEnabled && !unlocked)

    // Verrou désactivé : la session compte comme ouverte (activer l'option ne bloque pas l'écran en cours).
    // Verrou actif et fermé : demande automatique dès que l'écran verrouillé apparaît.
    LaunchedEffect(locked, s?.lockEnabled) {
        when {
            s == null -> Unit
            !s.lockEnabled -> { LockState.unlocked = true; unlocked = true }
            !unlocked -> prompt()
            else -> Unit
        }
    }

    Box(Modifier.fillMaxSize()) {
        content()
        if (locked) LockCover(showButton = s != null, error = error, onUnlock = { prompt() })
    }
}

/** Écran opaque qui masque l'appli et intercepte tous les appuis. */
@Composable
private fun LockCover(showButton: Boolean, error: String?, onUnlock: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center,
    ) {
        if (showButton) {
            Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ChampiLogo(88.dp)
                VSpace(16.dp)
                Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                VSpace(8.dp)
                Text("Champimap est verrouillée", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                if (error != null) {
                    VSpace(8.dp)
                    Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                VSpace(20.dp)
                ChampiButton("Déverrouiller", onClick = onUnlock)
            }
        }
    }
}
