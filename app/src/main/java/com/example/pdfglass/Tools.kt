package com.example.pdfglass

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.provider.DocumentsContract
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import kotlinx.coroutines.*
import java.io.IOException

enum class Tool(val label: String) { Merge("Merge"), Extract("Extract / Split"), Rotate("Rotate"), Compress("Compress"), Numbers("Page numbers"), Protect("Password") }
data class Src(val uri: Uri, val name: String, val pages: Int)
/** Options for page numbers (pos/fmt/font) and password protection. */
data class Extra(val pos: Int = 1, val fmt: Int = 0, val font: Int = 0, val pw: String = "", val allowPrint: Boolean = true, val allowCopy: Boolean = true)

/** Parses "1-3,5" (1-based) into 0-based indices, in the order typed. "5-1" gives descending order. */
fun parseRanges(s: String, max: Int): List<Int> {
    require(s.isNotBlank()) { "Enter pages, e.g. 1-3,5" }
    return s.split(',').flatMap { part ->
        val t = part.trim()
        val ab = t.split('-').map { it.trim().toInt() }
        val (a, b) = when (ab.size) { 1 -> ab[0] to ab[0]; 2 -> ab[0] to ab[1]; else -> throw IllegalArgumentException("Bad range \"$t\"") }
        require(a in 1..max && b in 1..max) { "Page out of range (1–$max): \"$t\"" }
        if (a <= b) (a - 1 until b).toList() else (a - 1 downTo b - 1).toList()
    }
}

object PdfOps {
    fun pageCount(ctx: Context, uri: Uri): Int =
        (ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IOException("Cannot open file")).use { pfd ->
            PdfRenderer(pfd).use { it.pageCount }
        }

