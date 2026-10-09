package mg.ecoledimanche.presences.ui.exporter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mg.ecoledimanche.presences.R

/** Onglet « Exporter » : fichier CSV des enfants (informations de base et présences). */
@Composable
fun ExporterEcran(
    etat: EtatEcranExport,
    onInclureArchives: (Boolean) -> Unit,
    onExporter: () -> Unit,
    onAcquitter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.export_titre),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(text = stringResource(R.string.export_explication), style = MaterialTheme.typography.bodyLarge)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.export_contenu_titre),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(text = stringResource(R.string.export_contenu_infos), style = MaterialTheme.typography.bodyMedium)
                Text(text = stringResource(R.string.export_contenu_presences), style = MaterialTheme.typography.bodyMedium)
                Text(text = stringResource(R.string.export_contenu_exclus), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(
                text = stringResource(R.string.export_inclure_archives),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = etat.inclureArchives, onCheckedChange = onInclureArchives, enabled = etat.action != ActionExport.EnCours)
        }

        ApercuLigne(etat.apercu)

        val pret = etat.apercu as? ApercuExport.Pret
        Button(
            onClick = onExporter,
            enabled = pret != null && pret.enfants > 0 && etat.action != ActionExport.EnCours,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            if (etat.action == ActionExport.EnCours) {
                CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            } else {
                Icon(Icons.Filled.FileDownload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.export_bouton), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }

        ResultatExport(etat.action, onAcquitter)
    }
}

@Composable
private fun ApercuLigne(apercu: ApercuExport) {
    when (apercu) {
        ApercuExport.Chargement -> Text(stringResource(R.string.chargement), style = MaterialTheme.typography.bodyMedium)
        ApercuExport.Erreur -> Text(
            text = stringResource(R.string.export_erreur_apercu),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        is ApercuExport.Pret -> Text(
            text = if (apercu.enfants == 0) {
                stringResource(R.string.export_aucun_enfant)
            } else {
                stringResource(R.string.export_apercu, libelleEnfants(apercu.enfants), libelleDimanches(apercu.dimanches))
            },
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

@Composable
private fun ResultatExport(action: ActionExport, onAcquitter: () -> Unit) {
    when (action) {
        ActionExport.Repos, ActionExport.EnCours -> Unit
        is ActionExport.Reussie -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp)) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = stringResource(R.string.export_reussi, libelleEnfants(action.enfants), libelleDimanches(action.dimanches)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                TextButton(onClick = onAcquitter) { Text(stringResource(R.string.action_ok)) }
            }
        }
        ActionExport.Echec -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.export_echec),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onAcquitter) { Text(stringResource(R.string.action_ok)) }
        }
    }
}

/** « 1 enfant », « 12 enfants » (le pluriel français commence à 2). */
@Composable
private fun libelleEnfants(nombre: Int): String =
    if (nombre <= 1) stringResource(R.string.export_enfant, nombre) else stringResource(R.string.export_enfants, nombre)

@Composable
private fun libelleDimanches(nombre: Int): String =
    if (nombre <= 1) stringResource(R.string.export_dimanche, nombre) else stringResource(R.string.export_dimanches, nombre)
