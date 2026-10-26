package com.noryan.romcore

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.CoreRegistry
import com.noryan.romcore.api.LaunchRequest
import com.noryan.romcore.api.System
import com.noryan.romcore.api.crash.AutoRetry
import com.noryan.romcore.api.crash.CrashText
import com.noryan.romcore.api.crash.RomCoreCrashGuard
import com.noryan.romcore.api.crash.RunMarker
import com.noryan.romcore.api.menu.ResumePoint
import com.noryan.romcore.api.save.SaveMigration
import com.noryan.romcore.api.save.SaveRoot
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import com.noryan.romcore.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import java.io.File
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State of the collection editor: new (collectionId == null) or editing an existing one. */
data class CollectionEditor(val collectionId: Long?, val name: String, val selection: SelectionState, val error: String? = null)

/** First-run / re-pick flow. The screen shown is a pure function of this state plus the All files grant. */
sealed interface Setup {
    data class Searching(val kind: FolderSearch.Kind) : Setup
    data class Choose(val kind: FolderSearch.Kind, val candidates: List<FolderSearch.Candidate>, val cancellable: Boolean) : Setup
    /** Nothing found for [kind]: offer the folder picker (ROMs only; a missing BIOS folder is skipped silently). */
    data class NotFound(val kind: FolderSearch.Kind, val cancellable: Boolean) : Setup
    /** Several `_romcore-save-data` folders were found: pick one. [cancellable] = a folder is already in use (keep it). */
    data class ChooseSave(val candidates: List<SaveDiscovery.Candidate>, val cancellable: Boolean) : Setup
    data object Ready : Setup
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ra = app as RomCoreApp
    private val dao = ra.db.games()
    private val cdao = ra.db.collections()
    val prefs = ra.prefs

    val games: StateFlow<List<GameEntity>> = dao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    /** Collections in tab order; null until the first DB read (so a saved collection tab is not dropped too early). */
    val collections: StateFlow<List<CollectionEntity>?> =
        cdao.observeCollections().map<List<CollectionEntity>, List<CollectionEntity>?> { it }.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    /** collection id -> ids of its games (membership rows are removed by the DB when a game or collection disappears). */
    val members: StateFlow<Map<Long, Set<Long>>> =
        cdao.observeMembers().map { rows -> rows.groupBy({ it.collectionId }, { it.gameId }).mapValues { it.value.toSet() } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())
    /** Saved selected tab (0 = GAMES or a collection id); UI resolves it through [CollectionRules.resolveTab]. */
    val selectedTab = MutableStateFlow(prefs.selectedTab)
    /** The Settings screen replaces the games area while true. */
    val settingsOpen = MutableStateFlow(false)
    /** The NEW COLLECTION / EDIT GAMES screen; null = closed. */
    val editor = MutableStateFlow<CollectionEditor?>(null)
    val accessGranted = MutableStateFlow(Environment.isExternalStorageManager())
    val romDir = MutableStateFlow(prefs.romDir)
    val biosDir = MutableStateFlow(prefs.biosDir)
    val setup = MutableStateFlow<Setup>(Setup.Ready)
    val scanning = MutableStateFlow(false)
    val scanProgress = MutableStateFlow(0)
    val status = MutableStateFlow("")
    val launching = MutableStateFlow(false)
    /** Quiet inline BIOS import results ("PS2 BIOS: IMPORTED ...") and a one-line note for the BIOS search. */
    val biosLines = MutableStateFlow<List<String>>(emptyList())
    val biosNote = MutableStateFlow("")
    /** Inline "GAME CLOSED UNEXPECTEDLY" notice on the list (title line, hint line); null = none. Dismissed with A. */
    val crashNotice = MutableStateFlow<Pair<String, String>?>(null)
    /** Bumped when per-system overrides change so the UI re-reads prefs. */
    val prefsTick = MutableStateFlow(0)

    /** Debug builds only: search these roots instead of the mounted volumes (adb: --es romcore.debug.roots /a:/b). */
    @Volatile var debugRoots: List<File>? = null
    private var searchJob: Job? = null

