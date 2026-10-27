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
import com.noryan.romcore.BuildConfig
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
import com.noryan.romcore.ui.components.ExpandBox
import com.noryan.romcore.ui.components.FocusLine
import com.noryan.romcore.ui.components.LocalButtonLook
import com.noryan.romcore.ui.components.OptionButton
import com.noryan.romcore.ui.components.PixelIconButton
import com.noryan.romcore.ui.components.PlainTextButton
import com.noryan.romcore.ui.components.PixelIcons
import com.noryan.romcore.ui.components.SubMenu
import com.noryan.romcore.ui.components.TabButton
import com.noryan.romcore.data.CollectionRules
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.pulltorefresh.PullToRefreshBox

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
        // Top bar, all left-aligned: the key hint, then [GAMES] [+] and the collection tabs (they scroll when there are many; focus
        // scrolls them into view). Visual order matches the L1/R1 cycle. X on any tab opens Settings; a long press on a collection
        // tab (touch) still opens that collection's own options.
        val newOpen = editor != null && editor?.collectionId == null
        val showSel = !settingsOpen && !newOpen
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TabButton("GAMES (${gameCount.size})", selected = showSel && tab == CollectionRules.GAMES_TAB, modifier = Modifier.focusRequester(focusOf(CollectionRules.GAMES_TAB)).widthIn(min = 130.dp),
                onAlt = { tabOpts = null; vm.toggleSettings() }) {
                tabOpts = null; vm.selectTab(CollectionRules.GAMES_TAB)
            }
            Row(Modifier.weight(1f).padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlainTextButton("+", selected = newOpen, description = "New collection", modifier = Modifier.focusRequester(plusFocus)) { tabOpts = null; vm.newCollection() }
                Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    cols.forEach { c ->
                        TabButton(CollectionRules.tabLabel(c.name), selected = showSel && tab == c.id,
                            modifier = Modifier.focusRequester(focusOf(c.id)).widthIn(min = 90.dp, max = 200.dp),
                            onAlt = { tabOpts = null; vm.toggleSettings() },
                            onLongPress = { tabOpts = if (tabOpts?.id == c.id) null else TabOptions(c.id, TabOptions.Mode.MENU) }) {
                            tabOpts = null; vm.selectTab(c.id)
                        }
                    }
                }
            }
            val keyHint = when { editor != null -> "A TOGGLE    B CANCEL"; settingsOpen -> "A SELECT    B BACK"; else -> "A SELECT    X OPTIONS" }
            val battery = rememberBatteryPercent()
            // Right side of the top bar: scan counter, the keys for this screen, then the battery level in the corner.
            if (scanning) Text("SCANNING $n", Modifier.padding(end = 18.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, softWrap = false)
            Text(keyHint, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            if (battery != null) Text("$battery%", Modifier.padding(start = 18.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, softWrap = false)
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

    }
}

/** The game list: the whole library ([collectionId] == null) or one collection's games. Same rows, launch and X options. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
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
    var coreOpen by remember(collectionId) { mutableStateOf<Long?>(null) }
    val games = if (collectionId == null) all else CollectionRules.gamesOf(all, members[collectionId].orEmpty())
    val cols = collections.orEmpty()
    if (collectionId == null && games.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (scanning) "SCANNING..." else "NO GAMES FOUND\nPress BACK to open SETTINGS and check the ROM folder.", textAlign = TextAlign.Center,
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    // Pull the list down (touch) to rescan the ROM folder; the spinner shows while the scan runs.
    PullToRefreshBox(isRefreshing = scanning, onRefresh = { vm.rescan() }, modifier = Modifier.fillMaxSize()) {
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
                    dim = core == null,
                    onAlt = { expanded = if (expanded == g.id) null else g.id; addOpen = null; coreOpen = null },
                    onClick = { vm.launch(ctx, g) }
                )
                if (expanded == g.id) {
                    val cands = sys?.let { CoreRouter.candidatesFor(it, g.gameId, title = g.title) }.orEmpty()
                    val auto = sys?.let { CoreRouter.autoChoice(it, g.title, gameId = g.gameId) }
                    val chosen = CoreRouter.resolveOverride(g.coreOverride, cands)
                    val autoLabel = "AUTO" + (auto?.displayName?.let { " ($it)" } ?: "")
                    // A narrow, centered stack of in-game-menu-style buttons under the game's name.
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopStart) {
                        Column(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 10.dp).widthIn(max = 296.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            OptionButton("PLAY") { vm.launch(ctx, g) }
                            if (collectionId != null)
                                OptionButton("REMOVE FROM COLLECTION") { vm.setMember(collectionId, g.id, false); expanded = null }
                            if (cols.isNotEmpty()) {
                                OptionButton("ADD TO COLLECTION", selected = addOpen == g.id) { addOpen = if (addOpen == g.id) null else g.id }
                                if (addOpen == g.id) SubMenu {
                                    cols.forEach { c ->
                                        val inIt = g.id in members[c.id].orEmpty()
                                        OptionButton((if (inIt) "[X] " else "[ ] ") + CollectionRules.tabLabel(c.name), selected = inIt, small = true, flat = true) { vm.setMember(c.id, g.id, !inIt) }
                                    }
                                }
                            }
                            // One CORE field. Closed: a single button. Open: the same box grows, with the current choice as its header
                            // and every other core that can run this game listed under it.
                            val current = "CORE: " + (chosen?.displayName ?: autoLabel)
                            if (coreOpen != g.id) {
                                OptionButton(current) { coreOpen = g.id }
                            } else ExpandBox(
                                header = { OptionButton(current, selected = true, flat = true) { coreOpen = null } },
                            ) {
                                // Everything except what the header already shows (AUTO resolves to [auto], so it is not listed again).
                                val others = cands.filter { it.id != chosen?.id && (chosen != null || it.id != auto?.id) }
                                if (chosen != null) OptionButton(autoLabel, small = true, flat = true) { vm.setGameCore(g, null); coreOpen = null }
                                others.forEach { c -> OptionButton(c.displayName, small = true, flat = true) { vm.setGameCore(g, c.id); coreOpen = null } }
                                if (chosen == null && others.isEmpty()) Text("NO OTHER CORES FOR THIS SYSTEM", Modifier.padding(8.dp).fillMaxWidth(),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

/** Device battery level (0-100), kept current from the sticky battery broadcast; null if the device doesn't report one. */
@Composable
private fun rememberBatteryPercent(): Int? {
    val ctx = LocalContext.current
    fun pct(i: android.content.Intent?): Int? {
        val level = i?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level >= 0 && scale > 0) level * 100 / scale else null
    }
    var value by remember { mutableStateOf<Int?>(null) }
    DisposableEffect(ctx) {
        val rx = object : android.content.BroadcastReceiver() { override fun onReceive(c: android.content.Context?, i: android.content.Intent?) { value = pct(i) } }
        value = pct(ctx.registerReceiver(rx, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)))   // sticky: gives the current level at once
        onDispose { runCatching { ctx.unregisterReceiver(rx) } }
    }
    return value
}

