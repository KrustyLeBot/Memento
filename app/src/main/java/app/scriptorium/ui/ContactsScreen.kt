package app.scriptorium.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.scriptorium.MainViewModel
import app.scriptorium.contacts.Contact
import app.scriptorium.contacts.ContactList
import app.scriptorium.suite.SuiteTopBar
import kotlinx.coroutines.launch

/** The Contacts module: favourites, letter sections with a draggable letter scroller, search, count. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
fun ContactsScreen(vm: MainViewModel) {
    val c = vm.contacts
    val all by c.contacts.collectAsState()
    val snackbar = rememberVmSnackbar(vm)
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) c.startImport(uri) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-vcard")) { uri -> if (uri != null) c.exportAll(uri) }
    var menu by remember { mutableStateOf(false) }
    BackHandler(enabled = c.query.isNotEmpty()) { c.query = "" }

    val searching = c.query.isNotBlank()
    val sections = remember(all, c.firstNameFirst) { ContactList.sections(all, c.firstNameFirst) }
    val results = remember(all, c.query, c.firstNameFirst) { if (searching) ContactList.search(all, c.query, c.firstNameFirst) else emptyList() }
    // index of each section's header in the LazyColumn (item 0 is the count line)
    val starts = remember(sections) {
        var i = 1
        sections.associate { s -> (s.title to i).also { i += 1 + s.contacts.size } }
    }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            SuiteTopBar("Contacts") {
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Importer des contacts") }, { menu = false; pick.launch(arrayOf("*/*")) })
                        DropdownMenuItem({ Text("Exporter les contacts") }, { menu = false; export.launch("contacts.vcf") }, enabled = all.isNotEmpty())
                        DropdownMenuItem({ Text("Doublons") }, { menu = false; c.openDuplicates() }, enabled = all.size > 1)
                    }
                }
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { c.newContact() }) { Icon(Icons.Default.Add, "Nouveau contact") }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            OutlinedTextField(
                value = c.query, onValueChange = { c.query = it },
                placeholder = { Text("Rechercher (nom, numéro, e-mail, notes)") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (c.query.isNotEmpty()) IconButton(onClick = { c.query = "" }) { Icon(Icons.Default.Close, "Effacer") } },
                singleLine = true, shape = RoundedCornerShape(28.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
            if (c.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = list, contentPadding = PaddingValues(bottom = 88.dp), modifier = Modifier.fillMaxSize()) {
                    item(key = "count") {
                        Text(
                            countText(if (searching) results.size else all.count { !it.conflict }, searching),
                            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                        )
                    }
                    if (all.isEmpty()) {
                        item(key = "empty") {
                            Text(
                                "Aucun contact. Crée-en un avec +, ou importe un fichier .vcf (menu ⋮ → Importer des contacts).",
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp),
                            )
                        }
                    }
                    if (searching) {
                        items(results, key = { "r:" + it.path }) { ContactRow(it) { c.open(it) } }
                    } else {
                        sections.forEach { s ->
                            stickyHeader(key = "h:" + s.title) { SectionHeader(s.title) }
                            items(s.contacts, key = { s.title + ":" + it.path }) { ContactRow(it) { c.open(it) } }
                        }
                    }
                }
                if (!searching) {
                    val letters = sections.map { it.title }.filter { it != ContactList.FAVORITES }
                    if (letters.size > 1) {
                        LetterScroller(letters, Modifier.align(Alignment.CenterEnd)) { letter ->
                            starts[letter]?.let { scope.launch { list.scrollToItem(it) } }
                        }
                    }
                }
            }
        }
    }
    ContactImportDialogs(c)
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun ContactRow(contact: Contact, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 32.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ContactAvatar(contact)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                contact.displayName + if (contact.conflict) " (copie en conflit)" else "",
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val second = contact.phones.firstOrNull()?.number ?: contact.emails.firstOrNull()
            if (second != null && second != contact.displayName) {
                Text(second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        if (contact.favorite) Icon(Icons.Default.Star, "Favori", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
    }
}

/** The letters down the right edge: tap or drag to jump; the letter under the finger is shown big. */
@Composable
private fun LetterScroller(letters: List<String>, modifier: Modifier, onLetter: (String) -> Unit) {
    var height by remember { mutableStateOf(1) }
    var active by remember { mutableStateOf<String?>(null) }
    // the gesture coroutines outlive recompositions (keyed on the letters): read the latest positions
    val jump by rememberUpdatedState(onLetter)
    fun at(y: Float): String = letters[((y / height) * letters.size).toInt().coerceIn(0, letters.lastIndex)]
    fun pick(y: Float) {
        val l = at(y)
        if (l != active) { active = l; jump(l) }
    }
    Box(modifier.fillMaxHeight()) {
        Column(
            Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(28.dp).padding(vertical = 8.dp)
                .onSizeChanged { height = maxOf(1, it.height) }
                .pointerInput(letters) {
                    detectTapGestures(onPress = { pick(it.y); tryAwaitRelease(); active = null })
                }
                .pointerInput(letters) {
                    detectVerticalDragGestures(
                        onDragStart = { pick(it.y) },
                        onDragEnd = { active = null },
                        onDragCancel = { active = null },
                        onVerticalDrag = { change, _ -> change.consume(); pick(change.position.y) },
                    )
                },
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            letters.forEach { l ->
                Text(l, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            }
        }
        active?.let { l ->
            Box(
                Modifier.align(Alignment.Center).padding(end = 48.dp).size(56.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(l, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}
