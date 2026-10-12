package mg.ecoledimanche.presences.camera

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mg.ecoledimanche.presences.R
import mg.ecoledimanche.presences.ui.components.EcranChargement

/**
 * Parcours photo : caméra (permission, capture) OU galerie, puis prévisualisation (« Reprendre » /
 * « Utiliser cette photo »). Rien n'est enregistré en base ici : [onValidee] reçoit seulement le
 * chemin de la photo TEMPORAIRE validée. Annuler ne crée aucune fiche.
 *
 * [depuisGalerie] : le parcours commence par le sélecteur de photos du système (aucune permission
 * demandée) au lieu de la caméra. Depuis la caméra, un bouton permet aussi d'aller à la galerie.
 */
@Composable
fun ParcoursPhoto(
    stockage: StockagePhotosPrive,
    onValidee: (cheminTemporaire: String) -> Unit,
    onAnnuler: () -> Unit,
    modifier: Modifier = Modifier,
    enCours: Boolean = false,
    messageInitial: String? = null,
    depuisGalerie: Boolean = false,
) {
    var capturee by rememberSaveable { mutableStateOf<String?>(null) }
    var traitement by remember { mutableStateOf(false) }
    var erreur by rememberSaveable { mutableStateOf<ErreurCamera?>(null) }
    var erreurGalerie by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf(messageInitial) }
    /** Vrai : l'écran d'attente de la galerie remplace la caméra (aucune permission caméra demandée). */
    var modeGalerie by rememberSaveable { mutableStateOf(depuisGalerie) }
    var galerieDejaOuverte by rememberSaveable { mutableStateOf(false) }
    var photoDejaChoisie by rememberSaveable { mutableStateOf(false) }
    val portee = rememberCoroutineScope()
    val resolveur = LocalContext.current.applicationContext.contentResolver

    fun importer(source: Uri) {
        traitement = true
        portee.launch {
            try {
                capturee = stockage.importerDepuisGalerie(resolveur, source)
                photoDejaChoisie = true
            } catch (annulation: CancellationException) {
                throw annulation
            } catch (echec: Exception) {
                erreurGalerie = true
            } finally {
                traitement = false
            }
        }
    }

    // Sélecteur de photos du système : sur Android 13+ sans aucune permission ; avant, le sélecteur de
    // documents. L'application ne reçoit l'accès qu'à la photo choisie.
    val galerie = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { source ->
        when {
            source != null -> importer(source)
            // Ouvert directement en mode galerie et fermé sans choisir : retour à l'écran précédent.
            depuisGalerie && !photoDejaChoisie && capturee == null && !traitement -> onAnnuler()
        }
    }

    fun ouvrirGalerie() {
        modeGalerie = true
        galerieDejaOuverte = true
        galerie.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    LaunchedEffect(Unit) {
        if (depuisGalerie && !galerieDejaOuverte && capturee == null) ouvrirGalerie()
    }

    // Une photo restaurée après recréation du processus peut avoir disparu : on revient au choix.
    LaunchedEffect(capturee) {
        val chemin = capturee ?: return@LaunchedEffect
        if (!stockage.existe(chemin)) capturee = null
    }

    fun reprendre() {
        val chemin = capturee
        capturee = null
        if (chemin != null) portee.launch { withContext(NonCancellable) { stockage.supprimer(chemin) } }
        if (modeGalerie) ouvrirGalerie()
    }

    BackHandler(enabled = capturee != null && !enCours) {
        // Retour depuis l'aperçu : on jette la photo et on revient au choix (sans rouvrir la galerie).
        val chemin = capturee
        capturee = null
        if (chemin != null) portee.launch { withContext(NonCancellable) { stockage.supprimer(chemin) } }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val chemin = capturee
        when {
            chemin != null -> ApercuPhoto(
                chemin = chemin,
                enCours = enCours,
                onReprendre = ::reprendre,
                onUtiliser = { onValidee(chemin) },
                libelleReprendre = stringResource(if (modeGalerie) R.string.apercu_autre_photo else R.string.apercu_reprendre),
            )
            traitement -> EcranChargement(stringResource(R.string.camera_traitement))
            modeGalerie -> AttenteGalerie(
                onOuvrirGalerie = ::ouvrirGalerie,
                onPrendrePhoto = { modeGalerie = false },
                onAnnuler = onAnnuler,
            )
            erreur == ErreurCamera.INDISPONIBLE -> CameraIndisponible(onRetour = onAnnuler, onGalerie = ::ouvrirGalerie)
            else -> PorteCamera(onAnnuler = onAnnuler, onGalerie = ::ouvrirGalerie) {
                CameraCapture(
                    stockage = stockage,
                    onCapture = { brut ->
                        traitement = true
                        portee.launch {
                            try {
                                capturee = stockage.traiterPhotoCapturee(brut)
                            } catch (annulation: CancellationException) {
                                throw annulation
                            } catch (echec: Exception) {
                                erreur = ErreurCamera.CAPTURE
                            } finally {
                                traitement = false
                            }
                        }
                    },
                    onErreur = { erreur = it },
                    onFermer = onAnnuler,
                    onGalerie = ::ouvrirGalerie,
                )
            }
        }
    }

    if (erreur == ErreurCamera.CAPTURE) {
        AlertDialog(
            onDismissRequest = { erreur = null },
            text = { Text(stringResource(R.string.camera_erreur_capture)) },
            confirmButton = { TextButton(onClick = { erreur = null }) { Text(stringResource(R.string.action_ok)) } },
        )
    } else if (erreurGalerie) {
        AlertDialog(
            onDismissRequest = { erreurGalerie = false },
            text = { Text(stringResource(R.string.galerie_erreur)) },
            confirmButton = { TextButton(onClick = { erreurGalerie = false }) { Text(stringResource(R.string.action_ok)) } },
        )
    } else if (message != null && capturee == null) {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(message.orEmpty()) },
            confirmButton = { TextButton(onClick = { message = null }) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}
