package com.noryan.romcore.api.input

import com.noryan.romcore.api.Pad
import com.noryan.romcore.api.System

/** One line of the "Change Controller Mapping" screen. */
data class MappingRow(val pad: Pad, val controls: List<PhysicalControl>) {
    val label: String get() = if (controls.isEmpty()) "Unbound" else controls.joinToString(" / ") { it.label() }
}

/**
 * Persistent per-device (and optional per-system) overrides on top of the auto-mapped [DeviceProfiles].
 * Resolution order: auto profile <- device-wide overrides <- device+system overrides.
 * Storage format per scope: "K96=B;A15-=NONE" under key "map|<deviceKey>|<SYSTEM or *>".
 */
class MappingStore(private val kv: KeyValueStore) {
    /** Bumped on every write; [InputRouter] uses it to drop cached mappers. */
    @Volatile var revision: Int = 0
        private set

    private fun k(dev: DeviceInfo, system: System?) = "map|${dev.key}|${system?.name ?: "*"}"

    fun overrides(dev: DeviceInfo, system: System?): Map<PhysicalControl, Pad?> {
        val s = kv.get(k(dev, system)) ?: return emptyMap()
        val out = LinkedHashMap<PhysicalControl, Pad?>()
        for (part in s.split(';')) {
            val i = part.indexOf('=')
            if (i <= 0) continue
            val c = PhysicalControl.decode(part.substring(0, i)) ?: continue
            val v = part.substring(i + 1)
            out[c] = if (v == "NONE") null else runCatching { Pad.valueOf(v) }.getOrNull() ?: continue
        }
        return out
    }

    private fun write(dev: DeviceInfo, system: System?, ov: Map<PhysicalControl, Pad?>) {
        if (ov.isEmpty()) kv.remove(k(dev, system))
        else kv.put(k(dev, system), ov.entries.joinToString(";") { "${it.key.encode()}=${it.value?.name ?: "NONE"}" })
        revision++
    }

    /** Auto profile + overrides. [system] = null gives the device-wide profile. */
    fun resolve(dev: DeviceInfo, system: System?): InputProfile {
        var p = DeviceProfiles.forDevice(dev).withOverrides(overrides(dev, null))
        if (system != null) p = p.withOverrides(overrides(dev, system))
        return p
    }

    /** Rows for every user-mappable pad, in display order. */
    fun rows(dev: DeviceInfo, system: System?): List<MappingRow> {
        val src = resolve(dev, system).padSources
        return MAPPABLE_PADS.map { MappingRow(it, src[it].orEmpty()) }
    }

    /**
     * Bind [control] to [target]. Existing controls of the same kind (key vs axis) bound to [target] are unbound, or
     * given [control]'s previous pad (swap) so the layout never ends up with a hole.
     */
    fun rebind(dev: DeviceInfo, system: System?, target: Pad, control: PhysicalControl) {
        val eff = resolve(dev, system).bindings
        val ov = LinkedHashMap(overrides(dev, system))
        val previousPad = eff[control]
        val displaced = eff.filter { it.value == target && it.key != control && it.key.isAxis == control.isAxis }.keys
        var swapped = false
        for (c in displaced) {
            if (!swapped && previousPad != null && previousPad != target) { ov[c] = previousPad; swapped = true } else ov[c] = null
        }
        ov[control] = target
        write(dev, system, ov)
    }

    fun unbind(dev: DeviceInfo, system: System?, control: PhysicalControl) {
        val ov = LinkedHashMap(overrides(dev, system)); ov[control] = null; write(dev, system, ov)
    }

    /** Remove all overrides for the scope (system = null: device-wide). */
    fun reset(dev: DeviceInfo, system: System?) = write(dev, system, emptyMap())

    companion object {
        /** Pads shown in the mapping UI (HOME is a hotkey concern, not remappable here). */
        val MAPPABLE_PADS: List<Pad> = listOf(
            Pad.A, Pad.B, Pad.X, Pad.Y, Pad.L1, Pad.R1, Pad.L2, Pad.R2, Pad.L3, Pad.R3, Pad.START, Pad.SELECT,
            Pad.DPAD_UP, Pad.DPAD_DOWN, Pad.DPAD_LEFT, Pad.DPAD_RIGHT,
            Pad.LS_UP, Pad.LS_DOWN, Pad.LS_LEFT, Pad.LS_RIGHT, Pad.RS_UP, Pad.RS_DOWN, Pad.RS_LEFT, Pad.RS_RIGHT,
        )

        /** Display name for a pad under a system family, e.g. "A (Circle)" on PlayStation systems. */
        fun padLabel(pad: Pad, system: System?): String {
            if (system?.family == System.Family.PLAYSTATION) {
                com.noryan.romcore.api.PsFace.of(pad)?.let { return "${pad.name} (${it.name.lowercase().replaceFirstChar(Char::uppercase)})" }
            }
            return pad.name.replace('_', ' ')
        }
    }
}
