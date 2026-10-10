package com.noryan.romcore.core.primehack

import java.io.File

/**
 * Generated controller profiles + engine defaults, written into Dolphin's user Config/ before every launch.
 *
 * Inputs are NOT bound to a physical Android device: RomCore's InputRouter already translated the real pad into
 * canonical buttons, and PrimeHackSession feeds them into the native virtual device "RomCore Pad". The profile
 * therefore is identical on every handheld, and always honours the user's "Change Controller Mapping" choices.
 *
 * Layout (Nintendo labels = labels on the physical buttons):
 *   Wii  (Prime 3 / Trilogy; Wiimote + Nunchuk):  A fire (A or R2) | B jump (B or L1) | Y = Nunchuk C (morph ball)
 *        L2 = Nunchuk Z (lock on) | left stick = move | right stick (+ gyro) = camera/aim | START/SELECT = 1/2
 *        D-pad = visors, X + D-pad = beams (X alone = spring ball / grapple) | L/R of D-pad hat is also -/+ via Select.
 *        (this is the AYN-Thor-tuned "odin.ini" scheme from RomRunner, re-expressed on canonical inputs)
 *   GC   (Prime 1/2 and every other GC game):    face buttons 1:1 | Z = R1 | L/R = L2/R2 (digital+analog)
 *        D-pad = visors, L1 + D-pad = C-stick beams (Prime) | right stick = camera (Prime) or C-stick (other games)
 */
object PrimeHackProfiles {
    const val DEVICE = "RomCore/0/RomCore Pad"

    private fun b(name: String) = "`$name`"

    /** Camera Control stick = right stick, plus gyro when enabled. */
    private fun cam(dir: String, gyro: Boolean) =
        if (gyro) "${b("RS $dir")} | ${b("Gyro $dir")}" else b("RS $dir")

