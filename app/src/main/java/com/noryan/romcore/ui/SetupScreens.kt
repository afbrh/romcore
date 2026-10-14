package com.noryan.romcore.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noryan.romcore.data.FolderSearch
import com.noryan.romcore.data.SaveDiscovery
import com.noryan.romcore.api.save.SaveRoot
import com.noryan.romcore.ui.components.FocusLine
import com.noryan.romcore.ui.components.ActionButton

/*
 * First-run screens, in order: ACCESS (All files access) -> SEARCHING -> [CHOOSE one of several | NOT FOUND] for the ROM
 * folder, then the same for BIOS (which never shows NOT FOUND: a missing BIOS folder is skipped silently).
 * Everything is a FocusLine so a controller drives it; no dialogs.
 */

@Composable
private fun Header(title: String, sub: String? = null) {
    LogoMark(72)
    Text("ROMCORE", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
    Spacer(Modifier.height(18.dp))
    Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    if (sub != null) {
        Spacer(Modifier.height(6.dp))
        Text(sub, Modifier.widthIn(max = 640.dp).padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
    Spacer(Modifier.height(14.dp))
}

/** Step 1, before anything else: the one permission RomCore needs. */
@Composable
fun AccessScreen() {
    val ctx = LocalContext.current
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    var tried by rememberSaveable { mutableStateOf(false) }
    // Coming back from the system screen without the switch on means the user declined (or backed out).
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var declined by rememberSaveable { mutableStateOf(false) }
    DisposableEffect(owner) {
        val ob = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME && tried) declined = true }
        owner.lifecycle.addObserver(ob); onDispose { owner.lifecycle.removeObserver(ob) }
    }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Header("ONE THING FIRST: ALL FILES ACCESS",
            "RomCore finds your ROM and BIOS folders by itself and every emulator core opens games by their real path. " +
                "That needs All files access. RomCore only reads those folders; it never deletes or moves your files.")
        Spacer(Modifier.height(12.dp))
        ActionButton(if (declined) "TRY AGAIN" else "GRANT ALL FILES ACCESS", Modifier.focusRequester(fr).width(520.dp)) { tried = true; openAllFilesAccess(ctx) }
        if (declined) {
            Spacer(Modifier.height(8.dp))
            Text("ALL FILES ACCESS IS STILL OFF", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            Text("RomCore can't work without it. Turn on \"Allow access to manage all files\" for RomCore, then press back.",
                Modifier.widthIn(max = 560.dp).padding(horizontal = 16.dp), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun SearchingScreen(kind: FolderSearch.Kind) {
    val a by rememberInfiniteTransition(label = "s").animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a")
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Header(if (kind == FolderSearch.Kind.ROMS) "FINDING YOUR ROMS" else "FINDING YOUR BIOS FILES")
        Text("SEARCHING...", Modifier.alpha(a), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text("Internal storage and every SD card / USB drive", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Several folders matched: pick one. */
@Composable
fun ChooseScreen(
    kind: FolderSearch.Kind, candidates: List<FolderSearch.Candidate>, cancellable: Boolean,
    onChoose: (String) -> Unit, onSearchAgain: () -> Unit, onPick: () -> Unit, onSkipOrCancel: () -> Unit
) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    val roms = kind == FolderSearch.Kind.ROMS
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Text(if (roms) "WHICH FOLDER HOLDS YOUR ROMS?" else "WHICH FOLDER HOLDS YOUR BIOS FILES?", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text("Found ${candidates.size} matching folders. Pick one; you can change it later in SETTINGS.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(candidates, key = { _, c -> c.path }) { i, c ->
                FocusLine(c.path, if (i == 0) Modifier.focusRequester(fr) else Modifier,
                    right = if (roms) "${c.countLabel} GAMES" else "${c.countLabel} FILES") { onChoose(c.path) }
            }
            item { FocusLine("SEARCH AGAIN", style = MaterialTheme.typography.titleSmall) { onSearchAgain() } }
            item { FocusLine("PICK ANOTHER FOLDER...", style = MaterialTheme.typography.titleSmall) { onPick() } }
            item { FocusLine(if (roms) "CANCEL" else "SKIP FOR NOW", style = MaterialTheme.typography.titleSmall, dim = roms && !cancellable) { if (!roms || cancellable) onSkipOrCancel() } }
        }
    }
}

/** No ROM folder matched: fall back to the system folder picker. */
@Composable
fun NotFoundScreen(cancellable: Boolean, onPick: () -> Unit, onSearchAgain: () -> Unit, onCancel: () -> Unit) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Header("NO ROMS FOLDER FOUND",
            "RomCore looks for folders named ROMS (also _ROMs, Game ROMs, Retro ROMs, ROMs (SD)...) on internal storage and every SD card. Pick yours by hand.")
        FocusLine("PICK ROM FOLDER", Modifier.focusRequester(fr).width(460.dp), center = true, style = MaterialTheme.typography.headlineSmall) { onPick() }
        FocusLine("SEARCH AGAIN", Modifier.width(460.dp), center = true, style = MaterialTheme.typography.titleMedium) { onSearchAgain() }
        if (cancellable) FocusLine("CANCEL", Modifier.width(460.dp), center = true, style = MaterialTheme.typography.titleMedium) { onCancel() }
    }
}

/** Several `_romcore-save-data` folders were found: pick the one to use for all save data and save states. */
@Composable
fun SaveChooseScreen(
    candidates: List<SaveDiscovery.Candidate>, cancellable: Boolean,
    onChoose: (String) -> Unit, onSearchAgain: () -> Unit, onPick: () -> Unit, onCancel: () -> Unit
) {
    val fr = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(8.dp))
        Text("WHICH SAVE DATA FOLDER?", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Text("Found ${candidates.size} \"${SaveRoot.FOLDER_NAME}\" folders. RomCore keeps ALL saves and save states in one of them. Pick one; you can change it later in SETTINGS.",
            Modifier.widthIn(max = 720.dp).padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            itemsIndexed(candidates, key = { _, c -> c.path }) { i, c ->
                Column {
                    FocusLine(c.path, if (i == 0) Modifier.focusRequester(fr) else Modifier, right = SaveRoot.sizeLabel(c.bytes)) { onChoose(c.path) }
                    Text("${c.dataFiles}${if (c.capped) "+" else ""} save files   last changed ${saveDate(c.lastModified)}",
                        Modifier.padding(start = 28.dp, bottom = 6.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item { FocusLine("SEARCH AGAIN", style = MaterialTheme.typography.titleSmall) { onSearchAgain() } }
            item { FocusLine("PICK ANOTHER FOLDER...", style = MaterialTheme.typography.titleSmall) { onPick() } }
            item { FocusLine("CANCEL", style = MaterialTheme.typography.titleSmall, dim = !cancellable) { if (cancellable) onCancel() } }
        }
    }
}

internal fun saveDate(ms: Long): String =
    if (ms <= 0) "-" else java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.ROOT).format(java.util.Date(ms))
