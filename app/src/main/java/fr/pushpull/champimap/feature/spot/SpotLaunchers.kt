package fr.pushpull.champimap.feature.spot

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import fr.pushpull.champimap.core.ErrorLogger
import java.io.File

/*
 * Lanceurs système du module spot : appareil photo (TakePicture + FileProvider),
 * permissions de position et de micro. Les photos sont écrites dans filesDir/photos/
 * (dossier déclaré « photos/ » dans res/xml/file_paths.xml).
 */

/** Mémoire du lanceur photo entre l'ouverture de l'appareil et le retour. */
private class CameraHolder {
    var file: File? = null
    var callback: ((File) -> Unit)? = null
}

/**
 * Renvoie une fonction `prendrePhoto { fichier -> … }` : demande la permission caméra si besoin
 * (elle est déclarée dans le manifeste, donc obligatoire pour l'appareil photo système),
 * ouvre l'appareil photo, puis appelle le rappel avec le fichier JPEG s'il a bien été écrit.
 * `onError` reçoit un message à afficher à l'utilisateur.
 */
@Composable
fun rememberCamera(onError: (String) -> Unit): ((File) -> Unit) -> Unit {
    val context = LocalContext.current
    val holder = remember { CameraHolder() }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = holder.file
        val cb = holder.callback
        holder.file = null
        holder.callback = null
        if (ok && f != null && f.exists() && f.length() > 0) cb?.invoke(f) else f?.delete()
    }

    /** Ouvre l'appareil photo sur un nouveau fichier de filesDir/photos/. */
    fun launchCamera() {
        try {
            val f = SpotRepository.newPhotoFile(context)
            holder.file = f
            val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
            takePicture.launch(uri)
        } catch (e: ActivityNotFoundException) {
            onError("Aucune appli d'appareil photo trouvée.")
        } catch (e: Exception) {
            ErrorLogger.log("Spot", "Ouverture de l'appareil photo impossible", e)
            onError("Impossible d'ouvrir l'appareil photo.")
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) launchCamera() else {
            holder.callback = null
            onError("Autorise l'appareil photo pour prendre une photo.")
        }
    }

    return { cb ->
        holder.callback = cb
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            launchCamera()
        } else {
            permission.launch(Manifest.permission.CAMERA)
        }
    }
}

/** Renvoie une fonction qui demande la position précise ; `onResult(true)` si accordée. */
@Composable
fun rememberLocationPermission(onResult: (Boolean) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        onResult(res[Manifest.permission.ACCESS_FINE_LOCATION] == true)
    }
    return {
        launcher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}

/** Renvoie une fonction qui demande le micro ; `onResult(true)` si accordé. */
@Composable
fun rememberMicPermission(onResult: (Boolean) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onResult(it) }
    return { launcher.launch(Manifest.permission.RECORD_AUDIO) }
}