    fun gcProfile(prime: Boolean, gyro: Boolean): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        m["Device"] = DEVICE
        m["Buttons/A"] = b("A"); m["Buttons/B"] = b("B"); m["Buttons/X"] = b("X"); m["Buttons/Y"] = b("Y")
        m["Buttons/Z"] = b("R1"); m["Buttons/Start"] = b("Start")
        m["Triggers/L"] = b("L2"); m["Triggers/R"] = b("R2")
        m["Triggers/L-Analog"] = b("L Trigger"); m["Triggers/R-Analog"] = b("R Trigger")
        for (d in listOf("Up", "Down", "Left", "Right")) m["Main Stick/$d"] = b("LS $d")
        if (prime) {
            for (d in listOf("Up", "Down", "Left", "Right")) {
                m["D-Pad/$d"] = "${b(d)} & !${b("L1")}"
                m["C-Stick/$d"] = "${b(d)} & ${b("L1")}"
                m["Camera Control/$d"] = cam(d, gyro)
            }
            m["Camera Control/Dead Zone"] = "10."
            m["Camera Control/Reset Camera Pitch"] = b("R3")
            m["PrimeHack/Spring Ball"] = b("L3")
            m["PrimeHack/Mode"] = "1"
        } else {
            for (d in listOf("Up", "Down", "Left", "Right")) {
                m["D-Pad/$d"] = b(d)
                m["C-Stick/$d"] = b("RS $d")
            }
            m["PrimeHack/Mode"] = "1"
        }
        return m
    }

    fun wiiProfile(gyro: Boolean): LinkedHashMap<String, String> {
        val m = LinkedHashMap<String, String>()
        m["Device"] = DEVICE
        m["Buttons/A"] = "${b("A")} ^ ${b("R Trigger")}"
        m["Buttons/B"] = "${b("B")} ^ ${b("L1")}"
        m["Buttons/1"] = b("Start"); m["Buttons/2"] = b("Select")
        m["Buttons/-"] = b("Left"); m["Buttons/+"] = b("Right")
        m["D-Pad/Down"] = b("R1")
        m["Extension"] = "Nunchuk"
        m["Extension/Attach MotionPlus"] = "False"
        m["Nunchuk/Buttons/C"] = b("Y"); m["Nunchuk/Buttons/Z"] = b("L Trigger")
        for (d in listOf("Up", "Down", "Left", "Right")) m["Nunchuk/Stick/$d"] = b("LS $d")
        // PrimeHack: X + D-pad = beams, D-pad alone = visors (same as the Thor "odin" profile).
        val x = b("X")
        m["PrimeHack/Beam 1"] = "${b("Up")}&$x"; m["PrimeHack/Beam 2"] = "${b("Right")}&$x"
        m["PrimeHack/Beam 3"] = "${b("Down")}&$x"; m["PrimeHack/Beam 4"] = "${b("Left")}&$x"
        m["PrimeHack/Visor 1"] = "${b("Up")}&!$x"; m["PrimeHack/Visor 2"] = "${b("Right")}&!$x"
        m["PrimeHack/Visor 3"] = "${b("Down")}&!$x"; m["PrimeHack/Visor 4"] = "${b("Left")}&!$x"
        m["PrimeHack/AltProfile"] = "Disabled"
        m["PrimeHack/Camera Sensitivity"] = "12."
        m["PrimeHack/Cursor Sensitivity"] = "23."
        for (d in listOf("Up", "Down", "Left", "Right")) m["Camera Control/$d"] = cam(d, gyro)
        m["Camera Control/Dead Zone"] = "10."
        m["Camera Control/Modifier/Range"] = "50."
        m["PrimeHack/Mode"] = "1"
        m["PrimeHack/Spring Ball"] = x
        m["PrimeHack/Grapple Lasso"] = x
        // Pointer / motion tuning inherited from the Thor profile.
        m["IR/Vertical Offset"] = "0."; m["IR/Total Yaw"] = "20.5"; m["IR/Total Pitch"] = "22."
        m["Shake/Intensity"] = "34."; m["Tilt/Modifier/Range"] = "50."
        m["Swing/Distance"] = "4."; m["Swing/Speed"] = "10.5"; m["Swing/Return Speed"] = "40."; m["Swing/Angle"] = "2.5"
        m["IMUIR/Enabled"] = "True"; m["IMUIR/Accelerometer Influence"] = "0."; m["IMUGyroscope/Dead Zone"] = "19.5"
        m["Nunchuk/Stick/Modifier/Range"] = "50."; m["Nunchuk/Tilt/Modifier/Range"] = "50."
        m["Rumble/Motor"] = "`Android/0/Device Sensors:Motor 0`"
        return m
    }

    // ---- ini helpers (merge keys into one section, leave everything else Dolphin wrote) -----------------------

    fun mergeSection(file: File, section: String, updates: Map<String, String>) {
        val lines = (if (file.exists()) file.readText() else "").lines().toMutableList()
        val header = "[$section]"
        var start = lines.indexOfFirst { it.trim() == header }
        if (start == -1) {
            if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
            lines.add(header); start = lines.size - 1
        }
        var end = lines.size
        for (i in start + 1 until lines.size) if (lines[i].trim().startsWith("[")) { end = i; break }
        val remaining = LinkedHashMap(updates)
        for (i in start + 1 until end) {
            val t = lines[i].trim()
            val eq = t.indexOf('=')
            if (eq <= 0) continue
            val key = t.substring(0, eq).trim()
            remaining.remove(key)?.let { lines[i] = "$key = $it" }
        }
        var at = end
        // keep a trailing blank line out of the section
        while (at > start + 1 && lines[at - 1].isBlank()) at--
        for ((k, v) in remaining) { lines.add(at, "$k = $v"); at++ }
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString("\n").trimEnd() + "\n")
    }

    fun setValue(file: File, section: String, key: String, value: String) = mergeSection(file, section, mapOf(key to value))
}
