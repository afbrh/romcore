package com.noryan.romcore.data

import com.noryan.romcore.api.CoreDescriptor
import com.noryan.romcore.api.System

/**
 * One line of Settings > INSTALLED CORES. A multi-core engine contributes one row per sub-core (named after the core
 * itself, with the version that core reports); its umbrella descriptor never gets a row. [coreId] is the owning
 * descriptor (for per-engine setup items such as keys / BIOS import).
 */
data class CoreRow(
    val key: String, val coreId: String, val name: String, val version: String,
    val systems: Set<System>, val extensions: Set<String>, val license: String?,
)

object CoreRows {
    fun of(cores: List<CoreDescriptor>, versionOf: (CoreDescriptor) -> String = { it.version }): List<CoreRow> =
        cores.flatMap { c ->
            if (c.subCores.isEmpty()) listOf(CoreRow(c.id, c.id, c.displayName, versionOf(c), c.systems, c.extensions, null))
            else c.subCores.map { s -> CoreRow("${c.id}:${s.id}", c.id, s.displayName, s.version, s.systems, s.extensions, s.license) }
        }
}