    // ---- portable save-data folder (see romcore-api/SAVEDATA.md) ----
    /** The folder in use: path + size/count (null until resolved). */
    val saveInfo = MutableStateFlow<SaveDiscovery.Candidate?>(null)
    /** One-line status for Settings ("READY", "SEARCHING", "MIGRATED 12 FILES"...). */
    val saveStatus = MutableStateFlow("")
    /** Non-null: the portable folder could not be created/used, app storage is used instead. */
    val saveWarning = MutableStateFlow<String?>(null)
    /** Other copies of the folder found while one is in use: offered in Settings, never forced. */
    val saveOthers = MutableStateFlow<List<SaveDiscovery.Candidate>>(emptyList())
    private var saveJob: Job? = null
    private var saveResolved = false
    private val migrateMutex = Mutex()

    val cores: List<CoreDescriptor> get() = CoreRegistry.all()

    init { if (accessGranted.value) begin() }

    /** Called on every resume: the All files switch is flipped in a system screen outside the app. */
    /** Called on every resume / new intent: report core processes that died without a clean Close Game. */
    fun checkCrashes() {
        val ctx: Context = getApplication()
        // The save folder vanished (SD card pulled, folder deleted/moved): search again.
        if (saveResolved && saveJob?.isActive != true) viewModelScope.launch {
            val gone = withContext(Dispatchers.IO) { SaveRoot.persisted(ctx)?.let { !File(it).isDirectory } ?: !SaveRoot.isFallback(ctx) }
            if (gone) { saveStatus.value = "FOLDER MISSING - SEARCHING"; resolveSaveRoot() }
        }
        viewModelScope.launch {
            val reports = withContext(Dispatchers.IO) { runCatching { RomCoreCrashGuard.scan(ctx) }.getOrDefault(emptyList()) }
            val r = reports.firstOrNull() ?: return@launch
            val retryPrefs = ctx.getSharedPreferences("romcore_auto_retry", Context.MODE_PRIVATE)
            val now = java.lang.System.currentTimeMillis()
            val retry = AutoRetry.shouldRetry(r.reason, r.marker.startedAt, r.diedAt, r.marker.title,
                retryPrefs.getString("title", null), retryPrefs.getLong("at", 0), now)
            val game = if (retry) withContext(Dispatchers.IO) { dao.all().firstOrNull { it.title == r.marker.title } } else null
            if (game != null) {
                // Killed for low memory right after starting: try the same game once more, automatically.
                retryPrefs.edit().putString("title", r.marker.title).putLong("at", now).apply()
                crashNotice.value = CrashText.title(r.marker) to "Out of memory. Starting it again automatically..."
                launch(ctx, game)
            } else {
                // The system took a backgrounded game away: put it back at the resume point written when it left the screen.
                val lost = r.reason?.startsWith("Out of memory") == true || r.reason?.startsWith("Killed") == true
                val back = if (lost) withContext(Dispatchers.IO) { resumableGame(ctx, r.marker) } else null
                if (back != null) {
                    ResumePoint.clear(ctx, r.marker.core, back.second)
                    crashNotice.value = CrashText.title(r.marker) to "The system closed it in the background. Restoring where you left off..."
                    launch(ctx, back.first, resume = true)
                } else crashNotice.value = CrashText.title(r.marker) to CrashText.hint(r.reason)
            }
        }
    }

    /** The library entry and ROM path of a dead game that left a resume point, or null. */
    private suspend fun resumableGame(ctx: Context, m: RunMarker): Pair<GameEntity, String>? {
        val g = dao.all().firstOrNull { it.title == m.title } ?: return null
        val path = RomResolver.resolve(ctx, g)?.path ?: return null
        return if (ResumePoint.exists(ctx, m.core, path)) g to path else null
    }

    fun dismissCrashNotice() { crashNotice.value = null }

    fun refreshAccess() {
        val g = Environment.isExternalStorageManager()
        if (g == accessGranted.value) return
        accessGranted.value = g
        if (g) begin()
    }

    private fun begin() {
        val rd = prefs.romDir
        if (rd != null && File(rd).isDirectory) {
            setup.value = Setup.Ready
            rescan()
            resolveSaveRoot()
            if (!prefs.biosSearched) searchBios(first = true) else runBiosImport()
        } else startRomSearch(cancellable = false)
    }

