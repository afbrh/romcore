package com.noryan.romcore

import org.junit.Assert.*
import org.junit.Test

class LaunchMemoryTest {
    @Test fun readsMemFree() {
        val text = "MemTotal:        7363544 kB\nMemFree:         3547656 kB\nMemAvailable:    5335020 kB\n"
        assertEquals(3547656L, LaunchMemory.parseFreeKb(text))
    }
    @Test fun missingFieldIsNull() = assertNull(LaunchMemory.parseFreeKb("MemTotal: 1 kB\n"))
    @Test fun doesNotMistakeMemAvailable() = assertNull(LaunchMemory.parseFreeKb("MemAvailable:    5335020 kB\n"))
}
