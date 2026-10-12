package mg.ecoledimanche.presences.ui.enfants

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import mg.ecoledimanche.presences.R
import mg.ecoledimanche.presences.data.local.EnfantEntity
import mg.ecoledimanche.presences.domain.Age
import mg.ecoledimanche.presences.ui.components.EtatVide
import mg.ecoledimanche.presences.ui.components.PhotoEnfant
import mg.ecoledimanche.presences.ui.components.libelleAge

@Composable
fun EnfantsEcran(
    etat: EtatEcranEnfants,
    onRecherche: (String) -> Unit,
    onVoirArchives: (Boolean) -> Unit,
    onOuvrir: (Long) -> Unit,
    onAjouter: () -> Unit,
    modifier: Modifier = Modifier,
    onImporter: () -> Unit = {},
    desarchivage: DesarchivageListeUi = DesarchivageListeUi(),
    onDemanderDesarchivage: (EnfantEntity) -> Unit = {},
    onConfirmerDesarchivage: () -> Unit = {},
    onAnnulerDesarchivage: () -> Unit = {},
) {
    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
            ) {
                Text(
                    text = stringResource(R.string.enfants_titre),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                // Import CSV : ajouter plusieurs enfants d'un coup (fichier au format de l'export).
                TextButton(onClick = onImporter) {
                    Icon(Icons.Filled.UploadFile, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.enfants_importer))
                }
            }
            ChampRecherche(valeurInitiale = etat.recherche, onRecherche = onRecherche)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 16.dp)) {
                FilterChip(
                    selected = !etat.archives,
                    onClick = { onVoirArchives(false) },
                    label = { Text(stringResource(R.string.filtre_actifs)) },
                )
                FilterChip(
                    selected = etat.archives,
                    onClick = { onVoirArchives(true) },
                    label = { Text(stringResource(R.string.filtre_archives)) },
                )
            }
            when {
                etat.chargement -> Unit
                etat.totalSansFiltre == 0 -> EtatVide(
                    icone = if (etat.archives) Icons.Filled.Inventory2 else Icons.Filled.Groups,
                    titre = stringResource(if (etat.archives) R.string.enfants_vide_archives else R.string.enfants_vide_actifs),
                    message = if (etat.archives) null else stringResource(R.string.enfants_vide_actifs_message),
                )
                etat.enfants.isEmpty() -> EtatVide(
                    icone = Icons.Filled.SearchOff,
                    titre = stringResource(R.string.enfants_aucun_resultat, etat.recherche.trim()),
                )
                else -> LazyColumn(contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp), modifier = Modifier.fillMaxSize()) {
                    items(etat.enfants, key = { it.id }) { enfant ->
                        // Liste « Archivés » : bouton « Désarchiver » au bout de chaque ligne.
                        val actionDesarchiver: (@Composable () -> Unit)? = if (etat.archives) {
                            {
                                TextButton(onClick = { onDemanderDesarchivage(enfant) }) {
                                    Icon(Icons.Filled.Unarchive, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text(stringResource(R.string.enfants_desarchiver))
                                }
                            }
                        } else {
                            null
                        }
                        ListItem(
                            leadingContent = {
                                PhotoEnfant(
                                    chemin = enfant.photoPath,
                                    sexe = enfant.sexe,
                                    description = stringResource(R.string.photo_de, enfant.prenom, enfant.nom),
                                    coteMaxPx = 240,
                                    forme = CircleShape,
                                    modifier = Modifier.size(56.dp),
                                )
                            },
                            headlineContent = {
                                Text("${enfant.prenom} ${enfant.nom}", style = MaterialTheme.typography.titleMedium)
                            },
                            supportingContent = { Text(libelleAge(Age.enAnnees(enfant.dateNaissance, etat.aujourdhui))) },
                            trailingContent = actionDesarchiver,
                            modifier = Modifier.fillMaxWidth().clickable { onOuvrir(enfant.id) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
        if (!etat.archives) {
            ExtendedFloatingActionButton(
                onClick = onAjouter,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.enfants_ajouter)) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
    val aDesarchiver = desarchivage.enfant
    if (aDesarchiver != null) {
        AlertDialog(
            onDismissRequest = onAnnulerDesarchivage,
            title = { Text(stringResource(R.string.desarchivage_titre)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.desarchivage_message_nom, "${aDesarchiver.prenom} ${aDesarchiver.nom}"))
                    if (desarchivage.erreur) {
                        Text(stringResource(R.string.desarchivage_erreur), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmerDesarchivage, enabled = !desarchivage.enCours) {
                    if (desarchivage.enCours) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.desarchivage_confirmer))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = onAnnulerDesarchivage, enabled = !desarchivage.enCours) {
                    Text(stringResource(R.string.action_annuler))
                }
            },
        )
    }
}

/** Le champ garde son propre état : le faire repasser par un flux asynchrone ferait sauter le curseur. */
@Composable
private fun ChampRecherche(valeurInitiale: String, onRecherche: (String) -> Unit) {
    var texte by rememberSaveable { mutableStateOf(valeurInitiale) }
    OutlinedTextField(
        value = texte,
        onValueChange = {
            texte = it
            onRecherche(it)
        },
        singleLine = true,
        label = { Text(stringResource(R.string.enfants_recherche)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            if (texte.isNotEmpty()) {
                IconButton(onClick = { texte = ""; onRecherche("") }) {
                    Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.recherche_effacer))
                }
            }
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}
