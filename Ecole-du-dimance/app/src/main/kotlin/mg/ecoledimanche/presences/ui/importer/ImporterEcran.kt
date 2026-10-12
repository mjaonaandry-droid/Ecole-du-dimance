package mg.ecoledimanche.presences.ui.importer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mg.ecoledimanche.presences.R
import mg.ecoledimanche.presences.domain.DonneesEnfant
import mg.ecoledimanche.presences.domain.FormatsFr
import mg.ecoledimanche.presences.domain.LigneRejetee
import mg.ecoledimanche.presences.domain.MotifRejet
import mg.ecoledimanche.presences.domain.Sexe
import mg.ecoledimanche.presences.ui.components.EcranChargement

class ActionsImport(
    val onRetour: () -> Unit,
    val onChoisirFichier: () -> Unit,
    val onConfirmer: () -> Unit,
    val onRecommencer: () -> Unit,
)

/** Import d'un fichier CSV au format de l'export : aperçu, puis ajout de tous les enfants valides. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImporterEcran(etat: EtatImport, actions: ActionsImport, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.import_titre)) },
                navigationIcon = {
                    IconButton(onClick = actions.onRetour) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_retour))
                    }
                },
            )
        },
    ) { marges ->
        when (etat) {
            EtatImport.Lecture -> EcranChargement(stringResource(R.string.import_lecture), Modifier.padding(marges))
            is EtatImport.Apercu -> Apercu(etat, actions, PaddingValues(16.dp), Modifier.padding(marges))
            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize().padding(marges),
            ) {
                when (etat) {
                    EtatImport.Repos -> item { Explications(actions.onChoisirFichier) }
                    EtatImport.FichierIllisible -> item {
                        Probleme(stringResource(R.string.import_illisible), actions.onChoisirFichier)
                    }
                    EtatImport.FichierVide -> item {
                        Probleme(stringResource(R.string.import_vide), actions.onChoisirFichier)
                    }
                    is EtatImport.ColonnesManquantes -> item {
                        Probleme(
                            stringResource(R.string.import_colonnes_manquantes, etat.colonnes.joinToString(" ; ")),
                            actions.onChoisirFichier,
                            aide = stringResource(R.string.import_format_entete),
                        )
                    }
                    is EtatImport.Termine -> item { Termine(etat, actions) }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun Explications(onChoisirFichier: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.import_explication), style = MaterialTheme.typography.bodyLarge)
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.import_format_titre),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
                Text(stringResource(R.string.import_format_colonnes), style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(R.string.import_format_exemple),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(stringResource(R.string.import_format_ignore), style = MaterialTheme.typography.bodyMedium)
                Text(
                    stringResource(R.string.import_format_regles),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Button(
            onClick = onChoisirFichier,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            Icon(Icons.Filled.UploadFile, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.import_choisir), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Probleme(message: String, onChoisirFichier: () -> Unit, aide: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer), modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(message, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyLarge)
                    if (aide != null) {
                        Text(aide, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        Button(onClick = onChoisirFichier, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(stringResource(R.string.import_choisir_autre))
        }
    }
}

@Composable
private fun Apercu(apercu: EtatImport.Apercu, actions: ActionsImport, contenu: PaddingValues, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(contentPadding = contenu, modifier = Modifier.weight(1f).fillMaxWidth()) {
            item {
                Text(
                    text = if (apercu.aAjouter.isEmpty()) {
                        stringResource(R.string.import_apercu_aucun)
                    } else {
                        libelleNombreEnfants(apercu.aAjouter.size, R.string.import_apercu_un, R.string.import_apercu_plusieurs)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            items(apercu.aAjouter) { enfant -> LigneEnfant(enfant) }
            if (apercu.rejets.isNotEmpty()) {
                item {
                    Text(
                        text = libelleNombreEnfants(apercu.rejets.size, R.string.import_rejets_un, R.string.import_rejets_plusieurs),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 20.dp, bottom = 8.dp),
                    )
                }
                items(apercu.rejets) { rejet -> LigneRejet(rejet) }
            }
        }
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (apercu.erreurEnregistrement) {
                Text(stringResource(R.string.import_erreur_enregistrement), color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = actions.onConfirmer,
                enabled = apercu.aAjouter.isNotEmpty() && !apercu.enregistrement,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                if (apercu.enregistrement) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(
                        libelleNombreEnfants(apercu.aAjouter.size, R.string.import_bouton_un, R.string.import_bouton_plusieurs),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            OutlinedButton(
                onClick = actions.onRecommencer,
                enabled = !apercu.enregistrement,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.import_choisir_autre)) }
        }
    }
}

@Composable
private fun LigneEnfant(enfant: DonneesEnfant) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text("${enfant.prenom} ${enfant.nom}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        Text(
            text = stringResource(
                R.string.import_ligne_details,
                stringResource(if (enfant.sexe == Sexe.GARCON) R.string.sexe_garcon else R.string.sexe_fille),
                FormatsFr.dateCourte(enfant.dateNaissance),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

@Composable
private fun LigneRejet(rejet: LigneRejetee) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            text = stringResource(R.string.import_rejet_ligne, rejet.numeroLigne, rejet.libelle.ifEmpty { "—" }),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Text(libelleMotif(rejet.motif), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    HorizontalDivider()
}

@Composable
private fun Termine(termine: EtatImport.Termine, actions: ActionsImport) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        libelleNombreEnfants(termine.ajoutes, R.string.import_termine_un, R.string.import_termine_plusieurs),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(stringResource(R.string.import_termine_aide), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Button(onClick = actions.onRetour, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
            Text(stringResource(R.string.import_voir_liste))
        }
        OutlinedButton(onClick = actions.onRecommencer, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.import_autre_fichier))
        }
    }
}

/** Le pluriel français commence à 2 : « 1 enfant », « 0 enfant », « 12 enfants ». */
@Composable
private fun libelleNombreEnfants(nombre: Int, singulier: Int, pluriel: Int): String =
    if (nombre <= 1) stringResource(singulier, nombre) else stringResource(pluriel, nombre)

@Composable
private fun libelleMotif(motif: MotifRejet): String = stringResource(
    when (motif) {
        MotifRejet.NOM_MANQUANT -> R.string.import_motif_nom
        MotifRejet.PRENOM_MANQUANT -> R.string.import_motif_prenom
        MotifRejet.NAISSANCE_MANQUANTE -> R.string.import_motif_naissance_manquante
        MotifRejet.NAISSANCE_INVALIDE -> R.string.import_motif_naissance_invalide
        MotifRejet.NAISSANCE_FUTURE -> R.string.import_motif_naissance_future
        MotifRejet.SEXE_MANQUANT -> R.string.import_motif_sexe_manquant
        MotifRejet.SEXE_INVALIDE -> R.string.import_motif_sexe_invalide
        MotifRejet.TEXTE_TROP_LONG -> R.string.import_motif_trop_long
        MotifRejet.DEJA_INSCRIT -> R.string.import_motif_deja_inscrit
        MotifRejet.DOUBLON_DANS_LE_FICHIER -> R.string.import_motif_doublon
        MotifRejet.ARCHIVE -> R.string.import_motif_archive
    },
)
