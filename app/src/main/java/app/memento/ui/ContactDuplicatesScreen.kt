package app.memento.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.memento.MainViewModel
import app.memento.contacts.Duplicates
import app.memento.suite.SuiteTopBar

/** The "Doublons" tool: contacts sharing a number or a name; per group, merge into one or ignore. Never automatic. */
@Composable
fun ContactDuplicatesScreen(vm: MainViewModel) {
    val c = vm.contacts
    val all by c.contacts.collectAsState()
    val groups = remember(all, c.ignoredGroups.toList(), c.firstNameFirst) {
        Duplicates.groups(all.filter { !it.conflict }, c.firstNameFirst).filter { g -> g.map { it.id }.toSet() !in c.ignoredGroups }
    }
    Scaffold(
        topBar = { SuiteTopBar("Doublons", onBack = { vm.back() }) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Text(
                    if (groups.isEmpty()) "Aucun doublon : pas de numéro ni de nom en double."
                    else "${groups.size} groupe${if (groups.size > 1) "s" else ""} : même numéro ou même nom. Choisis la fiche à garder ; " +
                        "elle reçoit les numéros, e-mails et notes des autres, qui sont supprimées.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(groups, key = { g -> g.joinToString(",") { it.id } }) { group ->
                var keep by remember(group) { mutableStateOf(Duplicates.best(group).id) }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        group.forEach { contact ->
                            Row(Modifier.fillMaxWidth().clickable { keep = contact.id }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(keep == contact.id, { keep = contact.id })
                                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                    Text(contact.displayName, fontWeight = FontWeight.SemiBold)
                                    val details = contact.phones.map { it.number } + contact.emails
                                    if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { c.ignoredGroups.add(group.map { it.id }.toSet()) }) { Text("Ignorer") }
                            Button(onClick = {
                                val kept = group.first { it.id == keep }
                                c.merge(kept, group.filter { it.id != keep })
                            }) { Text("Fusionner") }
                        }
                    }
                }
            }
        }
    }
}
