package app.memento.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.memento.MainViewModel
import app.memento.Note
import app.memento.suite.SuiteBanner
import app.memento.suite.UpdateBanner
import androidx.compose.material.icons.filled.Sync
import app.memento.fold

private fun filterNotes(notes: List<Note>, label: String?, query: String): List<Note> {
    val q = query.trim().fold()
    return notes
        .filter { label == null || label in it.labels }
        .filter {
            q.isEmpty() || it.title.fold().contains(q) || it.body.fold().contains(q) ||
                it.labels.any { l -> l.fold().contains(q) }
        }
        .sortedWith(compareByDescending<Note> { it.pinned }.thenByDescending { it.updated })
}

private fun previewText(note: Note): String =
    note.plainBody().lines()
        .filter { it.isNotBlank() && !it.trimStart().startsWith("![") }
        .take(8)
        .joinToString("\n") { it.replace("**", "").replace(Regex("^#{1,3} "), "") }
        .take(300)

@Composable
fun ListScreen(vm: MainViewModel, onScan: () -> Unit) {
    val notes by vm.notes.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val visible = remember(notes, vm.labelFilter, vm.query) { filterNotes(notes, vm.labelFilter, vm.query) }
    val labels = remember(notes) { notes.flatMap { it.labels }.distinct().sortedBy { it.fold() } }

    LaunchedEffect(vm.snack) {
        val s = vm.snack ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(s.text, actionLabel = if (s.undo != null) s.action else null)
        if (result == SnackbarResult.ActionPerformed) s.undo?.invoke()
        vm.snack = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SmallFloatingActionButton(
                    onClick = { if (!vm.syncing && !vm.savingScan) onScan() },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) { Icon(Icons.Default.DocumentScanner, "Scanner un document") }
                SmallFloatingActionButton(
                    onClick = { vm.newNote(checklist = true) },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ) { Icon(Icons.Default.Checklist, "Nouvelle liste") }
                ExtendedFloatingActionButton(
                    onClick = { vm.newNote() },
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("Note") },
                )
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            // Search pill: search, view toggle and settings in one place.
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth().height(52.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
                    Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        if (vm.query.isEmpty()) {
                            Text("Rechercher dans tes notes", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        BasicTextField(
                            value = vm.query,
                            onValueChange = { vm.query = it },
                            singleLine = true,
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (vm.query.isNotEmpty()) {
                        IconButton(onClick = { vm.query = "" }) { Icon(Icons.Default.Close, "Effacer") }
                    }
                    IconButton(onClick = { vm.changeGridView(!vm.gridView) }) {
                        Icon(
                            if (vm.gridView) Icons.Default.ViewAgenda else Icons.Default.ViewModule,
                            if (vm.gridView) "Vue liste" else "Vue grille",
                        )
                    }
                }
            }

            if (labels.isNotEmpty()) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    labels.forEach { l ->
                        FilterChip(
                            selected = vm.labelFilter == l,
                            onClick = { vm.selectLabel(if (vm.labelFilter == l) null else l) },
                            label = { Text(l) },
                        )
                    }
                }
            }

            if (vm.syncing) {
                SuiteBanner("Notes en lecture seule le temps de la synchro.", title = "Synchronisation", icon = Icons.Default.Sync, progress = -1f)
            } else vm.syncNotice?.let { SuiteBanner(it) }
            UpdateBanner(vm.updates)

            if (vm.query.isBlank() && vm.labelFilter == null) RecordingEntry(vm)

            if (visible.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.EditNote, null, modifier = Modifier.size(72.dp),
                            tint = MaterialTheme.colorScheme.outline,
                        )
                        Text(
                            if (vm.query.isNotBlank() || vm.labelFilter != null) "Aucun résultat" else "Aucune note pour l'instant",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        if (vm.query.isBlank() && vm.labelFilter == null) {
                            Text("Touche « Note » pour commencer", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            } else {
                val pinned = visible.filter { it.pinned }
                val others = visible.filter { !it.pinned }
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(if (vm.gridView) 2 else 1),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 160.dp),
                    verticalItemSpacing = 10.dp,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (pinned.isNotEmpty()) {
                        item(span = StaggeredGridItemSpan.FullLine, key = "h-pinned") { SectionHeader("ÉPINGLÉES") }
                        items(pinned, key = { it.path }) { NoteCard(it) { vm.openNote(it) } }
                        if (others.isNotEmpty()) {
                            item(span = StaggeredGridItemSpan.FullLine, key = "h-others") { SectionHeader("AUTRES") }
                        }
                    }
                    items(others, key = { it.path }) { NoteCard(it) { vm.openNote(it) } }
                }
            }
        }
    }
}

/** A recording running in the background stays one tap away from the notes. */
@Composable
private fun RecordingEntry(vm: MainViewModel) {
    val live by app.memento.record.Recorder.state.collectAsState()
    if (live == null || live?.finished != false) return
    Card(
        onClick = { vm.openRecord() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🏅", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(end = 12.dp))
            Column(Modifier.weight(1f)) {
                Text("Activités", style = MaterialTheme.typography.titleMedium)
                Text("Enregistrement en cours", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Default.PushPin, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f))
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
}

@Composable
private fun NoteCard(note: Note, onClick: () -> Unit) {
    val fg = noteForeground(note.color)
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = noteBackground(note.color), contentColor = fg),
        border = BorderStroke(1.dp, if (note.color == app.memento.DEFAULT_COLOR) MaterialTheme.colorScheme.outlineVariant else noteBackground(note.color)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (note.pdf != null || note.attachments.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
                    if (note.pdf != null) {
                        Icon(Icons.Default.PictureAsPdf, null, Modifier.size(18.dp))
                        Text(" PDF  ", style = MaterialTheme.typography.labelMedium)
                    }
                    if (note.attachments.isNotEmpty()) {
                        Icon(Icons.Default.AttachFile, null, Modifier.size(18.dp))
                        Text(" ${note.attachments.size}", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (note.conflict) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.padding(bottom = 6.dp),
                ) {
                    Text(
                        "Conflit", fontSize = 11.sp, color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
            if (note.title.isNotBlank()) {
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        note.title, style = MaterialTheme.typography.titleMedium,
                        maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    if (note.pinned) {
                        Icon(Icons.Default.PushPin, null, Modifier.padding(start = 4.dp).height(18.dp), tint = fg.copy(alpha = 0.6f))
                    }
                }
            }
            val preview = previewText(note)
            if (preview.isNotBlank()) {
                Text(
                    preview, style = MaterialTheme.typography.bodyMedium,
                    color = fg.copy(alpha = 0.85f),
                    maxLines = 10, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = if (note.title.isNotBlank()) 6.dp else 0.dp),
                )
            }
            if (note.labels.isNotEmpty()) {
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    note.labels.take(3).forEach { l ->
                        Surface(shape = RoundedCornerShape(50), color = fg.copy(alpha = 0.12f)) {
                            Text(l, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
