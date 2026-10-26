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
 * Écran « Sortie à plusieurs » (créateur) :
 *  1. demande l'autorisation Wi-Fi Direct ;
 *  2. donne un identifiant de groupe à la sortie, crée le réseau Wi-Fi Direct ;
 *  3. affiche le QR code d'invitation et démarre la synchro (GroupSync) ;
 *  4. liste les participants au fil des arrivées, « Continuer la sortie ».
 * La synchro continue après avoir quitté l'écran (pendant toute la sortie).
 */

/** Étapes de l'écran de création. */
private sealed class CreerEtat {
    object Autorisation : CreerEtat()
    object Preparation : CreerEtat()
    class Pret(val invite: GroupInvite) : CreerEtat()
    class Erreur(val message: String) : CreerEtat()
}

@Composable
internal fun GroupeCreerContent(nav: NavController, sortieId: String) {
    val context = LocalContext.current
    var etat by remember { mutableStateOf<CreerEtat>(CreerEtat.Autorisation) }
    var essai by remember { mutableIntStateOf(0) }
    val sync by GroupSync.state.collectAsState()

    val gate = rememberPermissionGate(wifiDirectPermissions()) { ok ->
        if (ok) {
            essai++
        } else {
            etat = CreerEtat.Erreur(
                "Sans l'autorisation « appareils à proximité », les téléphones ne peuvent pas se trouver. " +
                    "Elle sert uniquement au Wi-Fi Direct, jamais à Internet.",
            )
        }
    }

    // Première ouverture : on demande l'autorisation (ou on démarre directement si elle est déjà là).
    LaunchedEffect(Unit) {
        if (gate.granted) essai++ else gate.request()
    }

    // Création du réseau et de l'invitation (relancée par « Réessayer »).
    LaunchedEffect(essai) {
        if (essai == 0) return@LaunchedEffect
        etat = CreerEtat.Preparation
        etat = try {
            val groupId = GroupRepository.ensureGroupId(sortieId)
                ?: throw WifiDirect.WifiDirectException("Sortie introuvable.")
            val me = GroupRepository.me()
            val creds = WifiDirect.createGroup(context)
            GroupSync.start(context, groupId)
            CreerEtat.Pret(
                GroupInvite(
                    ssid = creds.networkName, pass = creds.passphrase, addr = creds.ownerAddress,
                    groupId = groupId, sortieId = sortieId, personId = me.id, name = me.name,
                ),
            )
        } catch (e: WifiDirect.WifiDirectException) {
            CreerEtat.Erreur(e.message ?: "Le Wi-Fi Direct n'a pas fonctionné.")
        } catch (e: Exception) {
            ErrorLogger.log("Groupe", "Création du groupe impossible", e)
            CreerEtat.Erreur("Le Wi-Fi Direct n'a pas fonctionné. Vérifie que le Wi-Fi est activé.")
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
                    CreerEtat.Autorisation -> {
                        BodyText("Les téléphones se connectent directement entre eux en Wi-Fi, sans Internet.")
                        ChampiButton("Autoriser", onClick = { gate.request() })
                    }
                    CreerEtat.Preparation -> {
                        CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                        BodyText("Création du réseau entre téléphones…", Modifier.fillMaxWidth(), center = true)
                    }
                    is CreerEtat.Erreur -> {
                        MessageCard(e.message, error = true)
                        ChampiButton("Réessayer", onClick = { if (gate.granted) essai++ else gate.request() })
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
                            "Sur l'autre téléphone : Démarrer une sortie → « Rejoindre ». Jusqu'à 6 personnes environ.",
                            Modifier.fillMaxWidth(), center = true,
                        )
                        QrImage(e.invite.encode(), Modifier.widthIn(max = 340.dp).align(Alignment.CenterHorizontally))
                        ParticipantsCard(sync)
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

/** Carte « Participants » : état de la synchro et prénoms connectés. */
@Composable
internal fun ParticipantsCard(sync: GroupState) {
    ChampiCard(Modifier.fillMaxWidth()) {
        SectionTitle("Participants")
        if (sync.peers.isEmpty()) {
            BodyText(
                when (sync.status) {
                    GroupState.Status.ATTENTE_RESEAU -> "Préparation du réseau…"
                    GroupState.Status.CONNEXION -> "Connexion…"
                    else -> "En attente des autres…"
                },
            )
        } else {
            sync.peers.forEach { p -> Text(champiText("• " + p.name), style = MaterialTheme.typography.bodyLarge) }
        }
        Text(champiText(sync.label()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        if (sync.photosPending > 0) BodyText("${sync.photosPending} photo(s) en cours d'envoi")
        sync.message?.let { BodyText(it) }
    }
}
