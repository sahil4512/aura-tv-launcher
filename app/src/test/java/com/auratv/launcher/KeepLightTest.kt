package com.auratv.launcher

import com.auratv.launcher.apps.AppEntry
import com.auratv.launcher.apps.KeepLight
import org.junit.Assert.assertEquals
import org.junit.Test

class KeepLightTest {
    private fun app(pkg: String, cls: String = ".Main") = AppEntry(pkg, pkg + cls, pkg, true, false, 0L, 0L)

    @Test fun closesStreamingAppsButNeverSystemPartsOrAura() {
        val apps = listOf(
            app("com.netflix.ninja"), app("in.startv.hotstar"), app("com.google.android.youtube.tv"),
            app("com.auratv.launcher"), app("com.tcl.tv"), app("com.android.vending"),
            app("com.google.android.gms"), app("com.netflix.ninja", ".Other"),
        )
        assertEquals(listOf("com.netflix.ninja", "in.startv.hotstar", "com.google.android.youtube.tv"),
            KeepLight.targets(apps, "com.auratv.launcher"))
    }
}
