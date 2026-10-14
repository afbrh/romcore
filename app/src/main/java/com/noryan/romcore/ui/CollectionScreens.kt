package com.noryan.romcore.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noryan.romcore.MainViewModel
import com.noryan.romcore.api.System
import com.noryan.romcore.data.CollectionEntity
import com.noryan.romcore.data.CollectionRules
import com.noryan.romcore.ui.components.FocusLine
import com.noryan.romcore.ui.theme.HighlightColor

/** Which collection's tab-options panel is open under the tab row, and what it shows. */
data class TabOptions(val id: Long, val mode: Mode) {
    enum class Mode { MENU, RENAME, CONFIRM_DELETE }
}

/**
 * A name line: a FocusLine until activated (A / tap), then an in-place text field that raises the Android keyboard.
 * Focus leaving (D-pad up/down), Done on the IME or Back ends editing. The caller keeps the value (capped by [CollectionRules.clip]).
 */
@Composable
private fun NameField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    var editing by remember { mutableStateOf(false) }
    var wasEditing by remember { mutableStateOf(false) }
    val lineFocus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    if (!editing) {
        // Back on the plain line after typing: give it the focus again (the text field it replaces had it).
        LaunchedEffect(Unit) { if (wasEditing) { wasEditing = false; runCatching { lineFocus.requestFocus() } } }
        FocusLine("NAME", right = value.ifEmpty { "(TAP TO TYPE)" }, modifier = modifier.focusRequester(lineFocus), onClick = { editing = true })
        return
    }
    SideEffect { wasEditing = true }
    val fr = remember { FocusRequester() }
    var tf by remember { mutableStateOf(TextFieldValue(value, TextRange(0, value.length))) }
    var gained by remember { mutableStateOf(false) }
    BackHandler { editing = false; kb?.hide() }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() }; kb?.show() }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("NAME", style = MaterialTheme.typography.titleMedium.copy(color = HighlightColor))
        Spacer(Modifier.width(16.dp))
        BasicTextField(
            value = tf,
            onValueChange = { n ->
                val clipped = CollectionRules.clip(n.text)
                tf = n.copy(text = clipped, selection = TextRange(minOf(n.selection.start, clipped.length), minOf(n.selection.end, clipped.length)))
                onChange(clipped)
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleMedium.copy(color = HighlightColor),
            cursorBrush = SolidColor(HighlightColor),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { editing = false; kb?.hide() }),
            modifier = Modifier.weight(1f).background(Color(0xFF14141A)).padding(horizontal = 8.dp, vertical = 4.dp)
                .focusRequester(fr).onFocusChanged { if (it.isFocused) gained = true else if (gained && editing) { editing = false; wasEditing = false } },
        )
        Text("${tf.text.length}/${CollectionRules.MAX_NAME}", Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Inline fold-down under the tab row for one collection: RENAME, EDIT GAMES, DELETE (with an inline confirm). */
@Composable
fun CollectionOptions(vm: MainViewModel, c: CollectionEntity, mode: TabOptions.Mode, setMode: (TabOptions.Mode) -> Unit, close: () -> Unit) {
    val first = remember(mode) { FocusRequester() }
    LaunchedEffect(mode, c.id) { runCatching { first.requestFocus() } }
    var draft by remember(c.id) { mutableStateOf(c.name) }
    var error by remember(c.id, mode) { mutableStateOf<String?>(null) }
    val members by vm.members.collectAsStateWithLifecycle()
    val small = MaterialTheme.typography.titleSmall
    Column(Modifier.fillMaxWidth()) {
        when (mode) {
            TabOptions.Mode.MENU -> {
                FocusLine("RENAME", right = CollectionRules.tabLabel(c.name), indent = 28.dp, style = small, modifier = Modifier.focusRequester(first)) { draft = c.name; setMode(TabOptions.Mode.RENAME) }
                FocusLine("EDIT GAMES", right = "${members[c.id].orEmpty().size} GAMES", indent = 28.dp, style = small) { close(); vm.editCollection(c.id) }
                FocusLine("DELETE", indent = 28.dp, style = small) { setMode(TabOptions.Mode.CONFIRM_DELETE) }
            }
            TabOptions.Mode.RENAME -> {
                NameField(draft, { draft = it; error = null }, Modifier.focusRequester(first))
                error?.let { Text(it, Modifier.padding(start = 44.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
                FocusLine("SAVE NAME", indent = 28.dp, style = small) { error = vm.renameCollection(c.id, draft); if (error == null) close() }
                FocusLine("CANCEL", indent = 28.dp, style = small) { setMode(TabOptions.Mode.MENU) }
            }
            TabOptions.Mode.CONFIRM_DELETE -> {
                Text("DELETE COLLECTION? ${CollectionRules.tabLabel(c.name)}", Modifier.padding(start = 44.dp, top = 4.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                FocusLine("CANCEL", indent = 28.dp, style = small, modifier = Modifier.focusRequester(first)) { setMode(TabOptions.Mode.MENU) }
                FocusLine("YES, DELETE", indent = 28.dp, style = small) { close(); vm.deleteCollection(c.id) }
            }
        }
        Text("Games are never deleted, only the list.", Modifier.padding(start = 44.dp, bottom = 4.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** NEW COLLECTION / EDIT GAMES: name, multi-select of the whole library, SAVE / CANCEL. Replaces the games area; no popups. */
@Composable
fun CollectionEditorScreen(vm: MainViewModel) {
    val e by vm.editor.collectAsStateWithLifecycle()
    val games by vm.games.collectAsStateWithLifecycle()
    val ed = e ?: return
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { nameFocus.requestFocus() } }
    val small = MaterialTheme.typography.titleSmall
    val sel = ed.selection
    val actions: @Composable () -> Unit = {
        FocusLine("SAVE", right = "${sel.count} GAMES", dim = !sel.canSave(), indent = 12.dp, style = small) { vm.saveEditor() }
        FocusLine("CANCEL", indent = 12.dp, style = small) { vm.cancelEditor() }
    }
    LazyColumn(Modifier.fillMaxSize()) {
        item { Section(if (ed.collectionId == null) "NEW COLLECTION" else "EDIT COLLECTION") }
        item { NameField(ed.name, { n -> vm.updateEditor { it.copy(name = n) } }, Modifier.focusRequester(nameFocus)) }
        ed.error?.let { err -> item(key = "err") { Text(err, Modifier.padding(start = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) } }
        item { Text("${sel.count} SELECTED", Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        item { FocusLine("SELECT ALL", right = "${games.size} GAMES", indent = 12.dp, style = small) { vm.updateEditor { it.copy(selection = it.selection.selectAll(games.map { g -> g.id })) } } }
        item { FocusLine("CLEAR", indent = 12.dp, style = small) { vm.updateEditor { it.copy(selection = it.selection.clear()) } } }
        item { actions() }
        item { Section("GAMES") }
        items(games, key = { it.id }) { g ->
            val on = sel.has(g.id)
            FocusLine((if (on) "[X] " else "[ ] ") + g.title, right = runCatching { systemLabel(System.valueOf(g.system)) }.getOrDefault(g.system), marked = on) {
                vm.updateEditor { it.copy(selection = it.selection.toggle(g.id)) }
            }
        }
        item { Spacer(Modifier.height(6.dp)) }
        item { actions() }
    }
}
