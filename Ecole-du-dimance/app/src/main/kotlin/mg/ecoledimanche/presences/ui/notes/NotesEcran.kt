package mg.ecoledimanche.presences.ui.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import mg.ecoledimanche.presences.R
import mg.ecoledimanche.presences.data.repository.LigneNotes
import mg.ecoledimanche.presences.data.repository.ResumeExamen
import mg.ecoledimanche.presences.domain.FormatsFr
import mg.ecoledimanche.presences.domain.Notes
import mg.ecoledimanche.presences.ui.components.EcranChargement
import mg.ecoledimanche.presences.ui.components.EcranErreur
import mg.ecoledimanche.presences.ui.components.EtatVide

/**
 * Onglet « Notes » : les examens (toucher pour saisir ou corriger les notes) puis, pour chaque enfant
 * actif, chacune de ses notes, son total et sa moyenne sur 20. Un examen sans note compte 0.
 */
@Composable
fun NotesEcran(
    etat: EtatEcranNotes,
    onNouvelExamen: () -> Unit,
    onOuvrirExamen: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        Text(
            text = stringResource(R.string.notes_titre),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        )
        Text(
            text = stringResource(R.string.notes_regle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Button(
            onClick = onNouvelExamen,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.notes_nouvel_examen))
        }
        when (etat) {
            EtatEcranNotes.Chargement -> EcranChargement(stringResource(R.string.chargement))
            EtatEcranNotes.Erreur -> EcranErreur(stringResource(R.string.notes_erreur))
            is EtatEcranNotes.Pret -> {
                val tableau = etat.tableau
                if (tableau.examens.isEmpty()) {
                    EtatVide(
                        icone = Icons.Filled.School,
                        titre = stringResource(R.string.notes_vide_titre),
                        message = stringResource(R.string.notes_vide_message),
                    )
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp), modifier = Modifier.fillMaxSize()) {
                        item { TitreSection(stringResource(R.string.notes_examens, tableau.examens.size)) }
                        items(tableau.examens, key = { "x" + it.examen.id }) { resume ->
                            LigneExamen(resume, tableau.lignes.size, onClick = { onOuvrirExamen(resume.examen.id) })
                            HorizontalDivider()
                        }
                        item { TitreSection(stringResource(R.string.notes_cumul)) }
                        if (tableau.lignes.isEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.enfants_vide_actifs),
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                                )
                            }
                        }
                        items(tableau.lignes, key = { "e" + it.enfant.id }) { ligne ->
                            LigneCumul(ligne)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TitreSection(texte: String) {
    Text(
        text = texte,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun LigneExamen(resume: ResumeExamen, enfants: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(resume.examen.intitule, style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Text(stringResource(R.string.notes_examen_resume, FormatsFr.dateCourte(resume.examen.date), resume.notesSaisies, enfants))
        },
        trailingContent = { Text(stringResource(R.string.notes_saisir), color = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

/** « 12 · 15,5 · 0 (abs.) » puis « Total 27,5 / 60 », moyenne sur 20 à droite. */
@Composable
private fun LigneCumul(ligne: LigneNotes) {
    val cumul = ligne.cumul
    val absente = stringResource(R.string.notes_absente)
    ListItem(
        headlineContent = { Text("${ligne.enfant.prenom} ${ligne.enfant.nom}", style = MaterialTheme.typography.titleMedium) },
        supportingContent = {
            Column {
                Text(cumul.notes.joinToString("  ·  ") { note -> note?.let(Notes::format) ?: absente })
                Text(
                    stringResource(R.string.notes_total, Notes.format(cumul.totalCentiemes), Notes.format(cumul.maximumCentiemes)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = cumul.moyenneCentiemes?.let(Notes::format) ?: "—",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(stringResource(R.string.notes_sur_20), style = MaterialTheme.typography.bodySmall)
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
}
