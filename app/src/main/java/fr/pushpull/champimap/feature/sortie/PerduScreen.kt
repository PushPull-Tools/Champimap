package fr.pushpull.champimap.feature.sortie

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import fr.pushpull.champimap.core.AppGraph
import fr.pushpull.champimap.core.ErrorLogger
import fr.pushpull.champimap.core.db.DepartType
import fr.pushpull.champimap.core.ui.ChampiButton
import fr.pushpull.champimap.core.ui.ChampiCard
import fr.pushpull.champimap.core.ui.ChampiScreen
import fr.pushpull.champimap.core.ui.theme.SafetyTheme

/*
 * « Je suis perdu » (écran de sécurité, jamais en thème party) :
 * coordonnées en grand (à dicter aux secours), flèche + distance vers la voiture,
 * bouton 112 qui OUVRE le clavier d'appel (n'appelle pas directement).
 */
@Composable
fun PerduScreen(nav: NavController, sortieId: String) {
    SafetyTheme {
        val context = LocalContext.current
        val sortie by remember(sortieId) { AppGraph.db.sortieDao().flow(sortieId) }.collectAsState(initial = null)
        val fix by rememberLiveFix(1000)
        val heading by rememberHeading(active = true, lat = fix?.lat, lon = fix?.lon)
        val now by rememberNow(30_000)
        LaunchedEffect(Unit) { SortieTracker.wakeGps() }

        val sLat = sortie?.startLat
        val sLon = sortie?.startLon
        val f = fix

        ChampiScreen(title = "Je suis perdu", onBack = { nav.popBackStack() }) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Reste calme. Si tu ne retrouves pas ton chemin, appelle le 112 et lis-leur ces coordonnées.",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )

                // Coordonnées en grand, sélectionnables.
                ChampiCard(Modifier.fillMaxWidth()) {
                    Text("Ta position", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (f == null) {
                        Text("Recherche du GPS…", style = MaterialTheme.typography.headlineSmall)
                    } else {
                        SelectionContainer {
                            Column {
                                Text("Latitude ${Geo.formatDms(f.lat, true)}", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                Text("Longitude ${Geo.formatDms(f.lon, false)}", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    Geo.formatCoords(f.lat, f.lon) + " · précision ±${f.accuracyM.toInt()} m",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                }

                // Flèche vers la voiture / le départ.
                ChampiCard(Modifier.fillMaxWidth()) {
                    val toCar = sortie?.departType != DepartType.FOOT
                    if (f != null && sLat != null && sLon != null) {
                        val bearing = Geo.bearingDeg(f.lat, f.lon, sLat, sLon)
                        val rotation = (bearing - (heading ?: 0f).toDouble()).toFloat()
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                            DirectionArrow(rotation, size = 120.dp)
                            Text(
                                (if (toCar) "Voiture à " else "Départ à ") + Geo.formatDistance(Geo.distanceM(f.lat, f.lon, sLat, sLon)),
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            // Coordonnées de la voiture / du départ, à dicter aussi au besoin.
                            SelectionContainer {
                                Text(
                                    (if (toCar) "Voiture : " else "Départ : ") + Geo.formatCoords(sLat, sLon),
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            if (heading == null) {
                                Text("Pas de boussole : la flèche indique la direction par rapport au nord.", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    } else {
                        Text(if (toCar) "Position de la voiture inconnue." else "Point de départ inconnu.", style = MaterialTheme.typography.bodyLarge)
                    }
                }

                if (f != null) {
                    Text(nightText(minutesBeforeForestDark(f.lat, f.lon, now)), style = MaterialTheme.typography.titleMedium)
                }
            }
            Column(Modifier.padding(16.dp)) {
                ChampiButton(
                    "Appeler le 112",
                    // Rouge franc + texte blanc dans tous les thèmes (le rouge du thème sombre est trop pâle).
                    color = androidx.compose.ui.graphics.Color(0xFFC62828),
                    contentColor = androidx.compose.ui.graphics.Color.White,
                    onClick = {
                        // Ouvre le clavier avec le numéro : c'est la personne qui lance l'appel.
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }.onFailure { ErrorLogger.log("Je suis perdu", "Clavier d'appel introuvable", it) }
                    },
                )
            }
        }
    }
}