    private fun roots(): List<File> {
        debugRoots?.let { return it }
        val ctx: Context = getApplication()
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val vols = sm.storageVolumes.filter { it.state == Environment.MEDIA_MOUNTED || it.state == Environment.MEDIA_MOUNTED_READ_ONLY }
            .mapNotNull { it.directory }
        return (vols + Environment.getExternalStorageDirectory()).distinctBy { it.path }
    }

    fun startRomSearch(cancellable: Boolean = true) {
        searchJob?.cancel()
        setup.value = Setup.Searching(FolderSearch.Kind.ROMS)
        searchJob = viewModelScope.launch {
            val dec = withContext(Dispatchers.IO) { FolderSearch.decide(FolderSearch.search(roots(), FolderSearch.Kind.ROMS, SystemDetector.scanExtensions())) }
            when (dec) {
                is FolderSearch.Decision.Single -> chooseRom(dec.candidate.path)
                is FolderSearch.Decision.Multiple -> setup.value = Setup.Choose(FolderSearch.Kind.ROMS, dec.candidates, cancellable && prefs.romDir != null)
                FolderSearch.Decision.None -> setup.value = Setup.NotFound(FolderSearch.Kind.ROMS, cancellable && prefs.romDir != null)
            }
        }
    }

    fun searchBios(first: Boolean) {
        searchJob?.cancel()
        biosNote.value = ""
        setup.value = Setup.Searching(FolderSearch.Kind.BIOS)
        searchJob = viewModelScope.launch {
            val dec = withContext(Dispatchers.IO) { FolderSearch.decide(FolderSearch.search(roots(), FolderSearch.Kind.BIOS, emptySet())) }
            when (dec) {
                is FolderSearch.Decision.Single -> chooseBios(dec.candidate.path)
                is FolderSearch.Decision.Multiple -> setup.value = Setup.Choose(FolderSearch.Kind.BIOS, dec.candidates, cancellable = !first)
                FolderSearch.Decision.None -> {
                    // BIOS is optional until a game needs it: no prompt, no screen.
                    prefs.biosSearched = true
                    if (!first) biosNote.value = "NO BIOS FOLDER FOUND"
                    setup.value = Setup.Ready
                }
            }
        }
    }

    fun chooseRom(path: String) {
        searchJob?.cancel()
        val f = File(path)
        if (!f.isDirectory || f.listFiles() == null) { status.value = "CAN'T READ THAT FOLDER"; setup.value = Setup.NotFound(FolderSearch.Kind.ROMS, prefs.romDir != null); return }
        prefs.romDir = path; romDir.value = path
        rescan()
        resolveSaveRoot()
        if (!prefs.biosSearched) searchBios(first = true) else setup.value = Setup.Ready
    }

    /** From the SAF picker: a tree uri is only used to learn its real path. */
    fun pickRom(uri: Uri) {
        val p = SafPathUtils.realPathFromDocumentUri(uri)
        if (p == null) { status.value = "CAN'T READ THAT FOLDER"; return }
        chooseRom(p)
    }

    fun chooseBios(path: String) {
        searchJob?.cancel()
        prefs.biosDir = path; biosDir.value = path; prefs.biosSearched = true
        setup.value = Setup.Ready
        runBiosImport()
    }

    fun pickBios(uri: Uri) {
        val p = SafPathUtils.realPathFromDocumentUri(uri)
        if (p == null || !File(p).isDirectory) { biosNote.value = "CAN'T READ THAT FOLDER"; return }
        chooseBios(p)
    }

    fun skipBios() { searchJob?.cancel(); prefs.biosSearched = true; setup.value = Setup.Ready }

    // ---- save-data folder: discovery, selection, migration ----

