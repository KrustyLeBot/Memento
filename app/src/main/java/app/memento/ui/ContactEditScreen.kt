package app.memento.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.memento.MainViewModel
import app.memento.contacts.PhoneLabels
import app.memento.suite.SuiteTopBar

/** Create / edit a contact. Back saves (like a note); "Annuler les modifications" leaves without saving. */
@Composable
fun ContactEditScreen(vm: MainViewModel) {
    val c = vm.contacts
    val ed = c.editor ?: return
    val snackbar = rememberVmSnackbar(vm)
    var menu by remember { mutableStateOf(false) }
    val birthdayError = ed.birthday().exceptionOrNull()?.message

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            SuiteTopBar(if (ed.original == null) "Nouveau contact" else "Modifier le contact", onBack = { vm.back() }) {
                TextButton(onClick = { c.closeEditor(save = true) }, enabled = !c.busy) { Text("Enregistrer") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Annuler les modifications") }, { menu = false; c.closeEditor(save = false) })
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val words = KeyboardOptions(capitalization = KeyboardCapitalization.Words)
            OutlinedTextField(ed.firstName, { ed.firstName = it }, label = { Text("Prénom") }, singleLine = true, keyboardOptions = words, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(ed.lastName, { ed.lastName = it }, label = { Text("Nom") }, singleLine = true, keyboardOptions = words, modifier = Modifier.fillMaxWidth())

            Label("Téléphone")
            ed.phones.forEachIndexed { i, p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        p.number, { ed.phones[i] = p.copy(number = it) }, singleLine = true,
                        placeholder = { Text("06 12 34 56 78") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    LabelPicker(p.label) { ed.phones[i] = p.copy(label = it) }
                    IconButton(onClick = { ed.phones.removeAt(i) }) { Icon(Icons.Default.Close, "Retirer ce numéro") }
                }
            }
            TextButton(onClick = { ed.phones.add(app.memento.contacts.PhoneField("", PhoneLabels.MOBILE)) }) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Ajouter un numéro")
            }

            Label("E-mail")
            ed.emails.forEachIndexed { i, e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        e, { ed.emails[i] = it }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { ed.emails.removeAt(i) }) { Icon(Icons.Default.Close, "Retirer cet e-mail") }
                }
            }
            TextButton(onClick = { ed.emails.add("") }) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Ajouter un e-mail") }

            OutlinedTextField(ed.address, { ed.address = it }, label = { Text("Adresse") }, modifier = Modifier.fillMaxWidth())

            Label("Anniversaire")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(
                    ed.birthDay, { v -> ed.birthDay = v.filter { it.isDigit() }.take(2) }, label = { Text("Jour") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.width(76.dp),
                )
                MonthPicker(ed.birthMonth) { ed.birthMonth = it }
                OutlinedTextField(
                    ed.birthYear, { v -> ed.birthYear = v.filter { it.isDigit() }.take(4) }, label = { Text("Année") }, singleLine = true,
                    placeholder = { Text("facultative") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.weight(1f),
                )
                if (ed.birthDay.isNotEmpty() || ed.birthMonth != null || ed.birthYear.isNotEmpty()) {
                    IconButton(onClick = { ed.birthDay = ""; ed.birthMonth = null; ed.birthYear = "" }) { Icon(Icons.Default.Close, "Effacer l'anniversaire") }
                }
            }
            birthdayError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Favori", modifier = Modifier.weight(1f))
                Switch(ed.favorite, { ed.favorite = it })
            }

            OutlinedTextField(
                ed.notes, { ed.notes = it }, label = { Text("Notes") }, minLines = 4,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun LabelPicker(label: String?, onPick: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val options = (PhoneLabels.standard + listOfNotNull(label)).distinct()
    Box {
        OutlinedButton(onClick = { open = true }) { Text(label ?: "type") }
        DropdownMenu(open, { open = false }) {
            options.forEach { o -> DropdownMenuItem({ Text(o) }, { open = false; onPick(o) }) }
            DropdownMenuItem({ Text("aucun") }, { open = false; onPick(null) })
        }
    }
}

@Composable
private fun MonthPicker(month: Int?, onPick: (Int?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(month?.let { MONTHS[it - 1] } ?: "Mois") }
        DropdownMenu(open, { open = false }) {
            MONTHS.forEachIndexed { i, m -> DropdownMenuItem({ Text(m) }, { open = false; onPick(i + 1) }) }
        }
    }
}
