package com.example.pdfglass

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PdfVm : ViewModel() {
    var images by mutableStateOf(listOf<Uri>()); private set
    var font by mutableStateOf(PdfFont.Sans)
    var pageNumbers by mutableStateOf(true)
    var caption by mutableStateOf("")
    var busy by mutableStateOf(false); private set
    var status by mutableStateOf<String?>(null); private set

    fun add(uris: List<Uri>) { images = images + uris.distinct().filter { it !in images } }
    fun reverse() { images = images.reversed() }
    fun remove(i: Int) { images = images.filterIndexed { k, _ -> k != i } }
    fun move(i: Int, d: Int) {
        val j = i + d
        if (j !in images.indices) return
        images = images.toMutableList().apply { add(j, removeAt(i)) }
    }

    fun export(ctx: Context, out: Uri) {
        val app = ctx.applicationContext
        val list = images; val f = font; val n = pageNumbers; val cap = caption
        viewModelScope.launch {
            busy = true; status = null
            status = withContext(Dispatchers.IO) {
                runCatching { PdfWriter.write(app, list, out, f, n, cap) }
                    .fold({ "Saved PDF with $it page(s)." }, { "Failed: ${it.message}" })
            }
            busy = false
        }
    }
}

@Composable
fun ImageToPdfScreen(vm: PdfVm = viewModel(), back: () -> Unit) {
    val ctx = LocalContext.current
    val g = LocalGlass.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { vm.add(it) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { u ->
        if (u != null) vm.export(ctx, u)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Text("Image to PDF", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text("Add photos")
                }
                OutlinedButton(onClick = vm::reverse, enabled = vm.images.size > 1) { Text("Reverse order") }
            }
        }
        if (vm.images.isEmpty()) item { Text("No photos yet. Tap “Add photos” to select several at once.", color = g.subtext) }
        itemsIndexed(vm.images, key = { _, u -> u.toString() }) { i, u ->
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(u, "Page ${i + 1} preview", Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop)
                    Text("Page ${i + 1}", Modifier.weight(1f).padding(start = 12.dp))
                    IconButton(onClick = { vm.move(i, -1) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                    IconButton(onClick = { vm.move(i, 1) }, enabled = i < vm.images.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                    IconButton(onClick = { vm.remove(i) }) { Icon(Icons.Default.Delete, "Remove") }
                }
            }
        }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Text("Page text font", color = g.subtext)
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PdfFont.entries.forEach { f ->
                        FilterChip(selected = f == vm.font, onClick = { vm.font = f },
                            label = { Text(f.label, fontFamily = f.compose) })
                    }
                }
                OutlinedTextField(vm.caption, { vm.caption = it }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Caption (optional)") }, textStyle = LocalTextStyle.current.copy(fontFamily = vm.font.compose))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Page numbers", Modifier.weight(1f))
                    Switch(vm.pageNumbers, { vm.pageNumbers = it })
                }
            }
        }
        item {
            Button(onClick = { save.launch("Images_${System.currentTimeMillis()}.pdf") },
                enabled = vm.images.isNotEmpty() && !vm.busy, modifier = Modifier.fillMaxWidth()) {
                if (vm.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Create PDF")
            }
            vm.status?.let { Text(it, Modifier.padding(top = 8.dp), color = g.accent) }
        }
    }
}
