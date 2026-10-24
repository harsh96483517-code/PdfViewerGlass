package com.example.pdfglass

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ScannerVm(app: Application) : AndroidViewModel(app) {
    var pages by mutableStateOf(listOf<ScanPage>()); private set
    var retake by mutableStateOf<Long?>(null)
    var busy by mutableStateOf(false); private set
    var progress by mutableFloatStateOf(0f); private set
    var status by mutableStateOf<String?>(null); private set
    var statusOk by mutableStateOf(false); private set
    private val dir = File(app.cacheDir, "scans").apply { mkdirs() }

    fun newFile() = File(dir, "scan_${System.nanoTime()}.jpg")
    fun onCaptured(f: File) {
        val r = retake
        pages = if (r != null) pages.map { if (it.id == r) { it.file.delete(); it.copy(file = f, corners = FULL_CROP, rot = 0) } else it }
        else pages + ScanPage(System.nanoTime(), f, filter = pages.lastOrNull()?.filter ?: ScanFilter.Enhance)
        retake = null
    }
    fun update(id: Long, f: (ScanPage) -> ScanPage) { pages = pages.map { if (it.id == id) f(it) else it } }
    fun setAllFilters(fl: ScanFilter) { pages = pages.map { it.copy(filter = fl) } }
    fun remove(id: Long) { pages.firstOrNull { it.id == id }?.file?.delete(); pages = pages.filter { it.id != id } }
    fun move(i: Int, d: Int) {
        val j = i + d
        if (j !in pages.indices) return
        pages = pages.toMutableList().apply { add(j, removeAt(i)) }
    }

    fun exportPdf(out: Uri) { val snap = pages; export(out) { app -> Scan.exportPdf(app, snap, out) { progress = it }; "Saved PDF (${snap.size} page(s))." } }
    fun exportImage(id: Long, out: Uri) { val p = pages.first { it.id == id }; export(out) { app -> Scan.exportJpeg(app, p, out); "Saved image." } }

    private fun export(out: Uri, block: (Application) -> String) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            busy = true; progress = 0f; status = null
            try {
                status = withContext(Dispatchers.IO) {
                    try { block(app) } catch (e: Exception) { runCatching { DocumentsContract.deleteDocument(app.contentResolver, out) }; throw e }
                }
                statusOk = true
            } catch (e: Exception) { status = "Failed: ${e.message}"; statusOk = false } finally { busy = false }
        }
    }

    override fun onCleared() { dir.deleteRecursively() }
}

@Composable
fun ScannerScreen(vm: ScannerVm = viewModel(), back: () -> Unit) {
    var stage by remember { mutableIntStateOf(if (vm.pages.isEmpty()) 0 else 1) }   // 0 camera, 1 review, 2 edit
    var editId by remember { mutableStateOf<Long?>(null) }
    when (stage) {
        0 -> {
            val toReview = { vm.retake = null; stage = 1 }
            BackHandler(enabled = vm.pages.isNotEmpty()) { toReview() }
            CameraStage(vm, onReview = toReview, onClose = { if (vm.pages.isNotEmpty()) toReview() else back() })
        }
        1 -> ReviewStage(vm, addPage = { vm.retake = null; stage = 0 }, edit = { editId = it; stage = 2 },
            retake = { vm.retake = it; stage = 0 }, back = back)
        else -> {
            val p = vm.pages.firstOrNull { it.id == editId }
            if (p != null) EditStage(vm, p) { stage = 1 } else LaunchedEffect(Unit) { stage = 1 }
        }
    }
}

@Composable
private fun CameraStage(vm: ScannerVm, onReview: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val g = LocalGlass.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val req = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) req.launch(Manifest.permission.CAMERA) }
    var msg by remember { mutableStateOf<String?>(null) }
    var shooting by remember { mutableStateOf(false) }
    val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build() }
    DisposableEffect(Unit) { onDispose { runCatching { ProcessCameraProvider.getInstance(ctx).get().unbindAll() } } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text(if (vm.retake != null) "Retake page" else "Scan Document", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            if (vm.pages.isNotEmpty()) TextButton(onClick = onReview) { Text("Done (${vm.pages.size})") }
        }
        if (!granted) {
            GlassCard(Modifier.fillMaxWidth().padding(20.dp)) {
                Text("Camera permission is needed to scan. If you denied it permanently, enable it in system Settings > Apps.", color = g.subtext)
                Button(onClick = { req.launch(Manifest.permission.CAMERA) }, Modifier.padding(top = 12.dp)) { Text("Allow camera") }
            }
        } else {
            AndroidView(factory = { c ->
                PreviewView(c).also { pv ->
                    val fut = ProcessCameraProvider.getInstance(c)
                    fut.addListener({
                        runCatching {
                            val prov = fut.get()
                            val pre = Preview.Builder().build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                            prov.unbindAll()
                            prov.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, pre, capture)
                        }.onFailure { msg = "Camera unavailable: ${it.message}" }
                    }, ContextCompat.getMainExecutor(c))
                }
            }, modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(24.dp)))
            Box(Modifier.fillMaxWidth().padding(16.dp), Alignment.Center) {
                Box(Modifier.size(72.dp).clip(CircleShape).background(Color.White).border(4.dp, g.accent, CircleShape)
                    .clickable(enabled = !shooting) {
                        shooting = true
                        val f = vm.newFile(); val wasRetake = vm.retake != null
                        capture.takePicture(ImageCapture.OutputFileOptions.Builder(f).build(), ContextCompat.getMainExecutor(ctx),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(r: ImageCapture.OutputFileResults) { shooting = false; vm.onCaptured(f); if (wasRetake) onReview() }
                                override fun onError(e: ImageCaptureException) { shooting = false; msg = "Capture failed: ${e.message}" }
                            })
                    })
            }
        }
        msg?.let { Text(it, Modifier.padding(horizontal = 20.dp), color = Color(0xFFFF6B6B)) }
    }
}

