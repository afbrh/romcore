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
import com.noryan.romcore.Setup
import com.noryan.romcore.data.CoreRouter
import com.noryan.romcore.data.CoreRows
import com.noryan.romcore.data.FolderSearch
import com.noryan.romcore.data.CoreSetup
import com.noryan.romcore.data.GameEntity
import com.noryan.romcore.ui.components.FocusLine
import com.noryan.romcore.ui.components.PixelIconButton
import com.noryan.romcore.ui.components.PlainTextButton
import com.noryan.romcore.ui.components.PixelIcons
import com.noryan.romcore.ui.components.TabButton
import com.noryan.romcore.data.CollectionRules
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyListScope

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
    val granted by vm.accessGranted.collectAsStateWithLifecycle()
    val setup by vm.setup.collectAsStateWithLifecycle()
    val romPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::pickRom) }
    val biosPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::pickBios) }
    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::pickSave) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val ob = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) vm.refreshAccess() }
        owner.lifecycle.addObserver(ob); onDispose { owner.lifecycle.removeObserver(ob) }
    }
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, contentColor = MaterialTheme.colorScheme.onBackground) {
        Box(Modifier.fillMaxSize().safeDrawingPadding()) {
            val s = setup
            when {
                !granted -> AccessScreen()
                s is Setup.Searching -> SearchingScreen(s.kind)
                s is Setup.Choose -> {
                    val roms = s.kind == FolderSearch.Kind.ROMS
                    ChooseScreen(s.kind, s.candidates, s.cancellable,
                        onChoose = { if (roms) vm.chooseRom(it) else vm.chooseBios(it) },
                        onSearchAgain = { if (roms) vm.startRomSearch() else vm.searchBios(first = !s.cancellable) },
                        onPick = { if (roms) romPicker.launch(null) else biosPicker.launch(null) },
                        onSkipOrCancel = { if (roms) vm.cancelSetup() else vm.skipBios() })
                }
                s is Setup.ChooseSave -> SaveChooseScreen(s.candidates, s.cancellable, onChoose = vm::chooseSave, onSearchAgain = vm::searchSaveAgain,
                    onPick = { savePicker.launch(null) }, onCancel = vm::cancelSetup)
                s is Setup.NotFound -> NotFoundScreen(s.cancellable, onPick = { romPicker.launch(null) }, onSearchAgain = { vm.startRomSearch() }, onCancel = vm::cancelSetup)
                else -> MainScreen(vm, pickRom = { romPicker.launch(null) }, pickBios = { biosPicker.launch(null) }, pickSave = { savePicker.launch(null) })
            }
        }
    }
}

@Composable
internal fun LogoMark(size: Int) {
    Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.size(size.dp))
}

