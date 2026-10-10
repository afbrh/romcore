package com.noryan.romcore.ui

import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noryan.romcore.CoreBootstrap
import com.noryan.romcore.MainViewModel
import com.noryan.romcore.R
import com.noryan.romcore.api.System
import com.noryan.romcore.data.CoreRouter
import com.noryan.romcore.data.CoreSetup
import com.noryan.romcore.data.GameEntity
import com.noryan.romcore.ui.components.FocusLine

private val TABS = listOf("GAMES", "SYSTEMS", "SETTINGS")

fun systemLabel(s: System): String = when (s) {
    System.NES -> "NES"; System.SNES -> "SNES"; System.N64 -> "N64"; System.GB -> "GameBoy"; System.GBC -> "GameBoy Color"
    System.GBA -> "GBA"; System.NDS -> "DS"; System.N3DS -> "3DS"; System.GAMECUBE -> "GameCube"; System.WII -> "Wii"
    System.WIIU -> "Wii U"; System.SWITCH -> "Switch"; System.PS1 -> "PS1"; System.PS2 -> "PS2"; System.PSP -> "PSP"
    System.GENESIS -> "Genesis"; System.MASTER_SYSTEM -> "Master System"; System.PCE -> "PC Engine"; System.ATARI -> "Atari"
    System.ARCADE -> "Arcade"; System.OTHER -> "Other"
}

internal fun openAllFilesAccess(ctx: android.content.Context) {
    runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

@Composable
private fun rememberAllFilesGranted(): State<Boolean> {
    val st = remember { mutableStateOf(Environment.isExternalStorageManager()) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val ob = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) st.value = Environment.isExternalStorageManager() }
        owner.lifecycle.addObserver(ob); onDispose { owner.lifecycle.removeObserver(ob) }
    }
    return st
}

private fun sysOf(g: GameEntity) = runCatching { System.valueOf(g.system) }.getOrNull()

@Composable
fun RomCoreRoot(vm: MainViewModel) {
    val tree by vm.treeUri.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::setFolder) }
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (tree == null) FirstRun { picker.launch(null) } else MainScreen(vm) { picker.launch(null) }
        }
    }
}

@Composable
private fun LogoMark(size: Int) {
    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(size.dp))
}

