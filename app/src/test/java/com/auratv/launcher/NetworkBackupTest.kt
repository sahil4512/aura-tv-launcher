package com.auratv.launcher

import com.auratv.launcher.backup.BackupNames
import com.auratv.launcher.backup.SmbClient
import com.auratv.launcher.backup.SmbDiscovery
import java.net.InetAddress
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkBackupTest {

    @Test fun backupNameCarriesTheDate() {
        val c = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 6, 21, 30) }
        assertEquals("20261106_aura-backup.json", BackupNames.forDate(c.timeInMillis))
    }

    @Test fun recognisesDatedAndOldBackups() {
        assertTrue(BackupNames.isBackup("20261126_aura-backup.json"))
        assertTrue(BackupNames.isBackup("aura-backup.json"))
        assertFalse(BackupNames.isBackup("2026112_aura-backup.json"))
        assertFalse(BackupNames.isBackup("today_aura-backup.json"))
        assertFalse(BackupNames.isBackup("20261126_aura-backup.json.bak"))
    }

    @Test fun newestFirstByDateInTheName() {
        val jan5 = Calendar.getInstance().apply { set(2026, Calendar.JANUARY, 5) }.timeInMillis
        val now = System.currentTimeMillis()
        // name, modification time (a copied file can be newer than its name says)
        val files = listOf("20260301_aura-backup.json" to now, "aura-backup.json" to jan5, "20261126_aura-backup.json" to now - 1000)
        assertEquals(listOf("20261126_aura-backup.json", "20260301_aura-backup.json", "aura-backup.json"),
            BackupNames.newestFirst(files, { it.first }, { it.second }).map { it.first })
    }

    @Test fun parsesWhatPeopleTypeAsAServer() {
        assertEquals("192.168.1.10" to null, SmbClient.parseAddress(" 192.168.1.10 "))
        assertEquals("NAS" to "smb://NAS/Backups/TV/", SmbClient.parseAddress("\\\\NAS\\Backups\\TV"))
        assertEquals("nas" to "smb://nas/share/", SmbClient.parseAddress("smb://nas/share/"))
        assertEquals("nas" to null, SmbClient.parseAddress("//nas"))
        assertNull(SmbClient.parseAddress("  \\\\ "))
        assertNull(SmbClient.parseAddress("my nas"))
    }

    @Test fun showsPathsTheWindowsWay() {
        assertEquals("\\\\nas\\Backups\\TV", SmbClient.displayPath("smb://nas/Backups/TV/"))
        assertEquals("nas", SmbClient.hostOf("smb://nas/Backups/TV/"))
    }

    @Test fun scansAtMostOneSlash24WithoutItself() {
        val ip = SmbDiscovery.toInt(InetAddress.getByName("192.168.1.20"))
        val t = SmbDiscovery.scanTargets(ip, 16)
        assertEquals(253, t.size)
        assertEquals("192.168.1.1", t.first())
        assertEquals("192.168.1.254", t.last())
        assertFalse("192.168.1.20" in t)
    }

    @Test fun readsTheNetbiosName() {
        val req = SmbDiscovery.nbstatRequest(0x1234)
        assertEquals(50, req.size)
        // Answer: header (12), compressed name pointer, type/class/ttl/rdlength, 2 names
        val r = ByteArray(12 + 2 + 10 + 1 + 18 * 2)
        r[12] = 0xc0.toByte(); r[13] = 0x0c
        r[24] = 2
        fun name(at: Int, n: String, suffix: Int, group: Boolean) {
            n.padEnd(15).forEachIndexed { i, ch -> r[at + i] = ch.code.toByte() }
            r[at + 15] = suffix.toByte()
            if (group) r[at + 16] = 0x80.toByte()
        }
        name(25, "WORKGROUP", 0, true)
        name(43, "MYNAS", 0, false)
        assertEquals("MYNAS", SmbDiscovery.parseNbstat(r, r.size))
    }
}
