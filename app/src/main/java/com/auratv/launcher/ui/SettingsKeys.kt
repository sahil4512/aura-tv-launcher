package com.auratv.launcher.ui

import android.view.KeyEvent

/**
 * Which remote keys Settings swallows before Android's focus search runs (pure, unit-tested).
 *
 * Activity.onKeyDown sees a key *before* directional focus navigation, so anything consumed here
 * can never move focus. Only ◀ on the sidebar is consumed (there is nothing to its left). Every
 * other ◀ ▶ must reach focus search: theme cards, swatches, wallpaper columns and the way back
 * from the content to the sidebar all depend on it.
 */
object SettingsKeys {
    fun consumeBeforeFocusSearch(keyCode: Int, sidebarFocused: Boolean): Boolean =
        keyCode == KeyEvent.KEYCODE_DPAD_LEFT && sidebarFocused
}