    suspend fun run(ctx: Context, tool: Tool, files: List<Src>, ranges: String, degrees: Int, preset: Int, x: Extra, out: Uri, progress: (Float) -> Unit) {
        val r = ctx.contentResolver
        fun input(u: Uri) = r.openInputStream(u) ?: throw IOException("Cannot open input file")
        val f = files.first()
        (r.openOutputStream(out) ?: throw IOException("Cannot create output file")).use { o ->
            when (tool) {
                Tool.Merge -> {
                    require(files.size >= 2) { "Add at least two PDFs." }
                    val m = PDFMergerUtility()
                    files.forEach { m.addSource(input(it.uri)) }
                    m.destinationStream = o
                    m.mergeDocuments(MemoryUsageSetting.setupMainMemoryOnly())
                }
                Tool.Extract -> PDDocument.load(input(f.uri)).use { src ->
                    val idx = parseRanges(ranges, src.numberOfPages)
                    PDDocument().use { dst ->
                        idx.forEachIndexed { n, i ->
                            currentCoroutineContext().ensureActive()
                            val sp = src.getPage(i)
                            val np = dst.importPage(sp)
                            np.resources = sp.resources; np.mediaBox = sp.mediaBox; np.cropBox = sp.cropBox; np.rotation = sp.rotation
                            progress((n + 1f) / idx.size)
                        }
                        dst.save(o)
                    }
                }
                Tool.Rotate -> PDDocument.load(input(f.uri)).use { d ->
                    val idx = (if (ranges.isBlank()) (0 until d.numberOfPages).toList() else parseRanges(ranges, d.numberOfPages)).distinct()
                    idx.forEachIndexed { n, i ->
                        val p = d.getPage(i); p.rotation = (p.rotation + degrees) % 360
                        progress((n + 1f) / idx.size)
                    }
                    d.save(o)
                }
                Tool.Numbers -> PDDocument.load(input(f.uri)).use { d ->
                    val n = d.numberOfPages
                    val idx = (if (ranges.isBlank()) (0 until n).toList() else parseRanges(ranges, n)).distinct()
                    val font = listOf(PDType1Font.HELVETICA, PDType1Font.TIMES_ROMAN, PDType1Font.COURIER)[x.font]
                    idx.forEachIndexed { k, i ->
                        currentCoroutineContext().ensureActive()
                        val page = d.getPage(i)
                        val box = page.cropBox
                        val text = if (x.fmt == 0) "${i + 1}" else "${i + 1} / $n"
                        val tw = font.getStringWidth(text) / 1000f * 11f
                        val px = when (x.pos) { 0 -> box.lowerLeftX + 36f; 2 -> box.upperRightX - 36f - tw; else -> (box.lowerLeftX + box.upperRightX - tw) / 2f }
                        PDPageContentStream(d, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                            cs.setNonStrokingColor(0.2f, 0.2f, 0.2f)
                            cs.beginText(); cs.setFont(font, 11f)
                            cs.newLineAtOffset(px, box.lowerLeftY + 20f); cs.showText(text); cs.endText()
                        }
                        progress((k + 1f) / idx.size)
                    }
                    d.save(o)
                }
                Tool.Protect -> PDDocument.load(input(f.uri)).use { d ->
                    require(x.pw.isNotEmpty()) { "Enter a password." }
                    val ap = AccessPermission()
                    ap.setCanPrint(x.allowPrint); ap.setCanPrintDegraded(x.allowPrint); ap.setCanExtractContent(x.allowCopy)
                    // Random owner password: the file can be opened with the user password but permissions can't be changed.
                    val spp = StandardProtectionPolicy(java.util.UUID.randomUUID().toString(), x.pw, ap)
                    spp.setEncryptionKeyLength(128); spp.setPreferAES(true)
                    d.protect(spp)
                    d.save(o)
                }
                Tool.Compress -> {
                    val (scale, q) = listOf(1f to 0.5f, 1.5f to 0.7f, 2f to 0.85f)[preset]
                    (r.openFileDescriptor(f.uri, "r") ?: throw IOException("Cannot open input file")).use { pfd ->
                        PdfRenderer(pfd).use { rd ->
                            PDDocument().use { doc ->
                                for (i in 0 until rd.pageCount) {
                                    currentCoroutineContext().ensureActive()
                                    rd.openPage(i).use { pg ->
                                        val w = pg.width.toFloat(); val h = pg.height.toFloat()
                                        val bmp = Bitmap.createBitmap((w * scale).toInt(), (h * scale).toInt(), Bitmap.Config.ARGB_8888)
                                        bmp.eraseColor(Color.WHITE)
                                        pg.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                                        val page = PDPage(PDRectangle(w, h)); doc.addPage(page)
                                        val img = JPEGFactory.createFromImage(doc, bmp, q)
                                        PDPageContentStream(doc, page).use { cs -> cs.drawImage(img, 0f, 0f, w, h) }
                                        bmp.recycle()
                                    }
                                    progress((i + 1f) / rd.pageCount)
                                }
                                doc.save(o)
                            }
                        }
                    }
                }
            }
        }
        progress(1f)
    }
}

class ToolsVm(app: Application) : AndroidViewModel(app) {
    var tool by mutableStateOf(Tool.Merge); private set
    var files by mutableStateOf(listOf<Src>()); private set
    var ranges by mutableStateOf("")
    var degrees by mutableIntStateOf(90)
    var preset by mutableIntStateOf(1)
    var extra by mutableStateOf(Extra())
    var pw2 by mutableStateOf("")
    var busy by mutableStateOf(false); private set
    var progress by mutableFloatStateOf(0f); private set
    var status by mutableStateOf<String?>(null); private set
    var statusOk by mutableStateOf(false); private set
    private var job: Job? = null

    fun selectTool(t: Tool) { tool = t; status = null; extra = extra.copy(pw = ""); pw2 = ""; if (t != Tool.Merge) files = files.take(1) }
    fun reverse() { files = files.reversed() }
    fun remove(i: Int) { files = files.filterIndexed { k, _ -> k != i } }
    fun move(i: Int, d: Int) {
        val j = i + d
        if (j !in files.indices) return
        files = files.toMutableList().apply { add(j, removeAt(i)) }
    }