@Composable
private fun FirstRun(onPick: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        LogoMark(96)
        Text("ROMCORE", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(8.dp))
        Text("One app. Every core. One menu.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(28.dp))
        Text("Choose the folder that holds your ROMs.", style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        FocusLine("PICK ROM FOLDER", Modifier.focusRequester(fr).width(420.dp), center = true, style = MaterialTheme.typography.headlineSmall, onClick = onPick)
        val ctx = LocalContext.current
        var granted by remember { mutableStateOf(Environment.isExternalStorageManager()) }
        val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val ob = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) granted = Environment.isExternalStorageManager() }
            owner.lifecycle.addObserver(ob); onDispose { owner.lifecycle.removeObserver(ob) }
        }
        if (!granted) {
            Spacer(Modifier.height(8.dp))
            FocusLine("GRANT ALL FILES ACCESS", Modifier.width(420.dp), center = true, style = MaterialTheme.typography.titleMedium) { openAllFilesAccess(ctx) }
            Text("Lets every core open ROMs by real path (PS2, 3DS need it).", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun MainScreen(vm: MainViewModel, pickFolder: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    Column(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type == KeyEventType.KeyDown && (e.key == Key.ButtonL1 || e.key == Key.ButtonR1)) {
                tab = (tab + (if (e.key == Key.ButtonR1) 1 else TABS.size - 1)) % TABS.size; true
            } else false
        }
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            LogoMark(36)
            Spacer(Modifier.width(8.dp))
            TABS.forEachIndexed { i, name ->
                val mod = if (i == 0) Modifier.focusRequester(first) else Modifier
                Box(mod.width(IntrinsicSize.Max).then(Modifier.padding(end = 4.dp))) {
                    FocusLine(name, Modifier.width(150.dp), marked = i == tab, dim = false, style = MaterialTheme.typography.headlineSmall) { tab = i }
                }
            }
            Spacer(Modifier.weight(1f))
            val scanning by vm.scanning.collectAsStateWithLifecycle()
            val n by vm.scanProgress.collectAsStateWithLifecycle()
            if (scanning) Text("SCANNING $n", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> GamesTab(vm)
                1 -> SystemsTab(vm)
                else -> SettingsTab(vm, pickFolder)
            }
        }
        Text("L1/R1 TABS    A SELECT    X OPTIONS", Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun GamesTab(vm: MainViewModel) {
    val ctx = LocalContext.current
    val games by vm.games.collectAsStateWithLifecycle()
    val tick by vm.prefsTick.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val launching by vm.launching.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf<Long?>(null) }
    if (games.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (scanning) "SCANNING..." else "NO GAMES FOUND\nCheck the ROM folder in SETTINGS.", textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val allFiles by rememberAllFilesGranted()
    LazyColumn(Modifier.fillMaxSize()) {
        if (!allFiles) item {
            FocusLine("ALL FILES ACCESS IS OFF - PRESS A TO GRANT", dim = false, style = MaterialTheme.typography.titleSmall) { openAllFilesAccess(ctx) }
        }
        if (launching) item { Text("PREPARING ROM...", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
        items(games, key = { it.id }) { g ->
            val sys = sysOf(g)
            val core = remember(g, tick, games.size, g.coreOverride) { vm.coreFor(g) }
            Column {
                FocusLine(
                    (if (g.favorite) "* " else "") + g.title,
                    right = (sys?.let(::systemLabel) ?: g.system) + if (core == null) " (NO CORE)" else "",
                    dim = core == null,
                    onAlt = { expanded = if (expanded == g.id) null else g.id },
                    onClick = { vm.launch(ctx, g) }
                )
                if (expanded == g.id) {
                    val cands = sys?.let(CoreRouter::candidates).orEmpty()
                    FocusLine("PLAY", indent = 28.dp, style = MaterialTheme.typography.titleSmall) { vm.launch(ctx, g) }
                    FocusLine("FAVORITE", right = if (g.favorite) "ON" else "OFF", indent = 28.dp, style = MaterialTheme.typography.titleSmall) { vm.toggleFavorite(g) }
                    val auto = sys?.let { CoreRouter.autoChoice(it, g.title) }
                    FocusLine("CORE: AUTO", right = auto?.displayName ?: "-", marked = g.coreOverride == null, indent = 28.dp, style = MaterialTheme.typography.titleSmall) { vm.setGameCore(g, null) }
                    cands.forEach { c ->
                        FocusLine("CORE: ${c.displayName}", marked = g.coreOverride == c.id, right = if (g.coreOverride == c.id) "SELECTED" else null,
                            indent = 28.dp, style = MaterialTheme.typography.titleSmall) { vm.setGameCore(g, c.id) }
                    }
                    Text(g.fileName, Modifier.padding(start = 44.dp, bottom = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun SystemsTab(vm: MainViewModel) {
    val games by vm.games.collectAsStateWithLifecycle()
    val tick by vm.prefsTick.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf<System?>(null) }
    val counts = games.groupingBy { it.system }.eachCount()
    val systems = System.entries.filter { counts.containsKey(it.name) || CoreRouter.candidates(it).isNotEmpty() }
        .filter { it != System.OTHER || counts.containsKey("OTHER") }
    LazyColumn(Modifier.fillMaxSize()) {
        items(systems, key = { it.name }) { s ->
            val cands = CoreRouter.candidates(s)
            val chosen = remember(tick, cands.size) { vm.prefs.systemCore(s)?.let { id -> cands.firstOrNull { it.id == id } } }
            val auto = CoreRouter.autoChoice(s, "")
            val label = (chosen ?: auto)?.displayName ?: "NO CORE"
            Column {
                FocusLine("${systemLabel(s)}  (${counts[s.name] ?: 0})", right = label, dim = cands.isEmpty(),
                    onAlt = { expanded = if (expanded == s) null else s },
                    onClick = { expanded = if (expanded == s) null else s })
                if (expanded == s) {
                    if (cands.isEmpty()) Text("No installed core serves this system.", Modifier.padding(start = 44.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else {
                        FocusLine("CORE: AUTO", right = auto?.displayName, marked = chosen == null, indent = 28.dp, style = MaterialTheme.typography.titleSmall) { vm.setSystemCore(s, null) }
                        cands.forEach { c ->
                            FocusLine("CORE: ${c.displayName}", marked = chosen?.id == c.id, right = if (chosen?.id == c.id) "SELECTED" else null,
                                indent = 28.dp, style = MaterialTheme.typography.titleSmall) { vm.setSystemCore(s, c.id) }
                        }
                        Text("Per-game overrides (X on a game) win over this.", Modifier.padding(start = 44.dp, bottom = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(vm: MainViewModel, pickFolder: () -> Unit) {
    val ctx = LocalContext.current
    val tree by vm.treeUri.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val games by vm.games.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<String?>(null) }
    fun toggle(k: String) { open = if (open == k) null else k }
    val cores = vm.cores
    val allFiles by rememberAllFilesGranted()
    var importFor by remember { mutableStateOf<CoreSetup.Item?>(null) }
    var importMsg by remember { mutableStateOf<String?>(null) }
    var setupTick by remember { mutableIntStateOf(0) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val item = importFor
        if (uri != null && item != null) {
            val ok = CoreSetup.import(ctx, item, uri)
            importMsg = if (ok) "${item.label.removePrefix("IMPORT ")}: DONE" else "${item.label.removePrefix("IMPORT ")}: FAILED"
            setupTick++
        }
    }
    val folderName = remember(tree) { tree?.let { Uri.parse(it).lastPathSegment?.substringAfter(':') } ?: "-" }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Section("ROM FOLDER") }
        item { FocusLine(folderName, right = "${games.size} GAMES", onClick = { toggle("folder") }, onAlt = { toggle("folder") }) }
        if (open == "folder") {
            item { FocusLine("CHANGE FOLDER", indent = 28.dp, style = MaterialTheme.typography.titleSmall) { pickFolder() } }
            item { FocusLine("RESCAN NOW", right = status.ifEmpty { null }, indent = 28.dp, style = MaterialTheme.typography.titleSmall) { vm.rescan() } }
            item {
                FocusLine("ALL FILES ACCESS", right = if (allFiles) "GRANTED" else "OFF (TAP)", indent = 28.dp, style = MaterialTheme.typography.titleSmall) {
                    openAllFilesAccess(ctx)
                }
            }
            item { Note("All files access lets cores in their own processes open ROMs by real path. Without it ROMs are copied to cache on launch.") }
        }
        item { Section("INSTALLED CORES (${cores.size})") }
        if (cores.isEmpty()) item { Note("No cores are installed in this build.") }
        items(cores, key = { it.id }) { c ->
            Column {
                FocusLine(c.displayName, right = CoreBootstrap.versionOf(c), onClick = { toggle("core:${c.id}") }, onAlt = { toggle("core:${c.id}") })
                if (open == "core:${c.id}") {
                    Note("id: ${c.id}")
                    Note("systems: " + c.systems.sortedBy { it.ordinal }.joinToString(", ") { systemLabel(it) })
                    Note("extensions: " + c.extensions.sorted().joinToString(" "))
                    CoreSetup.itemsFor(c.id).forEach { item ->
                        val st = remember(setupTick, open) { CoreSetup.status(ctx, item) }
                        FocusLine(item.label, right = st, indent = 28.dp, style = MaterialTheme.typography.titleSmall) {
                            importFor = item; importMsg = null; importPicker.launch(item.accept)
                        }
                    }
                    if (importMsg != null && CoreSetup.itemsFor(c.id).isNotEmpty()) Note(importMsg!!)
                }
            }
        }
        if (CoreBootstrap.missing.isNotEmpty()) {
            item { FocusLine("NOT INSTALLED (${CoreBootstrap.missing.size})", dim = true, onClick = { toggle("missing") }, onAlt = { toggle("missing") }) }
            if (open == "missing") item { Note(CoreBootstrap.missing.joinToString(", ")) }
        }
        item { Section("ABOUT") }
        item { Note("RomCore 0.1.0 - GPLv3. Embedded emulator cores keep their own licenses.") }
    }
}

@Composable
private fun Section(t: String) =
    Text(t, Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun Note(t: String) =
    Text(t, Modifier.padding(start = 44.dp, end = 16.dp, top = 2.dp, bottom = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
