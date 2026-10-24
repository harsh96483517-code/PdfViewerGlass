package com.example.pdfglass

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.collectLatest
import java.io.IOException

class ReaderVm : ViewModel() {
    // PdfRenderer allows only one open page at a time, so every access goes through this lock.
    private val lock = Mutex()
    private var renderer: PdfRenderer? = null
    private var pfd: ParcelFileDescriptor? = null
    private val cache = object : LruCache<String, Bitmap>(64 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    var name by mutableStateOf<String?>(null); private set
    var pageCount by mutableIntStateOf(0); private set
    var errorMsg by mutableStateOf<String?>(null); private set
    var loading by mutableStateOf(false); private set
    var continuous by mutableStateOf(true)
    var uriStr: String? = null; private set
    var size = 0L; private set
    var startPage = 0; private set

    fun open(ctx: Context, uri: Uri, startPage: Int = 0) {
        val app = ctx.applicationContext
        this.startPage = startPage; uriStr = uri.toString()
        name = null; pageCount = 0; errorMsg = null; loading = true
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) {
                lock.withLock {
                    runCatching {
                        closeInternal()
                        val p = app.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open file")
                        try { renderer = PdfRenderer(p); pfd = p } catch (e: Throwable) { p.close(); throw e }
                        val count = renderer!!.pageCount
                        if (count == 0) throw IOException("empty")
                        docMeta(app, uri) to count
                    }.onFailure { closeInternal() }
                }
            }
            r.onSuccess { (m, c) -> name = m.first; size = m.second; pageCount = c }
                .onFailure {
                    errorMsg = when (it) {
                        is SecurityException -> "This PDF is password-protected. Password support comes in a later stage."
                        is IOException -> "This file isn't a valid PDF, has no pages, or is damaged."
                        else -> "Couldn't open the PDF: ${it.message}"
                    }
                }
            loading = false
        }
    }

    suspend fun render(index: Int, width: Int): Bitmap? {
        val key = "$index@$width"
        cache.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            lock.withLock {
                cache.get(key) ?: runCatching {
                    val rd = renderer ?: return@runCatching null
                    rd.openPage(index).use { page ->
                        val h = (width.toLong() * page.height / page.width).toInt().coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp
                    }
                }.getOrNull()?.also { cache.put(key, it) }
            }
        }
    }

    private fun closeInternal() {
        cache.evictAll()
        runCatching { renderer?.close() }; renderer = null
        runCatching { pfd?.close() }; pfd = null
    }

    fun close() {
        name = null; pageCount = 0; errorMsg = null
        viewModelScope.launch(Dispatchers.IO) { lock.withLock { closeInternal() } }
    }

    override fun onCleared() { runBlocking { lock.withLock { closeInternal() } } }
}