    fun add(uris: List<Uri>) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val res = withContext(Dispatchers.IO) { uris.map { u -> runCatching { Src(u, docMeta(app, u).first, PdfOps.pageCount(app, u)) } } }
            val ok = res.mapNotNull { it.getOrNull() }
            val bad = res.count { it.isFailure }
            files = when {
                tool == Tool.Merge -> files + ok.filter { s -> files.none { it.uri == s.uri } }
                ok.isNotEmpty() -> ok.take(1)
                else -> files
            }
            statusOk = false
            status = if (bad > 0) "$bad file(s) couldn't be read (invalid or password-protected)." else null
        }
    }

    fun run(out: Uri) {
        val app = getApplication<Application>()
        val t = tool; val fs = files; val rg = ranges; val dg = degrees; val ps = preset; val x = extra
        job = viewModelScope.launch {
            busy = true; progress = 0f; status = null
            try {
                status = withContext(Dispatchers.IO) {
                    PdfOps.run(app, t, fs, rg, dg, ps, x, out) { progress = it }
                    val after = docMeta(app, out).second
                    if (t == Tool.Compress) {
                        val before = docMeta(app, fs.first().uri).second
                        val a = Formatter.formatShortFileSize(app, after); val b = Formatter.formatShortFileSize(app, before)
                        if (after in 1 until before) "Compressed: $b → $a." else "Result ($a) isn't smaller than the original ($b). Try “Smaller”, or keep the original."
                    } else if (t == Tool.Protect) "Saved protected PDF (${Formatter.formatShortFileSize(app, after)}). Keep the password safe; it can't be recovered."
                    else "Saved (${Formatter.formatShortFileSize(app, after)})."
                }
                statusOk = true; extra = extra.copy(pw = ""); pw2 = ""
            } catch (e: CancellationException) {
                cleanup(app, out); status = "Cancelled."; statusOk = false; throw e
            } catch (e: Exception) {
                cleanup(app, out); statusOk = false
                status = when {
                    e.javaClass.simpleName == "InvalidPasswordException" -> "A file is password-protected."
                    e is NumberFormatException -> "Invalid page list. Use numbers like 1-3,5."
                    else -> e.message ?: "Operation failed."
                }
            } finally { busy = false }
        }
    }

    fun cancel() { job?.cancel() }
    private suspend fun cleanup(app: Application, out: Uri) =
        withContext(NonCancellable + Dispatchers.IO) { runCatching { DocumentsContract.deleteDocument(app.contentResolver, out) } }
}