    /** Automatic: runs on every start once the ROM folder is known (and when the folder vanished). */
    fun resolveSaveRoot() {
        saveJob?.cancel()
        val ctx: Context = getApplication()
        saveJob = viewModelScope.launch {
            saveStatus.value = "SEARCHING"
            val persisted = withContext(Dispatchers.IO) { SaveRoot.persisted(ctx)?.let(::File)?.takeIf { it.isDirectory }?.let(SaveDiscovery::describe) }
            val dec = withContext(Dispatchers.IO) { SaveDiscovery.decide(persisted, SaveDiscovery.search(roots())) }
            when (dec) {
                is SaveDiscovery.Decision.Keep -> { saveOthers.value = dec.others; useSaveRoot(persisted!!.path) }
                is SaveDiscovery.Decision.Use -> { saveOthers.value = dec.others; useSaveRoot(dec.candidate.path) }
                is SaveDiscovery.Decision.Choose -> {
                    saveOthers.value = emptyList()
                    if (persisted != null) useSaveRoot(persisted.path) // keep working while the user decides
                    setup.first { it is Setup.Ready }
                    setup.value = Setup.ChooseSave(dec.candidates, cancellable = persisted != null)
                }
                SaveDiscovery.Decision.Create -> createSaveRoot()
            }
        }
    }

    private suspend fun createSaveRoot() {
        val ctx: Context = getApplication()
        val rd = romDir.value ?: prefs.romDir
        val target = rd?.let { File(it, SaveRoot.FOLDER_NAME) }
        if (target != null && withContext(Dispatchers.IO) { SaveRoot.ensureLayout(target) }) useSaveRoot(target.path)
        else useFallbackRoot("Could not create $target - using app storage until it can be created")
    }

    private suspend fun useFallbackRoot(why: String) {
        val ctx: Context = getApplication()
        withContext(Dispatchers.IO) { SaveRoot.clear(ctx); SaveRoot.ensureLayout(SaveRoot.fallbackRoot(ctx)) }
        saveWarning.value = why.uppercase()
        saveResolved = true
        refreshSaveInfo()
        migrateSaves()
    }

    private suspend fun useSaveRoot(path: String) {
        val ctx: Context = getApplication()
        val ok = withContext(Dispatchers.IO) { SaveRoot.set(ctx, File(path)) }
        if (!ok) { useFallbackRoot("Could not use $path - using app storage"); return }
        saveWarning.value = null
        saveResolved = true
        refreshSaveInfo()
        migrateSaves()
    }

    private suspend fun refreshSaveInfo() {
        val ctx: Context = getApplication()
        val i = withContext(Dispatchers.IO) { SaveDiscovery.describe(SaveRoot.current(ctx)) }
        saveInfo.value = i
        saveStatus.value = if (saveWarning.value != null) "USING APP STORAGE" else "READY"
    }

    /** Manual SEARCH AGAIN from Settings: always lets the user choose when more than one folder exists. */
    fun searchSaveAgain() {
        saveJob?.cancel()
        val ctx: Context = getApplication()
        saveJob = viewModelScope.launch {
            saveStatus.value = "SEARCHING"
            val (cands, cur) = withContext(Dispatchers.IO) {
                val cur = SaveRoot.persisted(ctx)?.let(::File)?.takeIf { it.isDirectory }?.let(SaveDiscovery::describe)
                (SaveDiscovery.search(roots()) + listOfNotNull(cur)).distinctBy { it.path } to cur
            }
            when {
                cands.isEmpty() -> createSaveRoot()
                cands.size == 1 -> { useSaveRoot(cands[0].path); saveStatus.value = if (cur?.path == cands[0].path) "NO OTHER FOLDER FOUND" else "READY" }
                else -> { setup.first { it is Setup.Ready }; setup.value = Setup.ChooseSave(SaveDiscovery.order(cands), cancellable = cur != null) ; saveStatus.value = "READY" }
            }
        }
    }

    fun chooseSave(path: String) {
        saveJob?.cancel()
        setup.value = Setup.Ready
        saveJob = viewModelScope.launch { useSaveRoot(path) }
    }

    fun pickSave(uri: Uri) {
        val p = SafPathUtils.realPathFromDocumentUri(uri)
        if (p == null || !File(p).isDirectory) { saveStatus.value = "CAN'T READ THAT FOLDER"; return }
        chooseSave(SaveDiscovery.resolvePicked(File(p)).path)
    }

