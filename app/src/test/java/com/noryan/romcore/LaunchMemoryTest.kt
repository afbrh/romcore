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
    @Test fun readsStall() = assertEquals(0.32, LaunchMemory.parseStall("some avg10=0.32 avg60=1.21 avg300=1.19 total=1\nfull avg10=0.22 avg60=1 avg300=1 total=2\n")!!, 0.001)
    @Test fun noStallLineIsNull() = assertNull(LaunchMemory.parseStall(""))
}
