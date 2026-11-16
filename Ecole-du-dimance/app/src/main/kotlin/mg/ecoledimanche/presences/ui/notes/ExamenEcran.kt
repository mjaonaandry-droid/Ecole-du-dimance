package mg.ecoledimanche.presences.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import mg.ecoledimanche.presences.R
import mg.ecoledimanche.presences.domain.FormatsFr
import mg.ecoledimanche.presences.ui.components.EcranChargement
import mg.ecoledimanche.presences.ui.components.EcranErreur

class ActionsExamen(
    val onRetour: () -> Unit,
    val onIntitule: (String) -> Unit,
    val onDate: (LocalDate) -> Unit,
    val onNote: (Long, String) -> Unit,
    val onEnregistrer: () -> Unit,
    val onDemanderSuppression: () -> Unit,
    val onConfirmerSuppression: () -> Unit,
    val onAnnulerSuppression: () -> Unit,
)

/** Saisie d'un examen : intitulé, date, puis une note sur 20 par enfant actif (vide = pas de note, compte 0). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExamenEcran(etat: EtatExamen, actions: ActionsExamen, modifier: Modifier = Modifier) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(if (etat.examenId == null) R.string.examen_titre_nouveau else R.string.examen_titre_modifier)) },
                navigationIcon = {
                    IconButton(onClick = actions.onRetour) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_retour))
                    }
                },
                actions = {
                    if (etat.examenId != null && !etat.introuvable) {
                        IconButton(onClick = actions.onDemanderSuppression, enabled = !etat.enregistrement) {
                            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.examen_supprimer))
                        }
                    }
                },
            )
        },
    ) { marges ->
        when {
            etat.chargement -> EcranChargement(stringResource(R.string.chargement), Modifier.padding(marges))
            etat.introuvable -> EcranErreur(stringResource(R.string.examen_introuvable), Modifier.padding(marges))
            else -> Contenu(etat, actions, Modifier.padding(marges).imePadding())
        }
    }
    if (etat.confirmationSuppression) {
        AlertDialog(
            onDismissRequest = actions.onAnnulerSuppression,
            title = { Text(stringResource(R.string.examen_supprimer_titre)) },
            text = { Text(stringResource(R.string.examen_supprimer_message, etat.intitule)) },
            confirmButton = {
                TextButton(onClick = actions.onConfirmerSuppression) {
                    Text(stringResource(R.string.suppression_confirmer), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = actions.onAnnulerSuppression) { Text(stringResource(R.string.action_annuler)) } },
        )
    }
}

@Composable
private fun Contenu(etat: EtatExamen, actions: ActionsExamen, modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            item {
                OutlinedTextField(
                    value = etat.intitule,
                    onValueChange = actions.onIntitule,
                    label = { Text(stringResource(R.string.examen_intitule)) },
                    placeholder = { Text(stringResource(R.string.examen_intitule_exemple)) },
                    isError = etat.intituleInvalide,
                    supportingText = if (etat.intituleInvalide) {
                        { Text(stringResource(R.string.erreur_obligatoire)) }
                    } else {
                        null
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item { ChampDateExamen(etat.date, actions.onDate) }
            item {
                Text(
                    text = stringResource(R.string.examen_aide_notes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (etat.notes.isEmpty()) {
                item { Text(stringResource(R.string.enfants_vide_actifs), modifier = Modifier.padding(vertical = 16.dp)) }
            }
            items(etat.notes, key = { it.enfantId }) { saisie -> LigneSaisie(saisie, actions.onNote) }
        }
        HorizontalDivider()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            if (etat.erreurEnregistrement) {
                Text(stringResource(R.string.examen_erreur_enregistrement), color = MaterialTheme.colorScheme.error)
            }
            if (etat.notes.any { it.invalide }) {
                Text(stringResource(R.string.examen_notes_invalides), color = MaterialTheme.colorScheme.error)
            }
            Button(
                onClick = actions.onEnregistrer,
                enabled = !etat.enregistrement,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                if (etat.enregistrement) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                } else {
                    Text(
                        stringResource(R.string.examen_enregistrer, etat.notesSaisies, etat.notes.size),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun LigneSaisie(saisie: SaisieNote, onNote: (Long, String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "${saisie.prenom} ${saisie.nom}",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(end = 12.dp),
        )
        OutlinedTextField(
            value = saisie.texte,
            onValueChange = { onNote(saisie.enfantId, it) },
            placeholder = { Text(stringResource(R.string.examen_note_vide)) },
            suffix = { Text("/ 20") },
            isError = saisie.invalide,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Next),
            modifier = Modifier.width(132.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChampDateExamen(date: LocalDate, onChoisir: (LocalDate) -> Unit) {
    var ouvert by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.examen_date, FormatsFr.dateCourte(date)),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { ouvert = true }) {
            Icon(Icons.Filled.CalendarMonth, contentDescription = null)
            Text(stringResource(R.string.examen_changer_date), modifier = Modifier.padding(start = 8.dp))
        }
    }
    if (ouvert) {
        val etat = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { ouvert = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        etat.selectedDateMillis?.let { onChoisir(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                        ouvert = false
                    },
                ) { Text(stringResource(R.string.action_ok)) }
            },
            dismissButton = { TextButton(onClick = { ouvert = false }) { Text(stringResource(R.string.action_annuler)) } },
        ) {
            DatePicker(state = etat)
        }
    }
}
