package app.scriptorium.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.scriptorium.CheckItem
import app.scriptorium.DEFAULT_COLOR
import app.scriptorium.EditorState
import app.scriptorium.ItemIds
import app.scriptorium.MainViewModel
import app.scriptorium.MarkdownEdit
import app.scriptorium.MarkdownTransformation
import app.scriptorium.Note
import app.scriptorium.findUrls
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun EditorScreen(vm: MainViewModel, ed: EditorState) {
    val context = LocalContext.current
    val bg = noteBackground(ed.color)
    val fg = noteForeground(ed.color)
    val focusManager = LocalFocusManager.current
    var showColors by remember { mutableStateOf(false) }
    var showLabels by remember { mutableStateOf(false) }
    var textFocused by remember { mutableStateOf(false) }
    var showAttach by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    // an event's description: the event screens own its date and its deletion
    val isEvent = app.scriptorium.NoteRepository.isAgendaPath(ed.base?.path.orEmpty())
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.snack) {
        val s = vm.snack ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(s.text, actionLabel = if (s.undo != null) s.action else null)
        if (result == SnackbarResult.ActionPerformed) s.undo?.invoke()
        vm.snack = null
    }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { vm.addAttachments(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addAttachments(it) }

    LaunchedEffect(ed) {
        androidx.compose.runtime.snapshotFlow { ed.snapshot() }
            .debounce(700)
            .collect { vm.persist(ed) }
    }

    val linkColor = MaterialTheme.colorScheme.primary
    val transformation = remember(linkColor) { MarkdownTransformation(linkColor) }
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = fg)
    val cursor = SolidColor(fg)
    val hint = fg.copy(alpha = 0.5f)

    // Drag & drop state for checklist reordering.
    var dragId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { HashMap<Long, Int>() }

    fun moveUnchecked(id: Long, delta: Int) {
        val un = ed.items.filter { !it.checked }.toMutableList()
        val i = un.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in un.indices) return
        val tmp = un[i]; un[i] = un[j]; un[j] = tmp
        val checked = ed.items.filter { it.checked }
        ed.items.clear()
        ed.items.addAll(un + checked)
    }

    fun updateItem(id: Long, f: (CheckItem) -> CheckItem) {
        val i = ed.items.indexOfFirst { it.id == id }
        if (i >= 0) ed.items[i] = f(ed.items[i])
    }

    fun addItemAfter(id: Long?) {
        val fresh = CheckItem(ItemIds.next(), "", false)
        val i = if (id == null) -1 else ed.items.indexOfFirst { it.id == id }
        if (i >= 0) ed.items.add(i + 1, fresh)
        else {
            val lastUnchecked = ed.items.indexOfLast { !it.checked }
            ed.items.add(lastUnchecked + 1, fresh)
        }
        ed.pendingFocusId = fresh.id
    }

    fun removeItem(id: Long, focusPrevious: Boolean) {
        val un = ed.items.filter { !it.checked }
        val idx = un.indexOfFirst { it.id == id }
        ed.items.removeAll { it.id == id }
        if (focusPrevious && idx > 0) ed.pendingFocusId = un[idx - 1].id
    }

    Scaffold(
        containerColor = bg,
        contentColor = fg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg, titleContentColor = fg),
                navigationIcon = {
                    IconButton(onClick = { vm.closeEditor() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour", tint = fg) }
                },
                title = {},
                actions = {
                    if (!isEvent) {
                        IconButton(onClick = { showDate = true }) {
                            Icon(androidx.compose.material.icons.Icons.Default.Event, "Ajouter à l'agenda", tint = fg.copy(alpha = 0.6f))
                        }
                        IconButton(onClick = { ed.pinned = !ed.pinned }) {
                            Icon(
                                Icons.Default.PushPin, if (ed.pinned) "Désépingler" else "Épingler",
                                tint = if (ed.pinned) MaterialTheme.colorScheme.primary else fg.copy(alpha = 0.6f),
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            Surface(color = bg, contentColor = fg) {
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    if (textFocused) {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FormatButton(Icons.Default.FormatBold, "Gras") { ed.text = MarkdownEdit.toggleWrap(ed.text, "**") }
                            FormatButton(Icons.Default.FormatItalic, "Italique") { ed.text = MarkdownEdit.toggleWrap(ed.text, "*") }
                            FormatButton(Icons.Default.Title, "Titre") { ed.text = MarkdownEdit.cycleHeading(ed.text) }
                            FormatButton(Icons.AutoMirrored.Filled.FormatListBulleted, "Puces") { ed.text = MarkdownEdit.toggleBullet(ed.text) }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        ToolButton(Icons.Default.CheckBox, "Liste à cocher") { vm.convertToChecklist() }
                        ToolButton(Icons.Default.Palette, "Couleur") { showColors = true }
                        ToolButton(Icons.Default.Label, "Libellés") { showLabels = true }
                        ToolButton(Icons.Default.Brush, "Dessiner") { vm.newDrawing() }
                        Box {
                            ToolButton(Icons.Default.AttachFile, "Joindre un fichier") { showAttach = true }
                            DropdownMenu(expanded = showAttach, onDismissRequest = { showAttach = false }) {
                                DropdownMenuItem(
                                    text = { Text("Photo ou vidéo") },
                                    leadingIcon = { Icon(Icons.Default.PhotoLibrary, null) },
                                    onClick = {
                                        showAttach = false
                                        pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Fichier") },
                                    leadingIcon = { Icon(Icons.Default.Folder, null) },
                                    onClick = { showAttach = false; pickFiles.launch(arrayOf("*/*")) },
                                )
                            }
                        }
                        ToolButton(Icons.Default.Share, "Partager") {
                            val text = Note(
                                path = "", id = "", title = ed.title,
                                created = 0, updated = 0, body = ed.body(),
                            ).shareText()
                            val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_SUBJECT, ed.title).putExtra(Intent.EXTRA_TEXT, text)
                            context.startActivity(Intent.createChooser(send, null))
                        }
                        Spacer(Modifier.weight(1f))
                        if (!isEvent) IconButton(onClick = { vm.deleteEditorNote() }) {
                            // the error red vanishes on the coral and burgundy notes: the note's own text colour there
                            Icon(Icons.Default.Delete, "Supprimer", tint = if (ed.color == DEFAULT_COLOR) MaterialTheme.colorScheme.error else fg)
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        ) {
            ed.base?.pdf?.let { pdf ->
                Banner("Document scanné") {
                    TextButton(onClick = { vm.showPdf(pdf) }) { Text("Voir le PDF") }
                }
            }
            if (ed.attachments.isNotEmpty() || vm.attaching) {
                Attachments(vm, ed.attachments, vm.attaching, fg)
            }
            if (ed.conflict) {
                Banner("Copie de conflit créée par la synchronisation") {
                    TextButton(onClick = { vm.keepConflictCopy() }) { Text("Garder celle-ci") }
                    TextButton(onClick = { vm.deleteEditorNote() }) { Text("Supprimer") }
                }
            }

            val titleFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { if (ed.focusTitle) { titleFocus.requestFocus(); ed.focusTitle = false } }
            BasicTextField(
                value = ed.title,
                onValueChange = { ed.title = it.replace("\n", " ") },
                textStyle = MaterialTheme.typography.titleLarge.copy(color = fg),
                cursorBrush = cursor,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
                modifier = Modifier.fillMaxWidth().focusRequester(titleFocus),
                decorationBox = { inner ->
                    Box(Modifier.padding(vertical = 8.dp)) {
                        if (ed.title.isEmpty()) Text("Titre", style = MaterialTheme.typography.titleLarge, color = hint)
                        inner()
                    }
                },
            )

            if (!isEvent) NoteDateChip(ed, fg) { showDate = true }

            if (ed.items.isEmpty() || ed.text.text.isNotBlank()) {
                BasicTextField(
                    value = ed.text,
                    onValueChange = { ed.text = it },
                        textStyle = textStyle,
                    cursorBrush = cursor,
                    visualTransformation = transformation,
                    modifier = Modifier.fillMaxWidth().onFocusChanged { textFocused = it.isFocused },
                    decorationBox = { inner ->
                        Box(Modifier.padding(vertical = 4.dp)) {
                            if (ed.text.text.isEmpty()) Text("Note", style = textStyle, color = hint)
                            inner()
                        }
                    },
                )
            }

            // Checklist: unchecked items first (draggable), checked ones folded below.
            val unchecked = ed.items.filter { !it.checked }
            val checked = ed.items.filter { it.checked }
            unchecked.forEach { item ->
                key(item.id) {
                    val itemFocus = remember { FocusRequester() }
                    LaunchedEffect(ed.pendingFocusId) {
                        if (ed.pendingFocusId == item.id) { itemFocus.requestFocus(); ed.pendingFocusId = null }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .zIndex(if (dragId == item.id) 1f else 0f)
                            .graphicsLayer { translationY = if (dragId == item.id) dragOffset else 0f }
                            .onGloballyPositioned { heights[item.id] = it.size.height },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        run {
                            Icon(
                                Icons.Default.DragHandle, "Déplacer",
                                tint = fg.copy(alpha = 0.5f),
                                modifier = Modifier.size(24.dp).pointerInput(item.id) {
                                    detectDragGestures(
                                        onDragStart = { dragId = item.id; dragOffset = 0f },
                                        onDragEnd = { dragId = null; dragOffset = 0f },
                                        onDragCancel = { dragId = null; dragOffset = 0f },
                                    ) { change, amount ->
                                        change.consume()
                                        dragOffset += amount.y
                                        val order = ed.items.filter { !it.checked }.map { it.id }
                                        val idx = order.indexOf(item.id)
                                        if (dragOffset > 0 && idx in 0 until order.lastIndex) {
                                            val h = heights[order[idx + 1]] ?: 0
                                            if (h > 0 && dragOffset > h / 2f) { moveUnchecked(item.id, 1); dragOffset -= h }
                                        } else if (dragOffset < 0 && idx > 0) {
                                            val h = heights[order[idx - 1]] ?: 0
                                            if (h > 0 && -dragOffset > h / 2f) { moveUnchecked(item.id, -1); dragOffset += h }
                                        }
                                    }
                                },
                            )
                        }
                        Checkbox(
                            checked = false,
                            onCheckedChange = { updateItem(item.id) { it.copy(checked = true) } },
                            colors = CheckboxDefaults.colors(uncheckedColor = fg.copy(alpha = 0.7f)),
                        )
                        BasicTextField(
                            value = item.text,
                            onValueChange = { v -> updateItem(item.id) { it.copy(text = v.replace("\n", " ")) } },
                                        textStyle = textStyle,
                            cursorBrush = cursor,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                            keyboardActions = KeyboardActions(onNext = { addItemAfter(item.id) }),
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(itemFocus)
                                .onPreviewKeyEvent { e ->
                                    if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace && item.text.isEmpty()) {
                                        removeItem(item.id, focusPrevious = true); true
                                    } else false
                                },
                        )
                        run {
                            IconButton(onClick = { removeItem(item.id, focusPrevious = false) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Close, "Supprimer l'élément", tint = fg.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            if (ed.items.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().clickable { addItemAfter(null) }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Add, null, tint = fg.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 12.dp))
                    Text("Ajouter un élément", color = fg.copy(alpha = 0.6f))
                }
            }
            if (checked.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().clickable { ed.checkedExpanded = !ed.checkedExpanded }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (ed.checkedExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null,
                        tint = fg.copy(alpha = 0.6f), modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    Text(
                        if (checked.size == 1) "1 élément coché" else "${checked.size} éléments cochés",
                        color = fg.copy(alpha = 0.6f),
                    )
                }
                if (ed.checkedExpanded) {
                    checked.forEach { item ->
                        key(item.id) {
                            Row(Modifier.fillMaxWidth().alpha(0.6f), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = true,
                                    onCheckedChange = { updateItem(item.id) { it.copy(checked = false) } },
                                    modifier = Modifier.padding(start = 24.dp),
                                )
                                Text(item.text, style = textStyle.copy(textDecoration = TextDecoration.LineThrough))
                            }
                        }
                    }
                }
            }

            if (ed.after.isNotBlank()) {
                BasicTextField(
                    value = ed.after,
                    onValueChange = { ed.after = it },
                        textStyle = textStyle.copy(color = fg.copy(alpha = 0.7f)),
                    cursorBrush = cursor,
                    visualTransformation = transformation,
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                )
            }

            val urls = findUrls(ed.text.text + "\n" + ed.items.joinToString("\n") { it.text } + "\n" + ed.after)
            if (urls.isNotEmpty()) {
                val uriHandler = LocalUriHandler.current
                Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    urls.forEach { url ->
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = fg.copy(alpha = 0.1f),
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable {
                                runCatching { uriHandler.openUri(url) }
                            },
                        ) {
                            Text(
                                url.removePrefix("https://").removePrefix("http://"),
                                maxLines = 1, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                }
            }
            Box(Modifier.size(48.dp))
        }
    }

    if (showColors) {
        AlertDialog(
            onDismissRequest = { showColors = false },
            confirmButton = { TextButton(onClick = { showColors = false }) { Text("OK") } },
            title = { Text("Couleur") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    NoteColors.chunked(4).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { c ->
                                val dark = isDarkTheme()
                                val fill = when {
                                    c.id == DEFAULT_COLOR -> MaterialTheme.colorScheme.surfaceContainer
                                    dark -> c.dark
                                    else -> c.light
                                }
                                Box(
                                    Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(fill)
                                        .border(
                                            if (ed.color == c.id) 3.dp else 1.dp,
                                            if (ed.color == c.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                            CircleShape,
                                        )
                                        .clickable { ed.color = c.id },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (ed.color == c.id) Icon(Icons.Default.Check, c.label, tint = if (dark) Color.White else Color.Black.copy(alpha = 0.7f))
                                }
                            }
                        }
                    }
                }
            },
        )
    }

    if (showLabels) {
        var newLabel by remember { mutableStateOf("") }
        val all = (vm.allLabels() + ed.labels).distinct()
        AlertDialog(
            onDismissRequest = { showLabels = false },
            confirmButton = { TextButton(onClick = { showLabels = false }) { Text("OK") } },
            title = { Text("Libellés") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    all.forEach { l ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                ed.labels = if (l in ed.labels) ed.labels - l else ed.labels + l
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = l in ed.labels, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                            Text(l)
                        }
                    }
                    OutlinedTextField(
                        value = newLabel,
                        onValueChange = { newLabel = it.replace("\n", "") },
                        placeholder = { Text("Nouveau libellé") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            val v = newLabel.trim()
                            if (v.isNotEmpty() && v !in ed.labels) ed.labels = ed.labels + v
                            newLabel = ""
                        }),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
        )
    }

    if (showDate) NoteDateDialog(vm, ed) { showDate = false }

    if (ed.externalConflict) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Note modifiée ailleurs") },
            text = { Text("Ce fichier a changé sur le disque (synchronisation ou édition sur PC) pendant que tu le modifiais.") },
            confirmButton = { TextButton(onClick = { vm.resolveExternal(keepMine = true) }) { Text("Garder ma version") } },
            dismissButton = { TextButton(onClick = { vm.resolveExternal(keepMine = false) }) { Text("Recharger") } },
        )
    }
}

@Composable
private fun FormatButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(40.dp)) { Icon(icon, label) }
}

@Composable
private fun ToolButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, label) }
}

@Composable
private fun Banner(text: String, actions: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            Row { actions() }
        }
    }
}
