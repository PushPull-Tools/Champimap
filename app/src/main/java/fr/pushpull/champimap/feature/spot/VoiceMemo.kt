package fr.pushpull.champimap.feature.spot

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.Manifest
import android.content.pm.PackageManager
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.Spot
import fr.pushpull.champimap.core.newId
import fr.pushpull.champimap.core.ui.ConfirmDialog
import fr.pushpull.champimap.feature.bonus.champiText
import kotlinx.coroutines.delay
import java.io.File

/*
 * Mémo vocal d'un spot : enregistrement au micro (MediaRecorder, AAC dans un .m4a),
 * fichier local dans filesDir/memos/, lecture (MediaPlayer), suppression avec confirmation.
 * Un seul mémo par spot : un nouvel enregistrement remplace l'ancien.
 */

/** Durée maximale d'un mémo (5 minutes). */
private const val MAX_MEMO_MS = 5 * 60 * 1000

/** Nouveau fichier de mémo dans filesDir/memos/. */
private fun newMemoFile(context: Context): File =
    File(File(context.filesDir, "memos").apply { mkdirs() }, "${newId()}.m4a")

/** Crée un MediaRecorder (constructeur avec contexte à partir d'Android 12). */
@Suppress("DEPRECATION")
private fun createRecorder(context: Context): MediaRecorder =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()

/** Boutons Enregistrer / Arrêter / Écouter / Supprimer du mémo vocal. */
@Composable
fun VoiceMemo(spot: Spot, saver: AutoSaver, onInfo: (String) -> Unit) {
    val context = LocalContext.current
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingFile by remember { mutableStateOf<File?>(null) }
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var seconds by remember { mutableIntStateOf(0) }
    var askDelete by remember { mutableStateOf(false) }
    val memo = spot.voiceMemoPath?.let { File(it) }?.takeIf { it.exists() }

    /** Arrête la lecture en cours. */
    fun stopPlaying() {
        player?.runCatching { stop(); release() }
        player = null
    }

    /** Termine l'enregistrement ; si `keep`, le fichier devient le mémo du spot (l'ancien est effacé). */
    fun stopRecording(keep: Boolean) {
        val r = recorder ?: return
        val f = recordingFile
        val ok = runCatching { r.stop() }.isSuccess // échoue si l'enregistrement est trop court
        r.release()
        recorder = null
        recordingFile = null
        if (keep && ok && f != null && f.length() > 0) {
            saver.now {
                // Lecture fraîche : cette fonction peut être appelée depuis un ancien rappel.
                val old = AppGraph.db.spotDao().get(spot.id)?.voiceMemoPath
                SpotRepository.updateSpot(spot.id) { it.copy(voiceMemoPath = f.absolutePath) }
                if (old != null && old != f.absolutePath) File(old).delete()
            }
        } else {
            f?.delete()
            if (keep) onInfo("Mémo trop court, rien n'a été enregistré.")
        }
    }

    /** Démarre l'enregistrement dans un nouveau fichier. */
    fun startRecording() {
        stopPlaying()
        val f = newMemoFile(context)
        val r = createRecorder(context)
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioEncodingBitRate(64_000)
            r.setAudioSamplingRate(44_100)
            r.setMaxDuration(MAX_MEMO_MS)
            r.setOutputFile(f.absolutePath)
            // Durée maximale atteinte : on garde l'enregistrement.
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopRecording(true)
            }
            r.prepare()
            r.start()
            recorder = r
            recordingFile = f
            seconds = 0
        } catch (e: Exception) {
            r.release()
            f.delete()
            ErrorLogger.log("Spot", "Enregistrement du mémo vocal impossible", e)
            onInfo("Impossible d'utiliser le micro.")
        }
    }

    /** Lit le mémo (ou l'arrête s'il est en cours de lecture). */
    fun togglePlay(file: File) {
        if (player != null) { stopPlaying(); return }
        try {
            val p = MediaPlayer()
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener { stopPlaying() }
            p.prepare()
            p.start()
            player = p
        } catch (e: Exception) {
            ErrorLogger.log("Spot", "Lecture du mémo vocal impossible", e)
            onInfo("Impossible de lire le mémo.")
        }
    }

    val askMic = rememberMicPermission { granted ->
        if (granted) startRecording() else onInfo("Autorise le micro pour enregistrer un mémo.")
    }

    // Compteur de secondes pendant l'enregistrement.
    LaunchedEffect(recorder) {
        while (recorder != null) { delay(1_000); seconds++ }
    }
    // En quittant l'écran : on garde ce qui a été dit et on libère le micro / le haut-parleur.
    DisposableEffect(Unit) {
        onDispose {
            stopRecording(true)
            stopPlaying()
        }
    }

    // Boutons icône + texte court sur une seule ligne (« Réenregistrer » se coupait en deux).
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (recorder != null) {
            MemoButton(
                "Arrêter ${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}",
                Icons.Filled.Stop,
                onClick = { stopRecording(true) },
                modifier = Modifier.weight(1f),
            )
        } else {
            MemoButton(
                if (memo == null) "Enregistrer" else "Refaire",
                Icons.Filled.Mic,
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                    if (granted) startRecording() else askMic()
                },
                modifier = Modifier.weight(1f),
            )
            if (memo != null) {
                MemoButton(
                    if (player != null) "Stop" else "Écouter",
                    if (player != null) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                    onClick = { togglePlay(memo) },
                    modifier = Modifier.weight(1f),
                )
                MemoButton("Effacer", Icons.Filled.Delete, onClick = { askDelete = true }, modifier = Modifier.weight(1f))
            }
        }
    }
    if (memo == null && recorder == null) {
        Text(champiText("Aucun mémo pour l'instant."), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (askDelete && memo != null) {
        ConfirmDialog(
            title = "Supprimer le mémo vocal ?",
            onConfirm = {
                askDelete = false
                stopPlaying()
                saver.now {
                    SpotRepository.updateSpot(spot.id) { it.copy(voiceMemoPath = null) }
                    memo.delete()
                }
            },
            onDismiss = { askDelete = false },
        )
    }
}

/** Bouton du mémo : icône au-dessus d'un libellé court, jamais coupé (une seule ligne). */
@Composable
private fun MemoButton(text: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 64.dp),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary),
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                champiText(text),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