/** True while RomCore is the device's chosen home app; re-read on every resume (the choice is made in a system screen). */
@Composable
private fun rememberIsHomeApp(): androidx.compose.runtime.State<Boolean> {
    val ctx = LocalContext.current
    val st = remember { mutableStateOf(isHomeApp(ctx)) }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val ob = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) st.value = isHomeApp(ctx) }
        owner.lifecycle.addObserver(ob); onDispose { owner.lifecycle.removeObserver(ob) }
    }
    return st
}

private fun isHomeApp(ctx: android.content.Context): Boolean {
    val home = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
    return ctx.packageManager.resolveActivity(home, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName == ctx.packageName
}

private fun openHomeAppSettings(ctx: android.content.Context) {
    runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }
        .onFailure { runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) } }
}

@Composable
private fun SettingsTab(vm: MainViewModel, pickRom: () -> Unit, pickBios: () -> Unit, pickSave: () -> Unit) {
    val ctx = LocalContext.current
    val romDir by vm.romDir.collectAsStateWithLifecycle()
    val biosDir by vm.biosDir.collectAsStateWithLifecycle()
    val biosLines by vm.biosLines.collectAsStateWithLifecycle()
    val biosNote by vm.biosNote.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val saveInfo by vm.saveInfo.collectAsStateWithLifecycle()
    val saveStatus by vm.saveStatus.collectAsStateWithLifecycle()
    val saveWarning by vm.saveWarning.collectAsStateWithLifecycle()
    val saveOthers by vm.saveOthers.collectAsStateWithLifecycle()
    val tick by vm.prefsTick.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf<String?>(null) }
    fun toggle(k: String) { open = if (open == k) null else k }
    val allFiles by rememberAllFilesGranted()
    val apps = remember { launchableApps(ctx) }   // re-read each time Settings opens
    // Menu-style buttons in one narrow, centered column (like the in-game menu). A row that is open grows into ONE bordered
    // box: its current value as the header, its options and notes inside.
    CompositionLocalProvider(LocalButtonLook provides 1) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
    val side = ((maxWidth - 400.dp) / 2).coerceAtLeast(12.dp)
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = side, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            SettingRow("ROM FOLDER", romDir?.substringAfterLast('/') ?: "-", open == "folder", { toggle("folder") }) {
                Note(romDir ?: "No folder chosen")
                FocusLine("SEARCH AGAIN", right = "AUTO-FIND") { vm.startRomSearch() }
                FocusLine("PICK FOLDER...") { pickRom() }
                FocusLine("RESCAN NOW", right = status.ifEmpty { null }) { vm.rescan() }
                Note("RomCore opens games by real path (All files access is ${if (allFiles) "on" else "OFF - press A on ALL FILES ACCESS below"}). Folders starting with . or _ and emulator data folders are not scanned.")
                if (!allFiles) FocusLine("ALL FILES ACCESS", right = "OFF (TAP)") { openAllFilesAccess(ctx) }
            }
        }
        item {
            SettingRow("BIOS FOLDER", biosDir?.substringAfterLast('/') ?: "NONE   OPTIONAL", open == "bios", { toggle("bios") }) {
                Note(biosDir ?: "No BIOS folder chosen. Games that need one will say so.")
                FocusLine("SEARCH AGAIN", right = "AUTO-FIND") { vm.searchBios(first = false) }
                FocusLine("PICK FOLDER...") { pickBios() }
                if (biosDir != null) FocusLine("IMPORT NOW") { vm.runBiosImport() }
                if (biosNote.isNotEmpty()) Note(biosNote)
                biosLines.forEach { l -> Note(l) }
                Note("Copies PS2 BIOS, Switch prod.keys + firmware and PS1 and other console BIOS into the cores. Your files are only read; newer copies are never overwritten.")
            }
        }
        item {
            val saveName = if (saveWarning != null) "APP STORAGE (WARNING)" else saveInfo?.path?.substringAfterLast('/') ?: "-"
            val saveSize = saveInfo?.let { com.noryan.romcore.api.save.SaveRoot.sizeLabel(it.bytes) }
            SettingRow("SAVE DATA FOLDER", if (saveSize != null) "$saveName   $saveSize" else saveName, open == "save", { toggle("save") }) {
                if (saveWarning != null) Note("WARNING: $saveWarning")
                Note(saveInfo?.path ?: "Not set up yet")
                saveInfo?.let { i -> Note("${i.dataFiles}${if (i.capped) "+" else ""} save files, ${com.noryan.romcore.api.save.SaveRoot.sizeLabel(i.bytes)}, last changed ${saveDate(i.lastModified)}") }
                FocusLine("SEARCH AGAIN", right = "AUTO-FIND") { vm.searchSaveAgain() }
                FocusLine("PICK FOLDER...") { pickSave() }
                if (saveStatus.isNotEmpty()) Note("STATUS: $saveStatus")
                saveOthers.forEach { o -> FocusLine("USE ${o.path}", right = com.noryan.romcore.api.save.SaveRoot.sizeLabel(o.bytes)) { vm.chooseSave(o.path) } }
                Note("All game saves and save states of every emulator live in this one folder (named ${com.noryan.romcore.api.save.SaveRoot.FOLDER_NAME}). Copy it to another device's ROMs folder and RomCore finds it by itself. Shader caches, configs, keys and BIOS are not stored there.")
            }
        }
        if (saveWarning != null && open != "save") item { Note("WARNING: $saveWarning") }
        item {
            val isHome by rememberIsHomeApp()
            FocusLine("HOME SCREEN", right = if (isHome) "ROMCORE" else "OFF   OPEN TO CHANGE", onClick = { openHomeAppSettings(ctx) })
        }
        item { Note("Make RomCore the launcher: choose it as the Home app, and the Home button brings you back here.") }
        // Every installed app that has a launcher icon, so RomCore still works as the device's home screen.
        item { Section("APPS (${apps.size})") }
        if (apps.isEmpty()) item { Note("No other apps found.") }
        items(apps, key = { it.component.flattenToString() }) { a ->
            FocusLine(a.label, onClick = { launchApp(ctx, a) })
        }
        item { Section("ABOUT") }
        item { Note("RomCore ${BuildConfig.VERSION_NAME} - GPLv3. Embedded emulator cores keep their own licenses.") }
    }
    }
    }
}

