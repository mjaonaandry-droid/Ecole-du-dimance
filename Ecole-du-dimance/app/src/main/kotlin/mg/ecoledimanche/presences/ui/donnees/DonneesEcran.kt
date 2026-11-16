package mg.ecoledimanche.presences.ui.donnees

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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import mg.ecoledimanche.presences.R
import mg.ecoledimanche.presences.data.repository.ResumeDonnees
import mg.ecoledimanche.presences.data.sauvegarde.ManifesteSauvegarde
import mg.ecoledimanche.presences.data.sauvegarde.ProblemeSauvegarde
import mg.ecoledimanche.presences.domain.FormatsFr

class ActionsDonnees(
    val onExporterCsv: () -> Unit,
    val onImporterCsv: () -> Unit,
    val onSauvegarder: () -> Unit,
    val onAcquitterSauvegarde: () -> Unit,
    val onChoisirRestauration: () -> Unit,
    val onConfirmerRestauration: () -> Unit,
    val onFermerRestauration: () -> Unit,
    val onDemanderRemiseAZero: () -> Unit,
    val onConfirmerRemiseAZero: () -> Unit,
    val onAnnulerRemiseAZero: () -> Unit,
)

/**
 * Onglet « Données » : fichiers CSV (tableur), sauvegarde complète (base + photos), restauration,
 * et « Repartir de zéro ».
 */
@Composable
fun DonneesEcran(etat: EtatDonnees, actions: ActionsDonnees, modifier: Modifier = Modifier) {
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        Text(
            text = stringResource(R.string.donnees_titre),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        Section(stringResource(R.string.donnees_csv_titre), stringResource(R.string.donnees_csv_explication)) {
            BoutonAction(Icons.Filled.FileDownload, stringResource(R.string.donnees_exporter_csv), !etat.occupe, actions.onExporterCsv)
            BoutonAction(Icons.Filled.UploadFile, stringResource(R.string.donnees_importer_csv), !etat.occupe, actions.onImporterCsv, secondaire = true)
        }

        Section(stringResource(R.string.donnees_sauvegarde_titre), stringResource(R.string.donnees_sauvegarde_explication)) {
            Button(
                onClick = actions.onSauvegarder,
                enabled = !etat.occupe,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) {
                if (etat.sauvegarde == ActionSauvegarde.EnCours) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Icon(Icons.Filled.Backup, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.donnees_sauvegarder), fontWeight = FontWeight.Bold)
                }
            }
            ResultatSauvegarde(etat.sauvegarde, actions.onAcquitterSauvegarde)
            BoutonAction(Icons.Filled.Restore, stringResource(R.string.donnees_restaurer), !etat.occupe, actions.onChoisirRestauration, secondaire = true)
            Text(
                text = stringResource(R.string.donnees_sauvegarde_conseil),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Section(stringResource(R.string.donnees_zero_titre), stringResource(R.string.donnees_zero_explication)) {
            OutlinedButton(
                onClick = actions.onDemanderRemiseAZero,
                enabled = !etat.occupe,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.DeleteForever, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.donnees_zero_bouton))
            }
        }
    }

    DialoguesRestauration(etat.restauration, actions.onConfirmerRestauration, actions.onFermerRestauration)
    DialogueRemiseAZero(etat, actions)
}

@Composable
private fun Section(titre: String, explication: String, contenu: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(16.dp)) {
            Text(titre, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(explication, style = MaterialTheme.typography.bodyMedium)
            contenu()
        }
    }
}

@Composable
private fun BoutonAction(icone: ImageVector, libelle: String, actif: Boolean, onClick: () -> Unit, secondaire: Boolean = false) {
    val contenu: @Composable () -> Unit = {
        Icon(icone, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(libelle)
    }
    if (secondaire) {
        OutlinedButton(onClick = onClick, enabled = actif, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { contenu() }
    } else {
        Button(onClick = onClick, enabled = actif, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { contenu() }
    }
}

@Composable
private fun ResultatSauvegarde(action: ActionSauvegarde, onAcquitter: () -> Unit) {
    when (action) {
        ActionSauvegarde.Repos, ActionSauvegarde.EnCours -> Unit
        is ActionSauvegarde.Reussie -> Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = stringResource(R.string.donnees_sauvegarde_reussie, action.manifeste.enfants, action.manifeste.photos),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            TextButton(onClick = onAcquitter) { Text(stringResource(R.string.action_ok)) }
        }
        ActionSauvegarde.Echec -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.donnees_sauvegarde_echec),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onAcquitter) { Text(stringResource(R.string.action_ok)) }
        }
    }
}