@Composable
fun ToolsScreen(vm: ToolsVm = viewModel(), back: () -> Unit) {
    val g = LocalGlass.current
    val ctx = LocalContext.current
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.add(it) }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u -> if (u != null) vm.add(listOf(u)) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { u -> if (u != null) vm.run(u) }
    val canRun = !vm.busy && when (vm.tool) {
        Tool.Merge -> vm.files.size >= 2
        Tool.Extract -> vm.files.size == 1 && vm.ranges.isNotBlank()
        Tool.Protect -> vm.files.size == 1 && vm.extra.pw.isNotEmpty() && vm.extra.pw == vm.pw2
        else -> vm.files.size == 1
    }
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Text("PDF Tools", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tool.entries.forEach { t -> FilterChip(vm.tool == t, { if (!vm.busy) vm.selectTool(t) }, { Text(t.label) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (vm.tool == Tool.Merge) pickMany.launch(arrayOf("application/pdf")) else pickOne.launch(arrayOf("application/pdf")) },
                enabled = !vm.busy) { Text(if (vm.tool == Tool.Merge) "Add PDFs" else "Choose PDF") }
            if (vm.tool == Tool.Merge) OutlinedButton(onClick = vm::reverse, enabled = vm.files.size > 1 && !vm.busy) { Text("Reverse order") }
        }
        if (vm.files.isEmpty()) Text(if (vm.tool == Tool.Merge) "Add two or more PDFs. They are merged top to bottom." else "Choose a PDF to start.", color = g.subtext)
        vm.files.forEachIndexed { i, s ->
            GlassCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text("${s.pages} page(s)", color = g.subtext, style = MaterialTheme.typography.bodySmall)
                    }
                    if (vm.tool == Tool.Merge) {
                        IconButton(onClick = { vm.move(i, -1) }, enabled = i > 0) { Icon(Icons.Default.KeyboardArrowUp, "Move up") }
                        IconButton(onClick = { vm.move(i, 1) }, enabled = i < vm.files.lastIndex) { Icon(Icons.Default.KeyboardArrowDown, "Move down") }
                    }
                    IconButton(onClick = { vm.remove(i) }, enabled = !vm.busy) { Icon(Icons.Default.Delete, "Remove") }
                }
            }
        }
        when (vm.tool) {
            Tool.Extract -> OutlinedTextField(vm.ranges, { vm.ranges = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Pages to keep, in order") }, supportingText = { Text("e.g. 1-3,5 · 5-1 reverses · leave out pages to delete them") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
            Tool.Rotate -> GlassCard(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(90, 180, 270).forEach { d -> FilterChip(vm.degrees == d, { vm.degrees = d }, { Text("$d°") }) }
                }
                OutlinedTextField(vm.ranges, { vm.ranges = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                    label = { Text("Pages (blank = all)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
            }
            Tool.Compress -> GlassCard(Modifier.fillMaxWidth()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Smaller", "Balanced", "Better").forEachIndexed { i, l -> FilterChip(vm.preset == i, { vm.preset = i }, { Text(l) }) }
                }
                Text("Pages are re-saved as JPEG images. Text stops being selectable/searchable, and the result can be larger for text-only PDFs.",
                    color = g.subtext, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            Tool.Numbers -> GlassCard(Modifier.fillMaxWidth()) {
                val x = vm.extra
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Left", "Center", "Right").forEachIndexed { i, l -> FilterChip(x.pos == i, { vm.extra = x.copy(pos = i) }, { Text(l) }) }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("1", "1 / N").forEachIndexed { i, l -> FilterChip(x.fmt == i, { vm.extra = x.copy(fmt = i) }, { Text(l) }) }
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Helvetica", "Times", "Courier").forEachIndexed { i, l -> FilterChip(x.font == i, { vm.extra = x.copy(font = i) }, { Text(l) }) }
                }
                OutlinedTextField(vm.ranges, { vm.ranges = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                    label = { Text("Pages (blank = all)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii))
                Text("Numbers go at the bottom of each page. Pages that carry a built-in rotation may show them on a side edge.",
                    color = g.subtext, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            Tool.Protect -> GlassCard(Modifier.fillMaxWidth()) {
                val x = vm.extra
                OutlinedTextField(x.pw, { vm.extra = x.copy(pw = it) }, Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Password to open") }, visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                OutlinedTextField(vm.pw2, { vm.pw2 = it }, Modifier.fillMaxWidth().padding(top = 8.dp), singleLine = true,
                    label = { Text("Repeat password") }, visualTransformation = PasswordVisualTransformation(),
                    isError = vm.pw2.isNotEmpty() && vm.pw2 != x.pw,
                    supportingText = { if (vm.pw2.isNotEmpty() && vm.pw2 != x.pw) Text("Passwords don't match") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Allow printing", Modifier.weight(1f)); Switch(x.allowPrint, { vm.extra = x.copy(allowPrint = it) }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Allow copying text", Modifier.weight(1f)); Switch(x.allowCopy, { vm.extra = x.copy(allowCopy = it) }) }
                Text("AES-128 encryption. The password can't be recovered. Our reader can't open protected PDFs yet, so open the result in another PDF app.",
                    color = g.subtext, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            else -> {}
        }
        Button(onClick = { save.launch("${vm.tool.name}_${System.currentTimeMillis()}.pdf") }, enabled = canRun, modifier = Modifier.fillMaxWidth()) { Text("Run & save") }
        if (vm.busy) {
            LinearProgressIndicator(progress = { vm.progress }, Modifier.fillMaxWidth())
            OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
        }
        vm.status?.let { Text(it, color = if (vm.statusOk) g.accent else androidx.compose.ui.graphics.Color(0xFFFF6B6B)) }
    }
}
