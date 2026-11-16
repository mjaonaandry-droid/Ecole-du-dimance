package mg.ecoledimanche.presences.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EventBusy
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import mg.ecoledimanche.presences.R
import mg.ecoledimanche.presences.data.repository.EtatHorloge
import mg.ecoledimanche.presences.domain.VerdictHorloge
import mg.ecoledimanche.presences.ui.donnees.DialoguesRestauration
import mg.ecoledimanche.presences.ui.donnees.EtatDonnees

/**
 * Premier lancement (ou après « Repartir de zéro ») : l'application démarre avec des données vierges,
 * ou à partir d'une sauvegarde complète (changement de téléphone, réinstallation).
 */
@Composable
fun AccueilEcran(
    etat: EtatDonnees,
    onCommencerVide: () -> Unit,
    onRestaurer: () -> Unit,
    onConfirmerRestauration: () -> Unit,
    onFermerRestauration: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Icon(Icons.Filled.School, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(64.dp))
        Text(
            text = stringResource(R.string.accueil_titre),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(text = stringResource(R.string.accueil_explication), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Button(
            onClick = onCommencerVide,
            enabled = !etat.occupe,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Text(stringResource(R.string.accueil_commencer_vide), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        OutlinedButton(
            onClick = onRestaurer,
            enabled = !etat.occupe,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Icon(Icons.Filled.Restore, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.accueil_restaurer), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = stringResource(R.string.accueil_restaurer_aide),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
    DialoguesRestauration(etat.restauration, onConfirmerRestauration, onFermerRestauration)
}

/**
 * La date du téléphone semble fausse : l'application ne clôture rien et n'inscrit personne tant que ce
 * n'est pas réglé (une date fausse marquerait des enfants absents à tort, de façon durable).
 */
@Composable
fun HorlogeEcran(
    etat: EtatHorloge.Douteuse,
    onReessayer: () -> Unit,
    onConfirmer: () -> Unit,
    onOuvrirReglages: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        Icon(Icons.Filled.EventBusy, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(64.dp))
        Text(
            text = stringResource(R.string.horloge_titre),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.horloge_date_telephone, dateHeure(etat.maintenant)),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = when (etat.verdict) {
                        VerdictHorloge.IMPOSSIBLE -> stringResource(R.string.horloge_impossible)
                        VerdictHorloge.RECUL -> stringResource(R.string.horloge_recul, dateHeure(etat.reference))
                        VerdictHorloge.SAUT -> stringResource(R.string.horloge_saut, dateHeure(etat.reference))
                        VerdictHorloge.FIABLE -> ""
                    },
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
        Text(stringResource(R.string.horloge_explication), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Button(onClick = onOuvrirReglages, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Icon(Icons.Filled.Settings, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.horloge_reglages))
        }
        OutlinedButton(onClick = onReessayer, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.horloge_reessayer))
        }
        if (etat.confirmable) {
            TextButton(onClick = onConfirmer, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(stringResource(R.string.horloge_confirmer), textAlign = TextAlign.Center)
            }
        }
    }
}

private val FORMAT_DATE_HEURE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM uuuu 'à' HH'h'mm", Locale.FRENCH)

private fun dateHeure(instant: Instant): String = FORMAT_DATE_HEURE.format(instant.atZone(ZoneId.systemDefault()))
