package fr.pushpull.champimap.feature.groupe

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiOutlinedButton
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.SectionTitle
import fr.pushpull.champimap.core.ui.Stack
import fr.pushpull.champimap.feature.bonus.champiText
import fr.pushpull.champimap.nav.Routes

/*
 * Écran « Sortie à plusieurs » (créateur = hôte Bluetooth) :
 *  1. vérifie Android 10+, demande les autorisations Bluetooth puis l'activation du Bluetooth ;
 *  2. donne un identifiant de groupe à la sortie, devient l'hôte et démarre la synchro
 *     (GroupSync : annonce BLE + canal L2CAP) ;
 *  3. affiche le QR code d'invitation et les participants au fil des arrivées.
 * La synchro continue après avoir quitté l'écran (pendant toute la sortie).
 */

/** Étapes de l'écran de création. */
private sealed class CreerEtat {
    object Autorisation : CreerEtat()
    object Preparation : CreerEtat()
    class Pret(val invite: GroupInvite) : CreerEtat()
    class Erreur(val message: String) : CreerEtat()
    object Incompatible : CreerEtat()
}

/** Texte commun : portée et fonctionnement. */
internal const val PORTEE_TEXTE =
    "Les téléphones échangent en Bluetooth, sans Internet, jusqu'à quelques dizaines de mètres. " +
        "Hors de portée, chacun continue seul : tout se synchronise au regroupement ou à la voiture."

@Composable
internal fun GroupeCreerContent(nav: NavController, sortieId: String) {
    val context = LocalContext.current
    var etat by remember {
        mutableStateOf<CreerEtat>(if (BleLink.isSupported()) CreerEtat.Autorisation else CreerEtat.Incompatible)
    }
    var essai by remember { mutableIntStateOf(0) }
    val sync by GroupSync.state.collectAsState()

    // Étape 2 : Bluetooth à activer (fenêtre système).
    val activerBluetooth = rememberBluetoothEnabler { ok ->
        if (ok) {
            essai++
        } else {
            etat = CreerEtat.Erreur("Le Bluetooth doit être activé pour la sortie à plusieurs (pas besoin d'Internet).")
        }
    }

    // Étape 1 : autorisations Bluetooth.
    val gate = rememberPermissionGate(bluetoothPermissions()) { ok ->
        if (ok) {
            activerBluetooth()
        } else {
            etat = CreerEtat.Erreur(
                "Sans l'autorisation « appareils à proximité », les téléphones ne peuvent pas se trouver. " +
                    "Elle sert uniquement au Bluetooth, jamais à Internet.",
            )
        }
    }

    // Première ouverture : on lance les étapes (rien sur Android < 10).
    LaunchedEffect(Unit) {
        if (BleLink.isSupported()) gate.request()
    }

    // Étape 3 : préparation du groupe et de l'invitation (relancée par « Réessayer »).
    LaunchedEffect(essai) {
        if (essai == 0) return@LaunchedEffect
        etat = CreerEtat.Preparation
        etat = try {
            val groupId = GroupRepository.ensureGroupId(sortieId)
            if (groupId == null) {
                CreerEtat.Erreur("Sortie introuvable.")
            } else {
                val me = GroupRepository.me()
                GroupSync.markHost(context, groupId)
                GroupSync.start(context, groupId)
                CreerEtat.Pret(
                    GroupInvite(
                        groupId = groupId, sortieId = sortieId, personId = me.id, name = me.name,
                        hash = BleLink.hashHex(groupId),
                    ),
                )
            }
        } catch (e: Exception) {
            ErrorLogger.log("Groupe", "Création du groupe impossible", e)
            CreerEtat.Erreur("La sortie à plusieurs n'a pas pu démarrer.")
        }
    }

    val continuer = {
        nav.navigate(Routes.sortie(sortieId)) {
            popUpTo(Routes.GROUPE_CREER) { inclusive = true }
            launchSingleTop = true
        }
    }

    ChampiScreen(title = "Sortie à plusieurs", onBack = { nav.popBackStack() }) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Stack(Modifier.fillMaxWidth()) {
                when (val e = etat) {
                    CreerEtat.Incompatible -> {
                        MessageCard("Sortie à plusieurs : Android 10 minimum, sur chaque téléphone. Tu peux faire la sortie seul et partager tes spots ensuite (QR code ou fichier).", error = true)
                        ChampiButton("Continuer seul", onClick = continuer)
                    }
                    CreerEtat.Autorisation -> {
                        BodyText(PORTEE_TEXTE)
                        ChampiButton("Autoriser le Bluetooth", onClick = { gate.request() })
                    }
                    CreerEtat.Preparation -> {
                        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                        BodyText("Préparation…", Modifier.fillMaxWidth(), center = true)
                    }
                    is CreerEtat.Erreur -> {
                        MessageCard(e.message, error = true)
                        ChampiButton("Réessayer", onClick = { gate.request() })
                        ChampiOutlinedButton("Continuer seul", onClick = continuer)
                    }
                    is CreerEtat.Pret -> {
                        Text(
                            champiText("Fais scanner ce QR code"),
                            style = MaterialTheme.typography.titleMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        BodyText(
                            "Sur l'autre téléphone : Démarrer une sortie → « Rejoindre ». Jusqu'à 6 personnes. " +
                                "Garde Champimap ouvert ou en sortie en cours pour relayer les autres.",
                            Modifier.fillMaxWidth(), center = true,
                        )
                        QrImage(e.invite.encode(), Modifier.widthIn(max = 340.dp).align(Alignment.CenterHorizontally))
                        ParticipantsCard(sync, onEnableBluetooth = activerBluetooth)
                        ChampiButton("Continuer la sortie", onClick = continuer)
                        ChampiOutlinedButton("Arrêter le partage", onClick = {
                            GroupSync.leave(context)
                            continuer()
                        })
                    }
                }
            }
        }
    }
}

/** Carte « Participants » : état de la synchro, prénoms connectés, Bluetooth à activer. */
@Composable
internal fun ParticipantsCard(sync: GroupState, onEnableBluetooth: () -> Unit) {
    ChampiCard(Modifier.fillMaxWidth()) {
        SectionTitle("Participants")
        if (sync.peers.isEmpty()) {
            BodyText(
                when (sync.status) {
                    GroupState.Status.RECHERCHE, GroupState.Status.CONNEXION -> "Recherche des autres téléphones…"
                    GroupState.Status.HORS_PORTEE -> "Personne à portée pour l'instant."
                    else -> "En attente des autres…"
                },
            )
        } else {
            sync.peers.forEach { p -> Text(champiText("• " + p.name), style = MaterialTheme.typography.bodyLarge) }
        }
        Text(champiText(sync.label()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (sync.photosPending > 0) BodyText("${sync.photosPending} photo(s) en cours d'envoi, en tâche de fond")
        sync.message?.let { BodyText(it) }
        if (sync.bluetoothOff) ChampiButton("Activer le Bluetooth", onClick = onEnableBluetooth, modifier = Modifier.padding(top = 8.dp))
    }
}
