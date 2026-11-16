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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mg.ecoledimanche.presences.R
import mg.ecoledimanche.presences.domain.GroupeExport

/** Export CSV des enfants, complet ou partiel : l'utilisateur choisit les groupes de colonnes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExporterEcran(
    etat: EtatEcranExport,
    onRetour: () -> Unit,
    onInclureArchives: (Boolean) -> Unit,
    onToutExporter: (Boolean) -> Unit,
    onGroupe: (GroupeExport, Boolean) -> Unit,
    onExporter: () -> Unit,
    onAcquitter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.export_titre)) },
                navigationIcon = {
                    IconButton(onClick = onRetour) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_retour))
                    }
                },
            )
        },
    ) { marges ->
        ContenuExport(etat, onInclureArchives, onToutExporter, onGroupe, onExporter, onAcquitter, Modifier.padding(marges))
    }
}

@Composable
private fun ContenuExport(
    etat: EtatEcranExport,
    onInclureArchives: (Boolean) -> Unit,
    onToutExporter: (Boolean) -> Unit,
    onGroupe: (GroupeExport, Boolean) -> Unit,
    onExporter: () -> Unit,
    onAcquitter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val actif = etat.action != ActionExport.EnCours
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text(text = stringResource(R.string.export_explication), style = MaterialTheme.typography.bodyLarge)

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = stringResource(R.string.export_contenu_titre),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Case(stringResource(R.string.export_tout), etat.toutExporter, actif, gras = true) { onToutExporter(it) }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp)) {
                    Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    Text(
                        text = stringResource(R.string.export_groupe_identite),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                for (groupe in GroupeExport.entries) {
                    Case(stringResource(libelleGroupe(groupe)), groupe in etat.groupes, actif) { onGroupe(groupe, it) }
                }
            }
        }
        if (etat.contientInfosPersonnelles) {
            Text(
                text = stringResource(R.string.export_avertissement_personnel),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(
                text = stringResource(R.string.export_inclure_archives),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Switch(checked = etat.inclureArchives, onCheckedChange = onInclureArchives, enabled = actif)
        }

        ApercuLigne(etat.apercu, GroupeExport.PRESENCES in etat.groupes)

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

        Text(
            text = stringResource(R.string.export_astuce_import),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ApercuLigne(apercu: ApercuExport, avecDimanches: Boolean) {
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
            } else if (!avecDimanches) {
                libelleEnfants(apercu.enfants)
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

/** Ligne à cocher entièrement cliquable (cible tactile de 48 dp). */
@Composable
private fun Case(libelle: String, coche: Boolean, actif: Boolean, gras: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = coche, enabled = actif, role = Role.Checkbox, onValueChange = onChange)
            .padding(horizontal = 4.dp),
    ) {
        Checkbox(checked = coche, onCheckedChange = null, enabled = actif, modifier = Modifier.padding(horizontal = 8.dp))
        Text(
            text = libelle,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (gras) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(start = 8.dp, end = 12.dp),
        )
    }
}

private fun libelleGroupe(groupe: GroupeExport): Int = when (groupe) {
    GroupeExport.IDENTIFIANT -> R.string.export_groupe_identifiant
    GroupeExport.AGE_STATUT -> R.string.export_groupe_age_statut
    GroupeExport.ADRESSE -> R.string.export_groupe_adresse
    GroupeExport.PARENTS -> R.string.export_groupe_parents
    GroupeExport.FRATRIE -> R.string.export_groupe_fratrie
    GroupeExport.EGLISE -> R.string.export_groupe_eglise
    GroupeExport.SUIVI -> R.string.export_groupe_suivi
    GroupeExport.PRESENCES -> R.string.export_groupe_presences
    GroupeExport.TOTAUX -> R.string.export_groupe_totaux
}

/** « 1 enfant », « 12 enfants » (le pluriel français commence à 2). */
@Composable
private fun libelleEnfants(nombre: Int): String =
    if (nombre <= 1) stringResource(R.string.export_enfant, nombre) else stringResource(R.string.export_enfants, nombre)

@Composable
private fun libelleDimanches(nombre: Int): String =
    if (nombre <= 1) stringResource(R.string.export_dimanche, nombre) else stringResource(R.string.export_dimanches, nombre)
