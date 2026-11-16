package mg.ecoledimanche.presences.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import mg.ecoledimanche.presences.R
import mg.ecoledimanche.presences.data.repository.EtatHorloge
import mg.ecoledimanche.presences.di.ConteneurApp
import mg.ecoledimanche.presences.ui.components.EcranChargement
import mg.ecoledimanche.presences.ui.components.EcranErreur
import mg.ecoledimanche.presences.ui.donnees.DonneesViewModel
import mg.ecoledimanche.presences.ui.donnees.ouvrirLecture
import mg.ecoledimanche.presences.ui.navigation.NavigationApp
import mg.ecoledimanche.presences.ui.navigation.TYPES_SAUVEGARDE

/**
 * Démarrage : base chargée et rattrapage effectué, puis, dans l'ordre :
 * 1. si la date du téléphone est douteuse : écran d'avertissement (rien n'est clôturé ni inscrit) ;
 * 2. si l'installation est vierge (ou après « Repartir de zéro ») : écran d'accueil ;
 * 3. sinon l'application (onglet Dimanche).
 */
@Composable
fun RacineApp(conteneur: ConteneurApp) {
    val demarrage: DemarrageViewModel = viewModel(
        factory = fabrique { DemarrageViewModel { conteneur.rattraperEtEntretenir() } },
    )
    val etat by demarrage.etat.collectAsStateWithLifecycle()
    val horloge by conteneur.gardienHorloge.etat.collectAsStateWithLifecycle()
    val fluxAccueil = remember { conteneur.donneesRepository.accueilAFaire() }
    val accueil by fluxAccueil.collectAsStateWithLifecycle<Boolean?>(initialValue = null)
    val portee = rememberCoroutineScope()

    // Retour au premier plan : la date a pu changer, et les écrans affichés refont le rattrapage.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        portee.launch { runCatching { conteneur.gardienHorloge.horlogeFiable() } }
        conteneur.actualisation.signalerReprise()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val douteuse = horloge as? EtatHorloge.Douteuse
        when {
            etat == EtatDemarrage.CHARGEMENT -> EcranChargement(stringResource(R.string.demarrage_chargement))
            etat == EtatDemarrage.ERREUR -> EcranErreur(stringResource(R.string.demarrage_erreur), onReessayer = demarrage::lancer)
            douteuse != null -> HorlogeRoute(conteneur, douteuse, onReessayer = demarrage::lancer)
            accueil == null -> EcranChargement(stringResource(R.string.demarrage_chargement))
            accueil == true -> AccueilRoute(conteneur)
            else -> NavigationApp(conteneur)
        }
    }
}

@Composable
private fun HorlogeRoute(conteneur: ConteneurApp, etat: EtatHorloge.Douteuse, onReessayer: () -> Unit) {
    val contexte = LocalContext.current
    val portee = rememberCoroutineScope()
    HorlogeEcran(
        etat = etat,
        // Nouvelle vérification puis rattrapage complet (qui ne clôture rien si la date reste douteuse).
        onReessayer = onReessayer,
        onConfirmer = {
            portee.launch {
                conteneur.gardienHorloge.confirmerDateActuelle()
                onReessayer()
            }
        },
        onOuvrirReglages = {
            try {
                contexte.startActivity(Intent(Settings.ACTION_DATE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (erreur: ActivityNotFoundException) {
                contexte.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        },
    )
}

/** Installation vierge : commencer sans données, ou restaurer une sauvegarde complète. */
@Composable
private fun AccueilRoute(conteneur: ConteneurApp) {
    val vm: DonneesViewModel = viewModel(
        key = "accueil",
        factory = fabrique { DonneesViewModel(conteneur.donneesRepository, conteneur.horloge) { conteneur.rattraperEtEntretenir() } },
    )
    val etat by vm.etat.collectAsStateWithLifecycle()
    val contexte = LocalContext.current
    val choisirSauvegarde = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { source ->
        if (source != null) vm.lireRestauration(ouvrirLecture(contexte, source))
    }
    AccueilEcran(
        etat = etat,
        onCommencerVide = vm::commencerVide,
        onRestaurer = { choisirSauvegarde.launch(TYPES_SAUVEGARDE) },
        onConfirmerRestauration = vm::confirmerRestauration,
        onFermerRestauration = vm::fermerRestauration,
    )
}
