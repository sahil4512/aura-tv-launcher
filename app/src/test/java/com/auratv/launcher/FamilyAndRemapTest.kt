package com.auratv.launcher

import com.auratv.launcher.redirect.AppRemaps
import com.auratv.launcher.settings.PinVerifier
import org.junit.Assert.*
import org.junit.Test

class FamilyAndRemapTest {
    @Test fun pinPreservesLeadingZerosAndUsesFreshSalt() {
        val verifier = PinVerifier.encode("0123")
        assertTrue(PinVerifier.matches("0123", verifier))
        assertFalse(PinVerifier.matches("1230", verifier))
        assertFalse(PinVerifier.matches("0123", "broken"))
        assertNotEquals(verifier, PinVerifier.encode("0123"))
    }

    @Test fun cyclicShortcutsFireOnlyForIndependentLaunches() {
        val watcher = AppRemaps()
        val mappings = mapOf("a" to "b", "b" to "a")
        watcher.foreground("a")
        assertEquals("b", watcher.target(mappings))
        watcher.foreground("b")
        assertNull(watcher.target(mappings))
        watcher.foreground("b")
        assertNull(watcher.target(mappings))
        watcher.foreground("home")
        watcher.foreground("b")
        assertEquals("a", watcher.target(mappings))
    }

    @Test fun ignoreObsoleteSourcesAndUnsafeBackupEntries() {
        val mappings = AppRemaps.decode(listOf("x=x", "a=b", "invalid", "a=com.amazon.tv.launcher"))
        assertEquals(mapOf("a" to "b"), mappings)
        val watcher = AppRemaps()
        watcher.foreground("a")
        watcher.foreground("other")
        assertNull(watcher.target(mappings))
    }
}
