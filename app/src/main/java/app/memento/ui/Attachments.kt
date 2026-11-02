package app.memento.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.memento.MainViewModel
import app.memento.isDrawing
import app.memento.isImage

private val TILE = 96.dp

/** The note's attached files, in a row at the top of the editor: images as thumbnails, other files by name. */
@Composable
fun Attachments(vm: MainViewModel, paths: List<String>, busy: Boolean, fg: Color) {
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        paths.forEach { path -> key(path) { Tile(vm, path, fg) } }
        if (busy) {
            Box(Modifier.size(TILE), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(32.dp)) }
        }
    }
}

@Composable
private fun Tile(vm: MainViewModel, path: String, fg: Color) {
    val px = with(LocalDensity.current) { TILE.roundToPx() }
    val version = vm.drawingVersion
    var thumb by remember(path, version) { mutableStateOf<Bitmap?>(null) }
    if (isImage(path) || isDrawing(path)) LaunchedEffect(path, version) { thumb = runCatching { vm.thumbnail(path, px) }.getOrNull() }
    Box(Modifier.size(TILE).clip(RoundedCornerShape(12.dp)).clickable { if (isDrawing(path)) vm.editDrawing(path) else vm.openFile(path) }) {
        val bmp = thumb
        if (bmp != null) {
            Image(bmp.asImageBitmap(), displayName(path), contentScale = ContentScale.Crop, modifier = Modifier.size(TILE))
        } else {
            Surface(color = fg.copy(alpha = 0.1f), contentColor = fg, modifier = Modifier.size(TILE)) {
                Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    val ext = path.substringAfterLast('.', "").lowercase()
                    val icon = when {
                        ext == "pdf" -> Icons.Default.PictureAsPdf
                        ext in setOf("mp4", "mov", "mkv", "webm", "3gp") -> Icons.Default.Movie
                        else -> Icons.AutoMirrored.Filled.InsertDriveFile
                    }
                    Icon(icon, null, Modifier.size(28.dp))
                    Text(
                        displayName(path), style = MaterialTheme.typography.labelSmall, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.width(TILE).padding(top = 4.dp),
                    )
                }
            }
        }
        Surface(
            shape = CircleShape, color = Color.Black.copy(alpha = 0.55f), contentColor = Color.White,
            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(24.dp).clip(CircleShape).clickable { vm.removeAttachment(path) },
        ) {
            Icon(Icons.Default.Close, "Retirer la pièce jointe", Modifier.padding(4.dp))
        }
    }
}

/** `attachments/facture-edf-k3f9a2.pdf` → `facture-edf.pdf`. */
fun displayName(path: String): String {
    val name = path.substringAfterLast('/')
    val ext = name.substringAfterLast('.', "")
    val stem = if (ext.isEmpty()) name else name.removeSuffix(".$ext")
    val clean = stem.replace(Regex("-[a-z0-9]{6}$"), "")
    return if (ext.isEmpty()) clean else "$clean.$ext"
}