/** Aperçu, progression et résultat d'une restauration (utilisé aussi par l'écran d'accueil). */
@Composable
fun DialoguesRestauration(etape: EtapeRestauration, onConfirmer: () -> Unit, onFermer: () -> Unit) {
    when (etape) {
        EtapeRestauration.Aucune -> Unit
        EtapeRestauration.Lecture, EtapeRestauration.EnCours -> AlertDialog(
            onDismissRequest = {},
            title = {
                Text(
                    stringResource(
                        if (etape == EtapeRestauration.Lecture) R.string.restauration_lecture else R.string.restauration_en_cours,
                    ),
                )
            },
            text = { CircularProgressIndicator() },
            confirmButton = {},
        )
        is EtapeRestauration.Invalide -> AlertDialog(
            onDismissRequest = onFermer,
            title = { Text(stringResource(R.string.restauration_impossible_titre)) },
            text = { Text(stringResource(libelleProbleme(etape.probleme))) },
            confirmButton = { TextButton(onClick = onFermer) { Text(stringResource(R.string.action_ok)) } },
        )
        is EtapeRestauration.Apercu -> AlertDialog(
            onDismissRequest = onFermer,
            title = { Text(stringResource(R.string.restauration_titre)) },
            text = { ApercuRestauration(etape.lue.manifeste, etape.actuelles) },
            confirmButton = {
                TextButton(onClick = onConfirmer) {
                    Text(stringResource(R.string.restauration_confirmer), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onFermer) { Text(stringResource(R.string.action_annuler)) } },
        )
        is EtapeRestauration.Reussie -> AlertDialog(
            onDismissRequest = onFermer,
            title = { Text(stringResource(R.string.restauration_reussie_titre)) },
            text = { Text(stringResource(R.string.restauration_reussie, etape.manifeste.enfants, etape.manifeste.photos)) },
            confirmButton = { TextButton(onClick = onFermer) { Text(stringResource(R.string.action_ok)) } },
        )
        EtapeRestauration.Echec -> AlertDialog(
            onDismissRequest = onFermer,
            title = { Text(stringResource(R.string.restauration_impossible_titre)) },
            text = { Text(stringResource(R.string.restauration_echec)) },
            confirmButton = { TextButton(onClick = onFermer) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

@Composable
private fun ApercuRestauration(manifeste: ManifesteSauvegarde, actuelles: ResumeDonnees) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val date = manifeste.creeeLe.atZone(ZoneId.systemDefault()).toLocalDate()
        Text(stringResource(R.string.restauration_contenu, FormatsFr.dateCourte(date), manifeste.versionApplication))
        Text(
            stringResource(R.string.restauration_nombres, manifeste.enfants, manifeste.seances, manifeste.photos),
            fontWeight = FontWeight.Medium,
        )
        if (actuelles.enfants > 0 || actuelles.seances > 0) {
            Text(
                stringResource(R.string.restauration_remplace, actuelles.enfants, actuelles.seances),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DialogueRemiseAZero(etat: EtatDonnees, actions: ActionsDonnees) {
    val etape = etat.remiseAZero
    var compris by rememberSaveable(etape is EtapeRemiseAZero.Confirmation) { mutableStateOf(false) }
    when (etape) {
        EtapeRemiseAZero.Aucune -> Unit
        is EtapeRemiseAZero.Confirmation -> AlertDialog(
            onDismissRequest = actions.onAnnulerRemiseAZero,
            title = { Text(stringResource(R.string.zero_titre)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.zero_message, etape.actuelles.enfants, etape.actuelles.seances))
                    Text(stringResource(R.string.zero_conseil), fontWeight = FontWeight.Medium)
                    OutlinedButton(
                        onClick = actions.onSauvegarder,
                        enabled = !etat.occupe,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        if (etat.sauvegarde == ActionSauvegarde.EnCours) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Backup, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(R.string.zero_sauvegarder_dabord))
                        }
                    }
                    ResultatSauvegarde(etat.sauvegarde, actions.onAcquitterSauvegarde)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(value = compris, role = Role.Checkbox, onValueChange = { compris = it }),
                    ) {
                        Checkbox(checked = compris, onCheckedChange = null)
                        Text(stringResource(R.string.zero_case), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = actions.onConfirmerRemiseAZero, enabled = compris && !etat.occupe) {
                    Text(stringResource(R.string.zero_confirmer), color = if (compris) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            dismissButton = { TextButton(onClick = actions.onAnnulerRemiseAZero) { Text(stringResource(R.string.action_annuler)) } },
        )
        EtapeRemiseAZero.EnCours -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.zero_en_cours)) },
            text = { CircularProgressIndicator() },
            confirmButton = {},
        )
        EtapeRemiseAZero.Echec -> AlertDialog(
            onDismissRequest = actions.onAnnulerRemiseAZero,
            text = { Text(stringResource(R.string.zero_echec)) },
            confirmButton = { TextButton(onClick = actions.onAnnulerRemiseAZero) { Text(stringResource(R.string.action_ok)) } },
        )
    }
}

private fun libelleProbleme(probleme: ProblemeSauvegarde): Int = when (probleme) {
    ProblemeSauvegarde.PAS_UNE_SAUVEGARDE -> R.string.restauration_pas_une_sauvegarde
    ProblemeSauvegarde.VERSION_TROP_RECENTE -> R.string.restauration_trop_recente
    ProblemeSauvegarde.ILLISIBLE -> R.string.restauration_illisible
    ProblemeSauvegarde.INCOHERENTE -> R.string.restauration_incoherente
    ProblemeSauvegarde.TROP_VOLUMINEUSE -> R.string.restauration_trop_volumineuse
}
