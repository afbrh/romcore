package com.noryan.romcore.core.watermelonds

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class WatermelonDsSavesTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun findsOnlySavesBesideDsRoms() {
        val r = tmp.newFolder("roms")
        File(r, "Zelda PH.nds").writeText("rom"); File(r, "Zelda PH.sav").writeText("s")
        File(r, "Other.gba").writeText("rom"); File(r, "Other.sav").writeText("s")      // not a DS rom
        File(r, "Orphan.sav").writeText("s")
        File(r, "_romcore-save-data").mkdirs(); File(r, "_romcore-save-data/Zelda PH.sav").writeText("s")
        File(r, "sub").mkdirs(); File(r, "sub/Game.NDS").writeText("rom"); File(r, "sub/Game.SAV").writeText("s")
        val found = WatermelonDsSaves.findRomSideSaves(r).map { it.name }.sorted()
        assertEquals(listOf("Game.SAV", "Zelda PH.sav"), found)
    }
}
