package com.example.pdfglass

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(applicationContext)
        setContent { App() }
    }
}

@Composable
fun App() {
    val ctx = LocalContext.current
    val prefs by ctx.store.data.collectAsState(initial = emptyPreferences())
    val theme = ThemeChoice.entries.firstOrNull { it.name == prefs[THEME_KEY] } ?: ThemeChoice.Ocean
    val mode = Mode.entries.firstOrNull { it.name == prefs[MODE_KEY] } ?: Mode.System
    LiquidGlassTheme(theme, mode) { Root(theme, mode) }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)
private val tabs = listOf(
    Tab("home", "Home", Icons.Default.Home), Tab("files", "Files", Icons.Default.Folder),
    Tab("favorites", "Favorites", Icons.Default.Favorite), Tab("settings", "Settings", Icons.Default.Settings)
)

@Composable
fun Root(theme: ThemeChoice, mode: Mode) {
    val nav = rememberNavController()
    val ctx = LocalContext.current
    val readerVm: ReaderVm = viewModel()
    val lib: LibraryVm = viewModel()
    val openDoc: (Doc) -> Unit = { d -> readerVm.open(ctx, Uri.parse(d.uri), d.page); nav.navigate("reader") }
    val route = nav.currentBackStackEntryAsState().value?.destination?.route
    AppBackground {
        Scaffold(
            containerColor = Color.Transparent, contentColor = LocalGlass.current.text,
            bottomBar = {
                if (tabs.any { it.route == route })
                    GlassNav(route) { r -> nav.navigate(r) { popUpTo("home"); launchSingleTop = true } }
            }
        ) { pad ->
            NavHost(nav, startDestination = "home", modifier = Modifier.padding(pad)) {
                composable("home") {
                    HomeScreen(lib, { nav.navigate("reader") }, { nav.navigate("img2pdf") }, { nav.navigate("tools") }, { nav.navigate("scanner") }, openDoc,
                        { nav.navigate("files") { popUpTo("home"); launchSingleTop = true } })
                }
                composable("reader") { ReaderScreen(readerVm, lib, back = { nav.popBackStack() }) }
                composable("tools") { ToolsScreen(back = { nav.popBackStack() }) }
                composable("scanner") { ScannerScreen(back = { nav.popBackStack() }) }
                composable("files") { FilesScreen(lib, false, openDoc) }
                composable("favorites") { FilesScreen(lib, true, openDoc) }
                composable("settings") { SettingsScreen(theme, mode) }
                composable("img2pdf") { ImageToPdfScreen(back = { nav.popBackStack() }) }
            }
        }
    }
}

@Composable
private fun GlassNav(current: String?, go: (String) -> Unit) {
    val g = LocalGlass.current
    val shape = RoundedCornerShape(28.dp)
    Row(
        Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()
            .clip(shape).background(g.glass).border(1.dp, g.border, shape).padding(6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        tabs.forEach { t ->
            val sel = t.route == current
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(22.dp))
                    .background(if (sel) g.accent.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { go(t.route) }.padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(t.icon, t.label, tint = if (sel) g.accent else g.subtext)
                Text(t.label, color = if (sel) g.accent else g.subtext, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun HomeScreen(lib: LibraryVm, openReader: () -> Unit, openImageToPdf: () -> Unit, openTools: () -> Unit, openScanner: () -> Unit, openDoc: (Doc) -> Unit, seeAll: () -> Unit) {
    val g = LocalGlass.current
    val docs by lib.docs.collectAsState()
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Hello 👋", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("What would you like to do?", color = g.subtext)
        GlassCard(Modifier.fillMaxWidth(), onClick = openReader) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Description, null, tint = g.accent, modifier = Modifier.size(36.dp))
                Column(Modifier.padding(start = 16.dp)) {
                    Text("PDF Reader", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Open, scroll, zoom and jump to any page", color = g.subtext,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        GlassCard(Modifier.fillMaxWidth(), onClick = openImageToPdf) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Image, null, tint = g.accent, modifier = Modifier.size(36.dp))
                Column(Modifier.padding(start = 16.dp)) {
                    Text("Image to PDF", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Pick photos, reverse or reorder, choose a font", color = g.subtext,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        GlassCard(Modifier.fillMaxWidth(), onClick = openTools) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Build, null, tint = g.accent, modifier = Modifier.size(36.dp))
                Column(Modifier.padding(start = 16.dp)) {
                    Text("PDF Tools", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Merge, split, rotate, compress, page numbers, password", color = g.subtext, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        GlassCard(Modifier.fillMaxWidth(), onClick = openScanner) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CameraAlt, null, tint = g.accent, modifier = Modifier.size(36.dp))
                Column(Modifier.padding(start = 16.dp)) {
                    Text("Scan Document", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("Camera, manual crop, filters, save as PDF", color = g.subtext, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        docs.firstOrNull { it.page > 0 }?.let { d ->
            GlassCard(Modifier.fillMaxWidth(), onClick = { openDoc(d) }) {
                Text("Continue reading", color = g.accent, style = MaterialTheme.typography.labelLarge)
                Text(d.name, maxLines = 1, fontWeight = FontWeight.SemiBold)
                Text("Page ${d.page + 1}", color = g.subtext, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (docs.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent files", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = seeAll) { Text("See all") }
            }
            docs.take(4).forEach { DocRow(it, lib, openDoc) }
        }
        Text("More tools (reader, scanner, merge, split…) are added stage by stage.", color = g.subtext,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun SettingsScreen(theme: ThemeChoice, mode: Mode) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val g = LocalGlass.current
    Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Appearance", color = g.subtext)
        GlassCard(Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mode.entries.forEach { m ->
                    FilterChip(selected = m == mode, label = { Text(m.name) },
                        onClick = { scope.launch { ctx.store.edit { it[MODE_KEY] = m.name } } })
                }
            }
        }
        Text("Theme", color = g.subtext)
        GlassCard(Modifier.fillMaxWidth()) {
            ThemeChoice.entries.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { t ->
                        Column(Modifier.clickable { scope.launch { ctx.store.edit { it[THEME_KEY] = t.name } } },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(64.dp).clip(CircleShape)
                                .background(Brush.linearGradient(listOf(t.bottom, t.accent)))
                                .border(if (t == theme) 3.dp else 1.dp, if (t == theme) g.accent else g.border, CircleShape))
                            Text(t.label, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun Soon(title: String, note: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(note, color = LocalGlass.current.subtext, modifier = Modifier.padding(top = 8.dp))
    }
}