@Composable
private fun ReviewStage(vm: ScannerVm, addPage: () -> Unit, edit: (Long) -> Unit, retake: (Long) -> Unit, back: () -> Unit) {
    val ctx = LocalContext.current
    val g = LocalGlass.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { u -> if (u != null) vm.exportPdf(u) }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Scanned pages", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = addPage) { Text("Add page") }
            ScanFilter.entries.forEach { f -> FilterChip(vm.pages.isNotEmpty() && vm.pages.all { it.filter == f }, { vm.setAllFilters(f) }, { Text(f.label) }) }
        }
        if (vm.pages.isEmpty()) Text("No pages yet. Tap “Add page” to scan.", color = g.subtext)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(vm.pages, key = { _, p -> p.id }) { i, p ->
                val thumb by produceState<Bitmap?>(null, p) { value = withContext(Dispatchers.IO) { runCatching { Scan.render(ctx, p, 360) }.getOrNull() } }
                GlassCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(width = 72.dp, height = 96.dp).clip(RoundedCornerShape(10.dp))) {
                            thumb?.let { Image(it.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                        }
                        Column(Modifier.padding(start = 12.dp)) {
                            Text("Page ${i + 1}", fontWeight = FontWeight.SemiBold)
                            Row {
                                IconButton({ edit(p.id) }, Modifier.size(40.dp)) { Icon(Icons.Default.Crop, "Crop and filter") }
                                IconButton({ retake(p.id) }, Modifier.size(40.dp)) { Icon(Icons.Default.CameraAlt, "Retake") }
                                IconButton({ vm.move(i, -1) }, Modifier.size(40.dp), enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                                IconButton({ vm.move(i, 1) }, Modifier.size(40.dp), enabled = i < vm.pages.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                                IconButton({ vm.remove(p.id) }, Modifier.size(40.dp)) { Icon(Icons.Default.Delete, "Remove") }
                            }
                        }
                    }
                }
            }
        }
        if (vm.busy) LinearProgressIndicator(progress = { vm.progress }, Modifier.fillMaxWidth())
        vm.status?.let { Text(it, color = if (vm.statusOk) g.accent else Color(0xFFFF6B6B)) }
        Button(onClick = { save.launch("Scan_${System.currentTimeMillis()}.pdf") }, enabled = vm.pages.isNotEmpty() && !vm.busy,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) { Text("Save as PDF") }
    }
}

@Composable
private fun EditStage(vm: ScannerVm, page: ScanPage, done: () -> Unit) {
    val ctx = LocalContext.current
    val g = LocalGlass.current
    val bmp by produceState<Bitmap?>(null, page.file) { value = withContext(Dispatchers.IO) { PdfWriter.decode(ctx, Uri.fromFile(page.file), 1400) } }
    var corners by remember(page.id) { mutableStateOf(page.corners) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val commit = { vm.update(page.id) { it.copy(corners = corners) } }
    val saveImg = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { u -> if (u != null) vm.exportImage(page.id, u) }
    BackHandler { commit(); done() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { commit(); done() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("Drag the corners to crop", style = MaterialTheme.typography.titleMedium)
        }
        Box(Modifier.weight(1f).fillMaxWidth(), Alignment.Center) {
            val b = bmp
            if (b == null) CircularProgressIndicator() else
                Box(Modifier.aspectRatio(b.width.toFloat() / b.height).onSizeChanged { box = it }.pointerInput(page.id) {
                    var active = -1
                    detectDragGestures(
                        onDragStart = { o ->
                            val d = corners.map { (Offset(it.x * box.width, it.y * box.height) - o).getDistance() }
                            val m = d.withIndex().minByOrNull { it.value }
                            active = if (m != null && m.value < 100f) m.index else -1
                        },
                        onDrag = { ch, delta ->
                            if (active >= 0 && box.width > 0) {
                                ch.consume()
                                val c = corners.toMutableList(); val o = c[active]
                                c[active] = Offset((o.x + delta.x / box.width).coerceIn(0f, 1f), (o.y + delta.y / box.height).coerceIn(0f, 1f))
                                corners = c
                            }
                        },
                        onDragEnd = { active = -1 }, onDragCancel = { active = -1 })
                }) {
                    Image(b.asImageBitmap(), "Page to crop", Modifier.fillMaxSize())
                    Canvas(Modifier.fillMaxSize()) {
                        val pts = corners.map { Offset(it.x * this.size.width, it.y * this.size.height) }
                        val path = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) }; close() }
                        drawPath(path, g.accent, style = Stroke(4f))
                        pts.forEach { drawCircle(g.accent, 20f, it); drawCircle(Color.White, 8f, it) }
                    }
                }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScanFilter.entries.forEach { f -> FilterChip(page.filter == f, { vm.update(page.id) { it.copy(filter = f) } }, { Text(f.label) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.update(page.id) { it.copy(rot = (it.rot + 90) % 360) } }) { Text("Rotate ${page.rot}°") }
            OutlinedButton(onClick = { corners = FULL_CROP }) { Text("Reset crop") }
            OutlinedButton(onClick = { commit(); saveImg.launch("Scan_${System.currentTimeMillis()}.jpg") }, enabled = !vm.busy) { Text("Save image") }
            Button(onClick = { commit(); done() }) { Text("Done") }
        }
        vm.status?.let { Text(it, color = if (vm.statusOk) g.accent else Color(0xFFFF6B6B)) }
    }
}
