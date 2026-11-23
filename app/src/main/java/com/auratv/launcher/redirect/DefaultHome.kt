package com.auratv.launcher.redirect

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings

/**
 * "Make GlowHome your home screen". Android never lets an app make itself the default Home app, so Aura
 * asks the viewer, using the most each TV allows. [DefaultHomePolicy] picks the way; this object
 * reads the device and starts it.
 */
object DefaultHome {
    const val ROLE_HOME = "android.app.role.HOME"

    private fun homeIntent() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    /** The package Android would open for Home right now ("android" means its chooser would show). */
    fun resolvedHomePackage(ctx: Context): String? = try {
        ctx.packageManager.resolveActivity(homeIntent(), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    } catch (_: Exception) { null }

    /** Android 10+ answers through the Home role (immune to package visibility); older ones by resolving Home. */
    fun isAuraHome(ctx: Context): Boolean {
        if (roleAvailable(ctx)) try {
            return roleManager(ctx)!!.isRoleHeld(ROLE_HOME)
        } catch (_: Exception) {}
        return resolvedHomePackage(ctx) == ctx.packageName
    }

    private fun roleManager(ctx: Context): android.app.role.RoleManager? =
        if (Build.VERSION.SDK_INT >= 29) ctx.getSystemService(android.app.role.RoleManager::class.java) else null

    private fun roleAvailable(ctx: Context): Boolean = Build.VERSION.SDK_INT >= 29 && try {
        roleManager(ctx)?.isRoleAvailable(ROLE_HOME) == true
    } catch (_: Exception) { false }

    private fun resolves(ctx: Context, i: Intent) = try {
        ctx.packageManager.resolveActivity(i, 0) != null
    } catch (_: Exception) { false }

    fun route(ctx: Context): DefaultHomePolicy.Route {
        val resolved = resolvedHomePackage(ctx)
        return DefaultHomePolicy.route(
            auraIsHome = isAuraHome(ctx),
            fireOs = HomeRedirect.isFireOs(ctx),
            roleAvailable = roleAvailable(ctx),
            homeSettingsAvailable = resolves(ctx, Intent(Settings.ACTION_HOME_SETTINGS)),
            chooserWouldShow = DefaultHomePolicy.isChooser(resolved),
        )
    }

    /**
     * Starts the system step for [route]. Returns false when nothing could be started (the caller
     * then explains the computer setup). ROLE results arrive in onActivityResult with [requestCode].
     */
    fun start(activity: Activity, route: DefaultHomePolicy.Route, requestCode: Int): Boolean = try {
        when (route) {
            DefaultHomePolicy.Route.ROLE -> {
                val i = if (Build.VERSION.SDK_INT >= 29) roleManager(activity)?.createRequestRoleIntent(ROLE_HOME) else null
                if (i != null) { activity.startActivityForResult(i, requestCode); true } else false
            }
            DefaultHomePolicy.Route.HOME_SETTINGS -> { activity.startActivity(Intent(Settings.ACTION_HOME_SETTINGS)); true }
            DefaultHomePolicy.Route.CHOOSER -> { activity.startActivity(homeIntent()); true }
            else -> false
        }
    } catch (_: Exception) { false }
}

/** Pure decisions for the first-start Home prompt (unit-tested). */
object DefaultHomePolicy {
    enum class Route {
        /** Aura already opens on Home: nothing to ask. */
        ALREADY_HOME,
        /** Android 10+: one system yes/no dialog (RoleManager ROLE_HOME). */
        ROLE,
        /** The system's "Home app" settings page, where Aura can be picked. */
        HOME_SETTINGS,
        /** No default yet: pressing Home shows Android's "which app?" chooser. */
        CHOOSER,
        /** Fire OS: no default Home is possible; Aura's own Home button redirect instead. */
        FIRE_TV,
        /**
         * Nothing on the TV itself works, typically because Google's stock TV launcher declares a
         * priority-2 Home filter that always wins. Only adb from a computer can change it.
         */
        COMPUTER,
    }

    /** Android's chooser (ResolverActivity) lives in the "android" package. */
    fun isChooser(resolvedPackage: String?) = resolvedPackage == "android"

    fun route(auraIsHome: Boolean, fireOs: Boolean, roleAvailable: Boolean,
              homeSettingsAvailable: Boolean, chooserWouldShow: Boolean): Route = when {
        auraIsHome -> Route.ALREADY_HOME
        fireOs -> Route.FIRE_TV
        roleAvailable -> Route.ROLE
        homeSettingsAvailable -> Route.HOME_SETTINGS
        chooserWouldShow -> Route.CHOOSER
        else -> Route.COMPUTER
    }

    /** The first-start prompt shows until answered once, and never when Aura is already Home. */
    fun shouldPromptOnStart(answered: Boolean, route: Route) = !answered && route != Route.ALREADY_HOME

    const val COMPUTER_STEPS = "From a computer on the same Wi-Fi, run:\n" +
        "tools/aura-setup <tv-ip>:5555 --set-home\n" +
        "or: tools/tv-cleanup <tv-ip>:5555 --apply --replace-home\n" +
        "(github.com/sahil4512/glowhome-tv-launcher)"

    class Prompt(val heading: String, val message: String, val goLabel: String?)

    fun prompt(route: Route): Prompt = when (route) {
        Route.ROLE -> Prompt("Make GlowHome your home screen",
            "GlowHome will open whenever you press Home on the remote. On the next screen, select GlowHome TV, " +
                "then Set as default. You can switch back at any time in the TV's settings.", "Make GlowHome my home screen")
        Route.HOME_SETTINGS -> Prompt("Make GlowHome your home screen",
            "GlowHome will open whenever you press Home on the remote. On the next screen, choose GlowHome TV. " +
                "You can switch back there at any time.", "Choose GlowHome TV")
        Route.CHOOSER -> Prompt("Make GlowHome your home screen",
            "GlowHome will open whenever you press Home on the remote. On the next screen, choose GlowHome TV, then Always.",
            "Choose GlowHome TV")
        Route.FIRE_TV -> Prompt("Open GlowHome with the Home button",
            "Fire TV always keeps Amazon's home screen and doesn't let apps replace it. GlowHome can still come up " +
                "when you press Home: turn on Settings → System → Home Button in GlowHome. It needs one step from a computer:\n" +
                "tools/aura-setup <tv-ip>:5555 --grant-usage", "Open Home Button settings")
        Route.COMPUTER -> Prompt("Make GlowHome your home screen",
            "This TV keeps its own home screen and doesn't let apps change that from the TV itself.\n$COMPUTER_STEPS",
            null)
        Route.ALREADY_HOME -> Prompt("GlowHome is your home screen", "GlowHome already opens when you press Home.", null)
    }
}
