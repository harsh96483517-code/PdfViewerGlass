package com.example.pdfglass

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class SortBy { Date, Name, Size }

private fun share(ctx: Context, d: Doc) {
    val i = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, Uri.parse(d.uri)); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { ctx.startActivity(Intent.createChooser(i, "Share PDF")) }
}

@Composable
fun FilesScreen(lib: LibraryVm, favoritesOnly: Boolean, openDoc: (Doc) -> Unit) {
    val ctx = LocalContext.current
    val g = LocalGlass.current
    val docs by lib.docs.collectAsState()
    var q by remember { mutableStateOf("") }
    var sort by remember { mutableStateOf(SortBy.Date) }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { lib.import(ctx, it) }
    val shown = remember(docs, q, sort, favoritesOnly) {
        docs.filter { (!favoritesOnly || it.fav) && it.name.contains(q.trim(), ignoreCase = true) }.let { l ->
            when (sort) {
                SortBy.Date -> l.sortedByDescending { it.opened }
                SortBy.Name -> l.sortedBy { it.name.lowercase() }
                SortBy.Size -> l.sortedByDescending { it.size }
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (favoritesOnly) "Favorites" else "Files", Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            if (!favoritesOnly) Button(onClick = { import.launch(arrayOf("application/pdf")) }) { Text("Import") }
        }
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search by filename") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Sort", color = g.subtext)
            SortBy.entries.forEach { s -> FilterChip(sort == s, { sort = s }, { Text(s.name) }) }
        }
        if (shown.isEmpty()) {
            GlassCard(Modifier.fillMaxWidth()) {
                Text(
                    when {
                        q.isNotBlank() -> "No files match “$q”."
                        favoritesOnly -> "No favorites yet. Tap the heart on any file."
                        else -> "No files yet. Import PDFs, or open one in the reader and it will appear here."
                    }, color = g.subtext
                )
            }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
            items(shown, key = { it.uri }) { DocRow(it, lib, openDoc) }
        }
    }
}

@Composable
fun DocRow(d: Doc, lib: LibraryVm, open: (Doc) -> Unit) {
    val ctx = LocalContext.current
    val g = LocalGlass.current
    var menu by remember { mutableStateOf(false) }
    GlassCard(Modifier.fillMaxWidth(), onClick = { open(d) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Description, null, tint = g.accent)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(d.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                val ago = DateUtils.getRelativeTimeSpanString(d.opened, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                Text("${Formatter.formatShortFileSize(ctx, d.size)} • $ago", color = g.subtext, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = { lib.toggleFav(d.uri) }) {
                Icon(if (d.fav) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                    if (d.fav) "Remove from favorites" else "Add to favorites", tint = if (d.fav) g.accent else g.subtext)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More options") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Share") }, { menu = false; share(ctx, d) })
                    DropdownMenuItem({ Text("Remove from list") }, { menu = false; lib.remove(d.uri) })
                }
            }
        }
    }
}