    /** Move older app-private save data into the root (idempotent; also refreshes the game-key table the cores read). */
    private fun migrateSaves() {
        val ctx: Context = getApplication()
        viewModelScope.launch(Dispatchers.IO) {
            migrateMutex.withLock {
                runCatching {
                    val keys = SaveRoot.refreshGameKeys(ctx, dao.all().map { g ->
                        val p = g.realPath ?: g.uri
                        SaveRoot.KeyInput(p, g.isDir, g.sizeBytes) { SaveRoot.quickHash(File(p)) }
                    })
                    val rep = SaveMigration.run(ctx, SaveRoot.current(ctx), keys.entries.associate { SaveRoot.legacyHash(it.key) to it.value })
                    refreshSaveInfo()
                    if (rep.changed || rep.failed > 0) saveStatus.value = (if (rep.failed > 0) "MIGRATION: ${rep.failed} FAILED, " else "MIGRATED ") +
                        "${rep.moved + rep.conflicts} FILES" + if (rep.conflicts > 0) " (${rep.conflicts} CONFLICTS KEPT)" else ""
                }
            }
        }
    }

    fun cancelSetup() { searchJob?.cancel(); setup.value = Setup.Ready }

    fun runBiosImport() {
        val dir = prefs.biosDir ?: return
        viewModelScope.launch {
            val r = withContext(Dispatchers.IO) { BiosImporter.import(getApplication(), File(dir)) }
            biosLines.value = r.lines
            prefsTick.value++
        }
    }

    fun rescan() {
        val root = romDir.value?.let(::File) ?: return
        if (scanning.value) return
        viewModelScope.launch {
            scanning.value = true; scanProgress.value = 0; status.value = "SCANNING"
            try {
                val prev = withContext(Dispatchers.IO) { dao.all() }
                val prevMap = HashMap<String, GameEntity>().also { m -> prev.forEach { m[it.realPath ?: it.uri] = it } }
                val res = RomScanner.scan(root, prevMap, prefs.detectVersion == SystemDetector.VERSION) { scanProgress.value = it }
                withContext(Dispatchers.IO) {
                    val (existing, fresh) = res.games.partition { it.id != 0L }
                    dao.updateAll(existing); dao.insertAll(fresh)
                    if (res.trustworthy) {
                        val keep = res.games.filter { it.id != 0L }.map { it.id }.toSet()
                        dao.deleteIds(prev.filter { it.id !in keep }.map { it.id })
                    }
                }
                if (res.trustworthy) prefs.detectVersion = SystemDetector.VERSION
                if (saveResolved) migrateSaves()
                status.value = if (res.trustworthy) "${res.games.size} GAMES" else "FOLDER EMPTY OR UNREADABLE"
            } catch (t: Throwable) {
                status.value = "SCAN FAILED"
            } finally { scanning.value = false }
        }
    }

    // ---- collections ----
    fun selectTab(id: Long) { selectedTab.value = id; prefs.selectedTab = id; settingsOpen.value = false }
    fun toggleWwFps() { prefs.wwFpsHack = !prefs.wwFpsHack; prefsTick.value++ }
    fun toggleSettings() { settingsOpen.value = !settingsOpen.value }
    /** The Home button while RomCore is the home app: back to the games list. */
    fun showHome() { settingsOpen.value = false; editor.value = null }

    private fun names(except: Long? = null) = collections.value.orEmpty().filter { it.id != except }.map { it.name }

    fun newCollection() {
        editor.value = CollectionEditor(null, CollectionRules.defaultName(names()), SelectionState())
        settingsOpen.value = false
    }

    fun editCollection(id: Long) {
        val c = collections.value.orEmpty().firstOrNull { it.id == id } ?: return
        val live = games.value.map { it.id }.toSet()
        editor.value = CollectionEditor(id, c.name, SelectionState(CollectionRules.prune(members.value[id].orEmpty(), live)))
        settingsOpen.value = false
    }

    fun updateEditor(f: (CollectionEditor) -> CollectionEditor) { editor.value = editor.value?.let(f)?.let { it.copy(error = null) } }
    fun cancelEditor() { editor.value = null }

    /** Validates and stores the editor; on a problem the editor stays open with [CollectionEditor.error] set. */
    fun saveEditor() {
        val e = editor.value ?: return
        val cols = collections.value.orEmpty()
        val err = CollectionRules.validateName(e.name, cols.map { it.id to it.name }, e.collectionId)?.text
            ?: if (!e.selection.canSave()) "PICK AT LEAST 1 GAME" else null
        if (err != null) { editor.value = e.copy(error = err); return }
        val name = CollectionRules.clean(e.name)
        val ids = CollectionRules.prune(e.selection.selected, games.value.map { it.id }.toSet())
        if (ids.isEmpty()) { editor.value = e.copy(error = "PICK AT LEAST 1 GAME"); return }
        editor.value = null
        viewModelScope.launch {
            if (e.collectionId == null) {
                val id = withContext(Dispatchers.IO) { cdao.create(name, ids) }
                selectTab(id)
            } else withContext(Dispatchers.IO) { cdao.update(e.collectionId, name, ids) }
        }
    }