/**
 * One Settings row. Closed: a menu-style button (name, with [value] under it). Open: the same button becomes the header of a single
 * bordered box that holds [children] (options drawn as flat small buttons, notes as centered text).
 */
@Composable
private fun SettingRow(label: String, value: String?, expanded: Boolean, onToggle: () -> Unit, dim: Boolean = false, children: @Composable () -> Unit) {
    if (!expanded) OptionButton(label, sub = value, dim = dim, onAlt = onToggle, onClick = onToggle)
    else ExpandBox(header = { OptionButton(label, sub = value, selected = true, flat = true, dim = dim, onAlt = onToggle, onClick = onToggle) }) {
        CompositionLocalProvider(LocalButtonLook provides 2) { children() }
    }
}

private class LaunchableApp(val label: String, val component: android.content.ComponentName)

/** Launcher-visible apps other than RomCore, A-Z. Needs the LAUNCHER <queries> entry in the manifest (Android 11+ package visibility). */
private fun launchableApps(ctx: android.content.Context): List<LaunchableApp> {
    val pm = ctx.packageManager
    val main = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(main, 0)
        .filter { it.activityInfo.packageName != ctx.packageName }
        .map { LaunchableApp(it.loadLabel(pm).toString(), android.content.ComponentName(it.activityInfo.packageName, it.activityInfo.name)) }
        .sortedBy { it.label.lowercase() }
}

private fun launchApp(ctx: android.content.Context, a: LaunchableApp) {
    runCatching {
        ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            .setComponent(a.component).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
    }
}

@Composable
internal fun Section(t: String) {
    val centered = LocalButtonLook.current > 0
    Text(t, if (centered) Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp) else Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, textAlign = if (centered) TextAlign.Center else TextAlign.Start)
}

@Composable
internal fun Note(t: String) {
    val centered = LocalButtonLook.current > 0
    Text(t, if (centered) Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp) else Modifier.padding(start = 44.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = if (centered) TextAlign.Center else TextAlign.Start)
}