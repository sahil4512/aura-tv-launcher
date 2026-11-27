package com.auratv.launcher

import android.view.KeyEvent
import com.auratv.launcher.settings.BackupFiles
import com.auratv.launcher.ui.RemoteKeys
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

class RemoteAndBackupTest {
    @Test fun setupRecognizesAppShortcutsAndPreservesNavigationAndSystemKeys() {
        assertEquals(RemoteKeys.APPS, RemoteKeys.id(KeyEvent.KEYCODE_ALL_APPS))
        assertEquals("183", RemoteKeys.id(KeyEvent.KEYCODE_PROG_RED))
        for (key in listOf(KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_POWER))
            assertNull(RemoteKeys.id(key))
    }

    @Test fun portableBackupReadsTheExistingUnicodeFormatAndRejectsOversizedInput() {
        val json = """{"format":"aura-tv-backup","version":1,"values":{"theme":{"t":"s","v":"étoile"}}}"""
        assertEquals("étoile", BackupFiles.read(json.byteInputStream()).getJSONObject("values").getJSONObject("theme").getString("v"))
        assertThrows(IllegalArgumentException::class.java) { BackupFiles.read(ByteArrayInputStream(ByteArray(4 * 1024 * 1024 + 1))) }
    }

    @Test fun firmwareCanSetKeepLightDefaultAndLauncherNeedsNoProcessKillingPermission() {
        assertTrue(File("src/main/res/values/config.xml").readText().contains("""<bool name="def_keep_tv_light">true</bool>"""))
        assertTrue(File("src/main/res/values/overlayable.xml").readText().contains("""type="bool" name="def_keep_tv_light""""))
        assertFalse(File("src/main/AndroidManifest.xml").readText().contains("KILL_BACKGROUND_PROCESSES"))
    }
}