    /** Rename only. Returns an error text or null. */
    fun renameCollection(id: Long, raw: String): String? {
        CollectionRules.validateName(raw, collections.value.orEmpty().map { it.id to it.name }, id)?.let { return it.text }
        val name = CollectionRules.clean(raw)
        viewModelScope.launch(Dispatchers.IO) { cdao.rename(id, name) }
        return null
    }

    fun deleteCollection(id: Long) {
        if (selectedTab.value == id) selectTab(CollectionRules.GAMES_TAB)
        viewModelScope.launch(Dispatchers.IO) { cdao.delete(id) }
    }

    fun setMember(cid: Long, gid: Long, on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { if (on) cdao.addMember(cid, gid) else cdao.removeMember(cid, gid) }
    }

    fun setGameCore(g: GameEntity, id: String?) { viewModelScope.launch(Dispatchers.IO) { dao.setCore(g.id, id) } }
    fun toggleFavorite(g: GameEntity) { viewModelScope.launch(Dispatchers.IO) { dao.setFavorite(g.id, !g.favorite) } }

    fun coreFor(g: GameEntity): CoreChoice? = CoreRouter.choose(g, prefs)

    fun launch(ctx: Context, g: GameEntity, resume: Boolean = false) {
        if (launching.value) return
        val choice = coreFor(g)
        val core = choice?.core
        val sys = runCatching { System.valueOf(g.system) }.getOrNull()
        if (core == null || sys == null) { toast(ctx, "No core installed for ${g.system}"); return }
        viewModelScope.launch {
            launching.value = true
            try {
                // Idempotent copy of the user's BIOS / keys / firmware into the cores before they start.
                if (prefs.biosDir != null) withContext(Dispatchers.IO) { runCatching { BiosImporter.import(ctx, File(prefs.biosDir!!)) } }
                if (core.id in LaunchMemory.HEAVY_CORES && LaunchMemory.isLow()) {
                    toast(ctx, "Freeing up memory first...")
                    LaunchMemory.awaitRoom() // gives up after a few seconds and launches anyway
                }
                val r = RomResolver.resolve(ctx, g)
                if (r == null) { toast(ctx, "Can't open ROM file"); return@launch }
                if (r.kind == "fd") { toast(ctx, "Turn on ALL FILES ACCESS in SETTINGS so this core can open the ROM"); return@launch }
                // Real path: cores get the path alone (a null uri makes them use it). Only a cache copy keeps a uri fallback.
                val req = LaunchRequest(romPath = r.path, romUri = if (r.kind == "real") null else g.uri, system = sys,
                    resumeState = resume, subCoreId = choice.sub?.id)
                // The save folder must be decided before the core starts (a first-ever launch can race the discovery).
                withTimeoutOrNull(8000) { saveJob?.join() }
                val gameKey = withContext(Dispatchers.IO) { SaveRoot.gameKey(ctx, g.realPath ?: g.uri) }
                val intent = SaveRoot.stamp(ctx, RomCoreCrashGuard.stamp(core.launchIntent(ctx, req), core.id, g.title), gameKey)
                crashNotice.value = null
                withContext(Dispatchers.IO) { dao.markPlayed(g.id, java.lang.System.currentTimeMillis()) }
                if (ctx !is android.app.Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Restoring a game whose process died: its old task (a dead record of the same singleTask activity) would swallow this
                // launch and re-create the dead one, which the crash guard refuses. Clear it so the restore starts fresh.
                if (resume) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                ctx.startActivity(intent)
            } catch (t: Throwable) {
                toast(ctx, "Launch failed: ${t.message ?: t.javaClass.simpleName}")
            } finally { launching.value = false }
        }
    }

    private fun toast(ctx: Context, m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()
}
