package com.noryan.romcore

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.CoreRegistry
import com.noryan.romcore.api.LaunchRequest
import com.noryan.romcore.api.System
import com.noryan.romcore.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val ra = app as RomCoreApp
    private val dao = ra.db.games()
    val prefs = ra.prefs

    val games: StateFlow<List<GameEntity>> = dao.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val treeUri = MutableStateFlow(prefs.treeUri)
    val scanning = MutableStateFlow(false)
    val scanProgress = MutableStateFlow(0)
    val status = MutableStateFlow("")
    val launching = MutableStateFlow(false)
    /** Bumped when per-system overrides change so the UI re-reads prefs. */
    val prefsTick = MutableStateFlow(0)

    val cores: List<CoreDescriptor> get() = CoreRegistry.all()

    init { if (treeUri.value != null) rescan() }

    fun setFolder(uri: Uri) {
        val ctx: Context = getApplication()
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        prefs.treeUri = uri.toString(); treeUri.value = uri.toString()
        rescan()
    }

    fun rescan() {
        val tree = treeUri.value?.let(Uri::parse) ?: return
        if (scanning.value) return
        viewModelScope.launch {
            scanning.value = true; scanProgress.value = 0; status.value = "SCANNING"
            try {
                val prev = withContext(Dispatchers.IO) { dao.all() }
                val prevMap = prev.associateBy { it.uri }
                val res = RomScanner.scan(getApplication(), tree, prevMap) { scanProgress.value = it }
                withContext(Dispatchers.IO) {
                    val (existing, fresh) = res.games.partition { it.id != 0L }
                    dao.updateAll(existing); dao.insertAll(fresh)
                    if (res.trustworthy) {
                        val keep = res.games.map { it.uri }.toSet()
                        dao.deleteIds(prev.filter { it.uri !in keep }.map { it.id })
                    }
                }
                status.value = if (res.trustworthy) "${res.games.size} GAMES" else "FOLDER EMPTY OR UNREADABLE"
            } catch (t: Throwable) {
                status.value = "SCAN FAILED"
            } finally { scanning.value = false }
        }
    }

    fun setGameCore(g: GameEntity, id: String?) { viewModelScope.launch(Dispatchers.IO) { dao.setCore(g.id, id) } }
    fun toggleFavorite(g: GameEntity) { viewModelScope.launch(Dispatchers.IO) { dao.setFavorite(g.id, !g.favorite) } }
    fun setSystemCore(s: System, id: String?) { prefs.setSystemCore(s, id); prefsTick.value++ }

    fun coreFor(g: GameEntity): CoreDescriptor? = CoreRouter.choose(g, prefs)

    fun launch(ctx: Context, g: GameEntity) {
        if (launching.value) return
        val core = coreFor(g)
        val sys = runCatching { System.valueOf(g.system) }.getOrNull()
        if (core == null || sys == null) { toast(ctx, "No core installed for ${g.system}"); return }
        viewModelScope.launch {
            launching.value = true
            try {
                val r = RomResolver.resolve(ctx, g)
                if (r == null) { toast(ctx, "Can't open ROM file"); return@launch }
                val req = LaunchRequest(romPath = r.path, romUri = g.uri, system = sys)
                val intent = core.launchIntent(ctx, req)
                if (intent.data == null && intent.clipData == null) {
                    intent.clipData = android.content.ClipData.newRawUri("rom", Uri.parse(g.uri))
                }
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                withContext(Dispatchers.IO) { dao.markPlayed(g.id, java.lang.System.currentTimeMillis()) }
                ctx.startActivity(intent)
            } catch (t: Throwable) {
                toast(ctx, "Launch failed: ${t.message ?: t.javaClass.simpleName}")
            } finally { launching.value = false }
        }
    }

    private fun toast(ctx: Context, m: String) = Toast.makeText(ctx, m, Toast.LENGTH_LONG).show()
}