@Composable
fun ReaderScreen(vm: ReaderVm, lib: LibraryVm, back: () -> Unit) {
    val ctx = LocalContext.current
    val g = LocalGlass.current
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            vm.open(ctx, u, lib.pageOf(u.toString()))
        }
    }
    val pick = { open.launch(arrayOf("application/pdf")) }
    DisposableEffect(Unit) { onDispose { vm.close() } }
    LaunchedEffect(vm.name) {
        val n = vm.name; val u = vm.uriStr
        if (n != null && u != null) lib.touch(u, n, vm.size)
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(vm.name ?: "PDF Reader", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = pick) { Text("Open") }
        }
        when {
            vm.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
            vm.pageCount > 0 -> Viewer(vm) { p -> vm.uriStr?.let { lib.savePage(it, p) } }
            else -> Column(Modifier.padding(20.dp)) {
                GlassCard(Modifier.fillMaxWidth()) {
                    Text(vm.errorMsg ?: "Open a PDF from your device to start reading.",
                        color = if (vm.errorMsg != null) Color(0xFFFF6B6B) else g.subtext)
                    Button(onClick = pick, Modifier.padding(top = 12.dp)) { Text("Open PDF") }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Viewer(vm: ReaderVm, onPage: (Int) -> Unit) {
    val g = LocalGlass.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val scroll = rememberScrollState()
    var scale by remember { mutableFloatStateOf(1f) }
    var offX by remember { mutableFloatStateOf(0f) }
    var single by remember { mutableIntStateOf(vm.startPage.coerceIn(0, vm.pageCount - 1)) }
    var goDialog by remember { mutableStateOf(false) }
    val continuous = vm.continuous
    val current = if (continuous) listState.firstVisibleItemIndex else single
    LaunchedEffect(Unit) {
        snapshotFlow { if (vm.continuous) listState.firstVisibleItemIndex else single }.collectLatest { delay(600); onPage(it) }
    }

    fun fit() { scale = 1f; offX = 0f }
    fun goTo(i: Int) {
        fit()
        if (continuous) scope.launch { listState.animateScrollToItem(i) }
        else { single = i; scope.launch { scroll.scrollTo(0) } }
    }
    LaunchedEffect(continuous) { if (continuous) listState.scrollToItem(single) }

    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        FilterChip(continuous, { if (!continuous) { fit(); vm.continuous = true } else Unit }, { Text("Scroll") })
        FilterChip(!continuous, { if (continuous) { single = listState.firstVisibleItemIndex; fit(); vm.continuous = false } }, { Text("Page") })
        Spacer(Modifier.weight(1f))
        TextButton(onClick = ::fit, enabled = scale != 1f) { Text("Fit width") }
    }

    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val wPx = with(LocalDensity.current) { maxWidth.toPx() }.toInt()
        val tState = rememberTransformableState { zoom, pan, _ ->
            val ns = (scale * zoom).coerceIn(1f, 4f)
            val lim = (ns - 1f) * wPx / 2f
            scale = ns
            offX = (offX + pan.x).coerceIn(-lim, lim)
        }
        Box(Modifier.fillMaxSize().clipToBounds().transformable(tState, canPan = { scale > 1f })) {
            val layer = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = offX,
                transformOrigin = TransformOrigin(0.5f, 0f))
            if (continuous) {
                LazyColumn(layer, state = listState, contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(vm.pageCount) { PageImage(vm, it, wPx, scale) }
                }
            } else {
                Column(layer.verticalScroll(scroll).padding(12.dp)) { PageImage(vm, single, wPx, scale) }
            }
        }
    }

    val shape = RoundedCornerShape(28.dp)
    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(shape)
        .background(g.glass).border(1.dp, g.border, shape).padding(4.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { goTo(current - 1) }, enabled = current > 0) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Previous page") }
        TextButton(onClick = { goDialog = true }) { Text("${current + 1} / ${vm.pageCount}") }
        IconButton(onClick = { goTo(current + 1) }, enabled = current < vm.pageCount - 1) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Next page") }
    }

    if (goDialog) {
        var txt by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { goDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    txt.toIntOrNull()?.let { goTo((it - 1).coerceIn(0, vm.pageCount - 1)) }
                    goDialog = false
                }) { Text("Go") }
            },
            text = {
                OutlinedTextField(txt, { txt = it.filter(Char::isDigit) }, singleLine = true,
                    label = { Text("Page (1–${vm.pageCount})") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            })
    }
}

@Composable
private fun PageImage(vm: ReaderVm, index: Int, wPx: Int, scale: Float) {
    val rs = if (scale > 1.2f) 2 else 1   // re-render sharper when zoomed (capped to bound memory)
    val bmp by produceState<Bitmap?>(null, index, wPx, rs) { value = vm.render(index, wPx * rs) }
    val b = bmp
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.White)
        .aspectRatio(if (b == null) 0.707f else b.width.toFloat() / b.height)) {
        if (b != null) Image(b.asImageBitmap(), "Page ${index + 1}", Modifier.fillMaxSize())
        else CircularProgressIndicator(Modifier.align(Alignment.Center))
    }
}