@Composable
private fun MainScreen(vm: MainViewModel, pickRom: () -> Unit, pickBios: () -> Unit, pickSave: () -> Unit) {
    val collections by vm.collections.collectAsStateWithLifecycle()
    val saved by vm.selectedTab.collectAsStateWithLifecycle()
    val settingsOpen by vm.settingsOpen.collectAsStateWithLifecycle()
    val editor by vm.editor.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val n by vm.scanProgress.collectAsStateWithLifecycle()
    val cols = collections.orEmpty()
    val gameCount by vm.games.collectAsStateWithLifecycle()
    // A saved collection tab is only trusted once the DB has answered; a deleted collection falls back to GAMES (never written back here).
    val tab = if (collections == null) saved else CollectionRules.resolveTab(saved, cols)
    var tabOpts by remember { mutableStateOf<TabOptions?>(null) }
    val tabFocus = remember { HashMap<Long, FocusRequester>() }
    val focusSeen = remember { mutableStateOf(false) }
    fun focusOf(id: Long) = tabFocus.getOrPut(id) { FocusRequester() }
    // Start on the selected tab (the first focus lands on a tab). Focus can only be taken once the window has it.
    val windowFocused = androidx.compose.ui.platform.LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused, collections != null) {
        if (windowFocused && collections != null && !focusSeen.value) {
            kotlinx.coroutines.delay(50)
            runCatching { focusOf(tab).requestFocus() }
        }
    }
    // Focus follows the screen that just closed: Settings -> the selected tab; the editor -> the tab it produced (or the + after a cancel).
    val plusFocus = remember { FocusRequester() }
    var closedSettings by remember { mutableStateOf(false) }
    var closedEditor by remember { mutableStateOf(false) }
    var sawSettings by remember { mutableStateOf(false) }
    var sawEditor by remember { mutableStateOf(false) }
    LaunchedEffect(settingsOpen) { if (settingsOpen) sawSettings = true else if (sawSettings) { sawSettings = false; closedSettings = true } }
    LaunchedEffect(editor != null) { if (editor != null) sawEditor = true else if (sawEditor) { sawEditor = false; closedEditor = true } }
    LaunchedEffect(closedSettings) { if (closedSettings) { closedSettings = false; runCatching { focusOf(tab).requestFocus() } } }
    LaunchedEffect(closedEditor, tab, cols) {
        if (closedEditor) {
            if (tab != CollectionRules.GAMES_TAB) { closedEditor = false; runCatching { focusOf(tab).requestFocus() } }
            else { kotlinx.coroutines.delay(250); closedEditor = false; runCatching { plusFocus.requestFocus() } }
        }
    }
    var lastOpts by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(tabOpts) {
        val o = tabOpts
        if (o != null) lastOpts = o.id
        else lastOpts?.let { id -> lastOpts = null; if (editor == null) runCatching { focusOf(id).requestFocus() } }
    }
    LaunchedEffect(cols, editor, settingsOpen) {
        val o = tabOpts
        if (o != null && (editor != null || cols.none { it.id == o.id })) tabOpts = null
    }
    // BACK is the Settings key, like it summons the in-game menu: it opens Settings from the home screen and closes it again.
    BackHandler {
        when {
            editor != null -> vm.cancelEditor()
            tabOpts != null -> tabOpts = null
            else -> vm.toggleSettings()
        }
    }
    Column(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            focusSeen.value = true
            if (e.type == KeyEventType.KeyDown && (e.key == Key.ButtonL1 || e.key == Key.ButtonR1)) {
                if (editor == null) {
                    val to = CollectionRules.cycleTab(tab, if (e.key == Key.ButtonR1) 1 else -1, cols)
                    tabOpts = null
                    vm.selectTab(to)
                    runCatching { focusOf(to).requestFocus() }
                }
                true
            } else false
        }
    ) {
        // GAMES is centered on the screen: equal-weight regions on either side. Left holds the scan counter; right holds [+] and the
        // collection tabs (they scroll when there are many; focus scrolls them into view). Visual order matches the L1/R1 cycle.
        val newOpen = editor != null && editor?.collectionId == null
        val showSel = !settingsOpen && !newOpen
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (scanning) Text("SCANNING $n", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            TabButton("GAMES (${gameCount.size})", selected = showSel && tab == CollectionRules.GAMES_TAB, modifier = Modifier.focusRequester(focusOf(CollectionRules.GAMES_TAB)).widthIn(min = 130.dp)) {
                tabOpts = null; vm.selectTab(CollectionRules.GAMES_TAB)
            }
            Row(Modifier.weight(1f).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlainTextButton("+", selected = newOpen, description = "New collection", modifier = Modifier.focusRequester(plusFocus)) { tabOpts = null; vm.newCollection() }
                Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cols.forEach { c ->
                        TabButton(CollectionRules.tabLabel(c.name), selected = showSel && tab == c.id,
                            modifier = Modifier.focusRequester(focusOf(c.id)).widthIn(min = 90.dp, max = 200.dp),
                            onAlt = { tabOpts = if (tabOpts?.id == c.id) null else TabOptions(c.id, TabOptions.Mode.MENU) }) {
                            tabOpts = null; vm.selectTab(c.id)
                        }
                    }
                }
            }
        }
        tabOpts?.let { o -> cols.firstOrNull { it.id == o.id }?.let { c -> CollectionOptions(vm, c, o.mode, { tabOpts = o.copy(mode = it) }, { tabOpts = null }) } }
        val notice by vm.crashNotice.collectAsStateWithLifecycle()
        notice?.let { (title, hint) ->
            Column {
                FocusLine(title, onClick = vm::dismissCrashNotice, style = MaterialTheme.typography.titleSmall)
                Text(hint, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                editor != null -> CollectionEditorScreen(vm)
                settingsOpen -> SettingsTab(vm, pickRom, pickBios, pickSave)
                tab == CollectionRules.GAMES_TAB -> GameList(vm, null)
                else -> GameList(vm, tab)
            }
        }
        val hint = when {
            editor != null -> "A TOGGLE    B CANCEL"
            settingsOpen -> "A SELECT    B BACK    X OPTIONS"
            else -> "L1/R1 TABS    A SELECT    X OPTIONS    BACK SETTINGS"
        }
        Text(hint, Modifier.fillMaxWidth().padding(8.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** The game list: the whole library ([collectionId] == null) or one collection's games. Same rows, launch and X options. */
@Composable
private fun GameList(vm: MainViewModel, collectionId: Long?) {
    val ctx = LocalContext.current
    val all by vm.games.collectAsStateWithLifecycle()
    val members by vm.members.collectAsStateWithLifecycle()
    val collections by vm.collections.collectAsStateWithLifecycle()
    val tick by vm.prefsTick.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val launching by vm.launching.collectAsStateWithLifecycle()
    var expanded by remember(collectionId) { mutableStateOf<Long?>(null) }
    var addOpen by remember(collectionId) { mutableStateOf<Long?>(null) }
    val games = if (collectionId == null) all else CollectionRules.gamesOf(all, members[collectionId].orEmpty())
    val cols = collections.orEmpty()
    if (collectionId == null && games.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (scanning) "SCANNING..." else "NO GAMES FOUND\nPress BACK to open SETTINGS and check the ROM folder.", textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        if (launching) item { Text("PREPARING ROM...", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
        if (collectionId != null && games.isEmpty()) item(key = "empty") {
            FocusLine("NO GAMES - PRESS X TO EDIT", dim = true, onAlt = { vm.editCollection(collectionId) }, onClick = { vm.editCollection(collectionId) })
        }
        items(games, key = { it.id }) { g ->
            val sys = sysOf(g)
            val core = remember(g, tick, all.size, g.coreOverride) { vm.coreFor(g) }
            Column {
                FocusLine(
                    (if (g.favorite) "* " else "") + g.title + if (core == null) " (NO CORE)" else "",
                    center = true,
                    dim = core == null,
                    onAlt = { expanded = if (expanded == g.id) null else g.id; addOpen = null },
                    onClick = { vm.launch(ctx, g) }
                )
                if (expanded == g.id) {
                    val cands = sys?.let { CoreRouter.candidatesFor(it, g.gameId) }.orEmpty()
                    FocusLine("PLAY", indent = 28.dp, style = MaterialTheme.typography.titleSmall) { vm.launch(ctx, g) }
                    FocusLine("FAVORITE", right = if (g.favorite) "ON" else "OFF", indent = 28.dp, style = MaterialTheme.typography.titleSmall) { vm.toggleFavorite(g) }
                    if (collectionId != null)
                        FocusLine("REMOVE FROM COLLECTION", indent = 28.dp, style = MaterialTheme.typography.titleSmall) { vm.setMember(collectionId, g.id, false); expanded = null }
                    if (cols.isNotEmpty()) {
                        FocusLine("ADD TO COLLECTION >", right = if (addOpen == g.id) "OPEN" else null, indent = 28.dp, style = MaterialTheme.typography.titleSmall) { addOpen = if (addOpen == g.id) null else g.id }
                        if (addOpen == g.id) cols.forEach { c ->
                            val inIt = g.id in members[c.id].orEmpty()
                            FocusLine((if (inIt) "[X] " else "[ ] ") + CollectionRules.tabLabel(c.name), marked = inIt, indent = 56.dp, style = MaterialTheme.typography.titleSmall) { vm.setMember(c.id, g.id, !inIt) }
                        }
                    }
                    val auto = sys?.let { CoreRouter.autoChoice(it, g.title, gameId = g.gameId) }
                    val gChosen0 = CoreRouter.resolveOverride(g.coreOverride, cands)
                    FocusLine("CORE: AUTO", right = auto?.displayName ?: "-", marked = gChosen0 == null, indent = 28.dp, style = MaterialTheme.typography.titleSmall) { vm.setGameCore(g, null) }
                    val gChosen = CoreRouter.resolveOverride(g.coreOverride, cands)
                    cands.forEach { c ->
                        FocusLine("CORE: ${c.displayName}", marked = gChosen?.id == c.id, right = if (gChosen?.id == c.id) "SELECTED" else null,
                            indent = 28.dp, style = MaterialTheme.typography.titleSmall) { vm.setGameCore(g, c.id) }
                    }
                    Text(g.fileName, Modifier.padding(start = 44.dp, bottom = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/** What Settings > SYSTEMS shows (the former SYSTEMS tab): per-system core choice, with the game count. */
private fun LazyListScope.systemsItems(vm: MainViewModel, games: List<GameEntity>, tick: Int, expanded: System?, setExpanded: (System?) -> Unit) {
    val counts = games.groupingBy { it.system }.eachCount()
    val systems = System.entries.filter { counts.containsKey(it.name) || CoreRouter.candidates(it).isNotEmpty() }
        .filter { it != System.OTHER || counts.containsKey("OTHER") }
    items(systems, key = { "sys:${it.name}" }) { s ->
        val cands = CoreRouter.candidates(s)
        val chosen = remember(tick, cands.size) { CoreRouter.resolveOverride(vm.prefs.systemCore(s), cands) }
        val auto = CoreRouter.autoChoice(s, "")
        val label = (chosen ?: auto)?.displayName ?: "NO CORE"
        Column {
            FocusLine("${systemLabel(s)}  (${counts[s.name] ?: 0})", right = label, dim = cands.isEmpty(), indent = 28.dp, style = MaterialTheme.typography.titleSmall,
                onAlt = { setExpanded(if (expanded == s) null else s) },
                onClick = { setExpanded(if (expanded == s) null else s) })
            if (expanded == s) {
                if (cands.isEmpty()) Text("No installed core serves this system.", Modifier.padding(start = 72.dp, bottom = 8.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else {
                    FocusLine("CORE: AUTO", right = auto?.displayName, marked = chosen == null, indent = 56.dp, style = MaterialTheme.typography.titleSmall) { vm.setSystemCore(s, null) }
                    cands.forEach { c ->
                        FocusLine("CORE: ${c.displayName}", marked = chosen?.id == c.id, right = if (chosen?.id == c.id) "SELECTED" else null,
                            indent = 56.dp, style = MaterialTheme.typography.titleSmall) { vm.setSystemCore(s, c.id) }
                    }
                    Text("Per-game overrides (X on a game) win over this.", Modifier.padding(start = 72.dp, bottom = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(vm: MainViewModel, pickRom: () -> Unit, pickBios: () -> Unit, pickSave: () -> Unit) {
    val ctx = LocalContext.current
    val romDir by vm.romDir.collectAsStateWithLifecycle()
    val biosDir by vm.biosDir.collectAsStateWithLifecycle()
    val biosLines by vm.biosLines.collectAsStateWithLifecycle()
    val biosNote by vm.biosNote.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val games by vm.games.collectAsStateWithLifecycle()
    val saveInfo by vm.saveInfo.collectAsStateWithLifecycle()
    val saveStatus by vm.saveStatus.collectAsStateWithLifecycle()
    val saveWarning by vm.saveWarning.collectAsStateWithLifecycle()
    val saveOthers by vm.saveOthers.collectAsStateWithLifecycle()
    val tick by vm.prefsTick.collectAsStateWithLifecycle()
    var openSystem by remember { mutableStateOf<System?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    fun toggle(k: String) { open = if (open == k) null else k }
    val rows = remember(vm.cores) { CoreRows.of(vm.cores, CoreBootstrap::versionOf) }
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
    LazyColumn(Modifier.fillMaxSize()) {
        item { FocusLine("ROM FOLDER", right = romDir?.substringAfterLast('/') ?: "-", onClick = { toggle("folder") }, onAlt = { toggle("folder") }) }
        if (open == "folder") {
            item { Note(romDir ?: "No folder chosen") }
            item { FocusLine("SEARCH AGAIN", right = "AUTO-FIND", indent = 28.dp, style = MaterialTheme.typography.titleSmall) { vm.startRomSearch() } }
            item { FocusLine("PICK FOLDER...", indent = 28.dp, style = MaterialTheme.typography.titleSmall) { pickRom() } }
            item { FocusLine("RESCAN NOW", right = status.ifEmpty { null }, indent = 28.dp, style = MaterialTheme.typography.titleSmall) { vm.rescan() } }
            item { Note("RomCore opens games by real path (All files access is ${if (allFiles) "on" else "OFF - press A on ALL FILES ACCESS below"}). Folders starting with . or _ and emulator data folders are not scanned.") }
            if (!allFiles) item { FocusLine("ALL FILES ACCESS", right = "OFF (TAP)", indent = 28.dp, style = MaterialTheme.typography.titleSmall) { openAllFilesAccess(ctx) } }
        }
        item { FocusLine("BIOS FOLDER", right = biosDir?.substringAfterLast('/') ?: "NONE   OPTIONAL", onClick = { toggle("bios") }, onAlt = { toggle("bios") }) }
        if (open == "bios") {
            item { Note(biosDir ?: "No BIOS folder chosen. Games that need one will say so.") }
            item { FocusLine("SEARCH AGAIN", right = "AUTO-FIND", indent = 28.dp, style = MaterialTheme.typography.titleSmall) { vm.searchBios(first = false) } }
            item { FocusLine("PICK FOLDER...", indent = 28.dp, style = MaterialTheme.typography.titleSmall) { pickBios() } }
            if (biosDir != null) item { FocusLine("IMPORT NOW", indent = 28.dp, style = MaterialTheme.typography.titleSmall) { vm.runBiosImport() } }
            if (biosNote.isNotEmpty()) item { Note(biosNote) }
            biosLines.forEach { l -> item { Note(l) } }
            item { Note("Copies PS2 BIOS, Switch prod.keys + firmware and PS1 and other console BIOS into the cores. Your files are only read; newer copies are never overwritten.") }
        }
        item {
            val saveName = if (saveWarning != null) "APP STORAGE (WARNING)" else saveInfo?.path?.substringAfterLast('/') ?: "-"
            val saveSize = saveInfo?.let { com.noryan.romcore.api.save.SaveRoot.sizeLabel(it.bytes) }
            FocusLine("SAVE DATA FOLDER", right = if (saveSize != null) "$saveName   $saveSize" else saveName, onClick = { toggle("save") }, onAlt = { toggle("save") })
        }
        if (saveWarning != null) item { Note("WARNING: $saveWarning") }
        if (open == "save") {
            item { Note(saveInfo?.path ?: "Not set up yet") }
            saveInfo?.let { i -> item { Note("${i.dataFiles}${if (i.capped) "+" else ""} save files, ${com.noryan.romcore.api.save.SaveRoot.sizeLabel(i.bytes)}, last changed ${saveDate(i.lastModified)}") } }
            item { FocusLine("SEARCH AGAIN", right = "AUTO-FIND", indent = 28.dp, style = MaterialTheme.typography.titleSmall) { vm.searchSaveAgain() } }
            item { FocusLine("PICK FOLDER...", indent = 28.dp, style = MaterialTheme.typography.titleSmall) { pickSave() } }
            if (saveStatus.isNotEmpty()) item { Note("STATUS: $saveStatus") }
            saveOthers.forEach { o -> item(key = "so:${o.path}") { FocusLine("USE ${o.path}", right = com.noryan.romcore.api.save.SaveRoot.sizeLabel(o.bytes), indent = 28.dp, style = MaterialTheme.typography.titleSmall) { vm.chooseSave(o.path) } } }
            item { Note("All game saves and save states of every emulator live in this one folder (named ${com.noryan.romcore.api.save.SaveRoot.FOLDER_NAME}). Copy it to another device's ROMs folder and RomCore finds it by itself. Shader caches, configs, keys and BIOS are not stored there.") }
        }
        item { Section("SYSTEMS") }
        item { FocusLine("CORE PER SYSTEM", right = if (open == "systems") "OPEN" else null, onClick = { toggle("systems") }, onAlt = { toggle("systems") }) }
        if (open == "systems") systemsItems(vm, games, tick, openSystem) { openSystem = it }
        item { Section("INSTALLED CORES (${rows.size})") }
        if (rows.isEmpty()) item { Note("No cores are installed in this build.") }
        items(rows, key = { it.key }) { c ->
            Column {
                FocusLine(c.name, right = c.version, onClick = { toggle("core:${c.key}") }, onAlt = { toggle("core:${c.key}") })
                if (open == "core:${c.key}") {
                    Note("id: ${c.key}")
                    Note("systems: " + c.systems.sortedBy { it.ordinal }.joinToString(", ") { systemLabel(it) })
                    Note("extensions: " + c.extensions.sorted().joinToString(" "))
                    c.license?.let { Note("license: $it") }
                    CoreSetup.itemsFor(c.coreId).forEach { item ->
                        val st = remember(setupTick, open) { CoreSetup.status(ctx, item) }
                        FocusLine(item.label, right = st, indent = 28.dp, style = MaterialTheme.typography.titleSmall) {
                            importFor = item; importMsg = null; importPicker.launch(item.accept)
                        }
                    }
                    if (importMsg != null && CoreSetup.itemsFor(c.coreId).isNotEmpty()) Note(importMsg!!)
                }
            }
        }
        if (CoreBootstrap.missing.isNotEmpty()) {
            item { FocusLine("NOT INSTALLED (${CoreBootstrap.missing.size})", dim = true, onClick = { toggle("missing") }, onAlt = { toggle("missing") }) }
            if (open == "missing") item { Note(CoreBootstrap.missing.joinToString(", ") { CoreBootstrap.friendlyName(it) }) }
        }
        item { Section("ABOUT") }
        item { Note("RomCore 0.1.0 - GPLv3. Embedded emulator cores keep their own licenses.") }
    }
}

@Composable
internal fun Section(t: String) =
    Text(t, Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)

@Composable
internal fun Note(t: String) =
    Text(t, Modifier.padding(start = 44.dp, end = 16.dp, top = 2.dp, bottom = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
