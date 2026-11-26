package com.auratv.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.auratv.launcher.BuildConfig
import com.auratv.launcher.R
import com.auratv.launcher.apps.AppOrdering
import com.auratv.launcher.backup.BackupNames
import com.auratv.launcher.backup.LocalStorage
import com.auratv.launcher.backup.SmbClient
import com.auratv.launcher.backup.SmbDiscovery
import com.auratv.launcher.backup.SmbLogins
import com.auratv.launcher.redirect.DefaultHome
import com.auratv.launcher.redirect.HomeRedirect
import com.auratv.launcher.settings.AuraTheme
import com.auratv.launcher.settings.Prefs
import com.auratv.launcher.util.Bg
import com.auratv.launcher.util.Dp
import com.auratv.launcher.util.dp
import com.auratv.launcher.util.sizeDp
import java.io.File

/**
 * Full-screen two-pane settings from the Aura design: a sidebar of sections on the left (moving
 * focus there switches the page live) and the section's content on the right.
 *
 * Remote: ▲▼ move, ▶ enters the content, ◀ returns to the sidebar, ◀ ▶ change values,
 * Select activates, Back goes up one level and finally closes.
 */
class SettingsPanel(activity: MainActivity) : Overlay(activity) {

    // ---------------------------------------------------------------- item model

    sealed class Item
    class Section(val title: String) : Item()
    class Toggle(val title: String, val sub: String? = null, val get: () -> Boolean, val set: (Boolean) -> Unit) : Item()
    class Choice(val title: String, val options: List<String>, val get: () -> Int, val set: (Int) -> Unit, val sub: String? = null) : Item()
    class Nav(val title: String, val sub: () -> String? = { null }, val open: () -> Unit) : Item()
    class Action(val title: String, val sub: String? = null, val value: String? = null, val run: () -> Unit) : Item()
    class Info(val title: String, val value: String) : Item()
    class Pick(val title: String, val sub: String?, val selected: Boolean,
               val thumb: (((Bitmap?) -> Unit) -> Unit)?, val run: () -> Unit) : Item()
    object ThemeCards : Item()
    object Swatches : Item()
    class WallpaperGrid(val ids: List<Pair<String, String>>, val selected: String?, val onPick: (String) -> Unit) : Item()
    class Buttons(val buttons: List<Triple<Int, String, () -> Unit>>) : Item()

    class Page(val title: String, val build: () -> List<Item>) {
        /** Last focused row (index among the page's focusables) and scroll offset, restored on return. */
        var focusIndex = -1
        var scrollY = 0
    }

    private class SectionDef(val title: String, val icon: Int, val page: () -> Page)

    private val p: Prefs get() = activity.prefs
    private val sections = listOf(
        SectionDef("Appearance", R.drawable.ic_appearance) { appearancePage() },
        SectionDef("Apps", R.drawable.ic_apps) { appsPage() },
        SectionDef("Layout", R.drawable.ic_layout) { layoutPage() },
        SectionDef("System", R.drawable.ic_system) { systemPage() },
        SectionDef("About", R.drawable.ic_about) { aboutPage() },
    )
    private var section = 0
    private val stack = ArrayList<Page>()
    private var pageToken = 0
    /** True while the content list is rebuilt: sidebar focus changes must not switch sections then. */
    private var rendering = false

    // ---------------------------------------------------------------- views

    /** Sidebar: ▲▼ stop at the first/last section instead of escaping into the page content. */
    private val sidebar = object : LinearLayout(ctx) {
        override fun focusSearch(focused: View, direction: Int): View? {
            val next = super.focusSearch(focused, direction)
            if ((direction == View.FOCUS_UP || direction == View.FOCUS_DOWN) && (next == null || next.parent !== this)) return focused
            return next
        }
    }.apply { orientation = LinearLayout.VERTICAL }
    private val sideItems = ArrayList<SideItem>()
    private val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    // Clipped top and bottom (rows never draw over the heading), open at the sides (focus rings).
    private val scroll = EdgeSafeScrollView(ctx, focusMargin = 10.dp, sideOverflow = 16.dp).apply {
        setPadding(0, 6.dp, 0, 60.dp)
        addView(list)
    }
    private val crumb = TextView(ctx).apply { sizeDp(13f); typeface = Style.MEDIUM; letterSpacing = 0.1f }
    private val heading = TextView(ctx).apply { sizeDp(25f); typeface = Style.REGULAR }

    /** Content column: ◀ from its left edge goes back to the selected sidebar entry. */
    private val content = object : LinearLayout(ctx) {
        override fun focusSearch(focused: View, direction: Int): View? {
            val next = super.focusSearch(focused, direction)
            val inside = next != null && isInside(next)
            if (direction == View.FOCUS_LEFT && !inside) return sideItems[section]
            // ▶ at the end of a row and ▲▼ past the first/last row stay put (never jump to the sidebar).
            if (!inside) return focused
            return next
        }

        private fun isInside(v: View): Boolean {
            var cur: View? = v
            while (cur != null) { if (cur === this) return true; cur = cur.parent as? View }
            return false
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        addView(crumb)
        addView(heading, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 8.dp })
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
    }

    override val coversWallpaper = true

    private val backdrop = Backdrop(ctx)

    override val root: View = FrameLayout(ctx).apply {
        addView(backdrop, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val panes = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(activity.safeH - 10.dp, 110.dp, activity.safeH, 0)
            clipChildren = false
            clipToPadding = false // the last card's focus ring scales past the right safe margin
            addView(sidebar, LinearLayout.LayoutParams(206.dp, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(content, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { leftMargin = 34.dp })
        }
        addView(panes, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Remembers the focused row of the current page, so returning to the page restores it. */
    private val focusTracker = android.view.ViewTreeObserver.OnGlobalFocusChangeListener { _, now ->
        if (rendering || now == null || stack.isEmpty()) return@OnGlobalFocusChangeListener
        val idx = focusablesInList().indexOf(now)
        if (idx >= 0) stack.last().focusIndex = idx
    }

    init {
        sections.forEachIndexed { i, s ->
            val item = SideItem(ctx, s.icon, s.title)
            item.setOnFocusChangeListener { _, has -> if (has && i != section && !rendering) showSection(i) else item.refresh(i == section) }
            item.setOnClickListener { focusContent() }
            item.setOnKeyListener { _, code, e ->
                if (e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT) { focusContent(); true } else false
            }
            sideItems.add(item)
            sidebar.addView(item, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 44.dp).apply { bottomMargin = 4.dp })
        }
        restyle()
        showSection(0)
        root.viewTreeObserver.addOnGlobalFocusChangeListener(focusTracker)
    }

    override fun onClosed() {
        root.viewTreeObserver.removeOnGlobalFocusChangeListener(focusTracker)
        discovery?.stop()
    }

    private fun restyle() {
        crumb.setTextColor(Style.accent)
        heading.setTextColor(Style.theme.text)
        sideItems.forEachIndexed { i, it -> it.refresh(i == section) }
        backdrop.invalidate()
    }

    // ---------------------------------------------------------------- navigation

    private fun showSection(i: Int) {
        section = i
        sideItems.forEachIndexed { idx, it -> it.refresh(idx == section) }
        stack.clear()
        stack.add(sections[i].page())
        render(focusFirst = false)
    }

    /** Opens System → Home Button directly (from the first-start prompt on Fire TV). */
    fun openHomeButton() {
        val i = sections.indexOfFirst { it.title == "System" }
        if (i < 0) return
        showSection(i)
        push(homeButtonPage())
    }

    private fun push(page: Page) {
        stack.last().scrollY = scroll.scrollY
        stack.add(page)
        render(focusFirst = true)
    }

    /** Back from a sub-page: the parent page comes back with the row that opened it focused. */
    private fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.size - 1)
        render(focusFirst = false)
        restoreFocus(stack.last())
        return true
    }

    private fun restoreFocus(page: Page) {
        val rows = focusablesInList()
        val target = rows.getOrNull(page.focusIndex) ?: return focusFirstRow()
        scroll.scrollTo(0, page.scrollY)
        target.requestFocus()
    }

    private fun focusFirstRow() {
        scroll.scrollTo(0, 0)
        focusablesInList().firstOrNull()?.requestFocus()
    }

    /** Rebuild the current page in place (values changed), keeping focus at the same position. */
    fun reload() {
        val idx = focusedIndex()
        val y = scroll.scrollY
        render(focusFirst = false)
        scroll.scrollTo(0, y)
        if (idx >= 0) (focusablesInList().getOrNull(idx) ?: focusablesInList().lastOrNull())?.requestFocus()
    }

    private fun focusablesInList(): List<View> {
        val out = ArrayList<View>()
        list.addFocusables(out, View.FOCUS_DOWN)
        return out
    }

    private fun focusedIndex(): Int {
        val f = list.findFocus() ?: return -1
        return focusablesInList().indexOf(f)
    }

    /** ▶ from the sidebar: back to the row last used on this page, else the first row at the top. */
    private fun focusContent() {
        val page = stack.last()
        if (page.focusIndex >= 0) restoreFocus(page) else focusFirstRow()
    }

    private fun render(focusFirst: Boolean) {
        val page = stack.last()
        pageToken++
        crumb.text = if (stack.size > 1) stack.dropLast(1).joinToString("  ›  ") { it.title }.uppercase() else ""
        crumb.visibility = if (stack.size > 1) View.VISIBLE else View.GONE
        heading.text = page.title
        rendering = true
        // Removing the focused row would make Android hand focus to the first sidebar entry (and
        // switch sections); park it on the current section's entry while the list is rebuilt.
        if (list.findFocus() != null) sideItems[section].requestFocus()
        list.removeAllViews()
        val items = page.build()
        for (item in items) list.addView(makeView(item))
        // A page with nothing to select (e.g. "No hidden apps") still takes focus on its first
        // message, so focus never stays parked on the sidebar where ▲▼ would switch sections.
        if (items.all { it is Info || it is Section }) {
            val first = items.indexOfFirst { it is Info }
            if (first >= 0) list.getChildAt(first).isFocusable = true
        }
        scroll.scrollTo(0, 0)
        rendering = false
        if (focusFirst) focusContent()
    }

    override fun focusInitial() {
        sideItems[section].requestFocus()
    }

    override fun onBack(): Boolean {
        if (pop()) return true
        if (list.findFocus() != null) { sideItems[section].requestFocus(); return true }
        return false
    }

    /**
     * ◀ on the sidebar has nowhere to go: consume it. Everything else must reach Android's focus
     * search (Activity.onKeyDown runs before it), so cards, swatches and rows can be navigated.
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        SettingsKeys.consumeBeforeFocusSearch(keyCode, sidebar.hasFocus())

    override fun onThemeChanged() {
        restyle()
        reload()
    }

    // ---------------------------------------------------------------- item views

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = top; bottomMargin = 2.dp }

    private fun makeView(item: Item): View = when (item) {
        is Section -> TextView(ctx).apply {
            text = item.title
            sizeDp(14f); typeface = Style.MEDIUM
            setTextColor(Style.withAlpha(Style.theme.text, 170))
            setPadding(4.dp, 16.dp, 0, 8.dp)
            layoutParams = lp()
        }
        is Toggle -> MenuRow(ctx).apply {
            layoutParams = lp()
            title.text = item.title
            setSubtitle(item.sub)
            value.visibility = View.GONE
            val sw = ToggleView(ctx).apply { checked = item.get() }
            addView(sw, LinearLayout.LayoutParams(44.dp, 24.dp).apply { leftMargin = 14.dp })
            // Select toggles. ◀ ▶ only navigate, so moving around never flips a switch by accident.
            setOnClickListener { item.set(!item.get()); sw.checked = item.get() }
        }
        is Choice -> MenuRow(ctx).apply {
            layoutParams = lp()
            title.text = item.title
            setSubtitle(item.sub)
            fun show() { value.text = "‹  ${item.options[item.get().coerceIn(0, item.options.size - 1)]}  ›" }
            show()
            fun step(d: Int) { item.set((item.get() + d + item.options.size) % item.options.size); show() }
            setOnClickListener { step(1) }
            setOnKeyListener { _, code, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (code) {
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { step(1); true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { step(-1); true }
                    else -> false
                }
            }
        }
        is Nav -> MenuRow(ctx).apply {
            layoutParams = lp()
            title.text = item.title
            setSubtitle(item.sub())
            value.text = "›"; value.sizeDp(22f)
            setOnClickListener { item.open() }
            setOnKeyListener { _, code, e ->
                if (e.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_DPAD_RIGHT) { item.open(); true } else false
            }
        }
        is Action -> MenuRow(ctx).apply {
            layoutParams = lp()
            title.text = item.title
            setSubtitle(item.sub)
            value.text = item.value ?: ""
            setOnClickListener { item.run() }
        }
        is Info -> MenuRow(ctx).apply {
            layoutParams = lp()
            isFocusable = false
            title.text = item.title
            if (item.value.isEmpty()) { title.maxLines = 3; title.sizeDp(15f) } else useInfoLayout()
            value.text = item.value
            value.setTextColor(Style.theme.textDim)
        }
        is Pick -> MenuRow(ctx).apply {
            layoutParams = lp()
            title.text = item.title
            setSubtitle(item.sub)
            value.text = if (item.selected) "✓" else ""
            value.sizeDp(18f)
            if (item.thumb != null) {
                setThumb(null)
                val token = pageToken
                item.thumb.invoke { b -> if (token == pageToken && b != null) thumb.setImageBitmap(b) }
            }
            setOnClickListener { item.run() }
        }
        is ThemeCards -> cardRow(AuraTheme.ALL.map { t ->
            CardSpec(t.name, selected = p.themeId == t.id, render = { cb -> themePreview(t, cb) }) {
                p.themeId = t.id
                activity.applyAppearance()
                onThemeChanged()
            }
        }, 116, 70)
        is Swatches -> swatchRow()
        is WallpaperGrid -> wallpaperGrid(item)
        is Buttons -> LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(10.dp)
            item.buttons.forEachIndexed { i, (icon, label, run) ->
                addView(PillButton(ctx, icon, label).apply { setOnClickListener { run() } },
                    LinearLayout.LayoutParams(0, 46.dp, 1f).apply { if (i > 0) leftMargin = 12.dp })
            }
        }
    }

    // ---------------------------------------------------------------- cards (themes, wallpapers)

    private class CardSpec(val title: String, val selected: Boolean, val render: ((Bitmap?) -> Unit) -> Unit, val run: () -> Unit)

    private fun cardRow(specs: List<CardSpec>, wDp: Int, hDp: Int): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        clipToPadding = false // the focused card scales past the row's padding edge
        layoutParams = lp()
        setPadding(4.dp, 4.dp, 0, 10.dp)
        val token = pageToken
        specs.forEachIndexed { i, s ->
            val card = Card(ctx, wDp.dp, hDp.dp, s.title, s.selected)
            s.render { b -> if (token == pageToken && b != null) card.image.setImageBitmap(b) }
            card.setOnClickListener { s.run() }
            addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i > 0) leftMargin = 12.dp
            })
        }
    }

    private fun wallpaperGrid(item: WallpaperGrid): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false
        layoutParams = lp()
        val cols = 3
        val w = 168.dp
        val h = 94.dp
        var row: LinearLayout? = null
        val token = pageToken
        item.ids.forEachIndexed { i, (id, name) ->
            if (i % cols == 0) {
                row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false
                    setPadding(4.dp, 4.dp, 0, 6.dp)
                }
                addView(row)
            }
            val card = Card(ctx, w, h, name, id == item.selected)
            activity.loadThumb(id) { b -> if (token == pageToken && b != null) card.image.setImageBitmap(b) }
            card.setOnClickListener { item.onPick(id) }
            row!!.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                if (i % cols != 0) leftMargin = 14.dp
            })
        }
    }

    private fun swatchRow(): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = lp()
        setPadding(6.dp, 6.dp, 0, 10.dp)
        Style.ACCENTS.forEachIndexed { i, c ->
            val color = if (c == 0) Style.theme.accent else c
            val sw = Swatch(ctx, color, selected = p.accentColor == c, auto = c == 0)
            sw.setOnClickListener {
                p.accentColor = c
                activity.applyAppearance()
                onThemeChanged()
            }
            addView(sw, LinearLayout.LayoutParams(34.dp, 34.dp).apply { if (i > 0) leftMargin = 14.dp })
        }
    }

    /** Small preview of a theme: current wallpaper, the theme's shading, and a row of mini cards. */
    private fun themePreview(t: AuraTheme, cb: (Bitmap?) -> Unit) {
        activity.loadThumb(p.wallpaperId) { wall ->
            val w = 116.dp
            val h = 70.dp
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            if (wall != null) c.drawBitmap(wall, null, Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG))
            else c.drawColor(t.placeholder)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Style.withAlpha(t.shade, (255 * t.topShade * 0.6f).toInt()),
                Style.withAlpha(t.shade, (255 * t.bottomShade).toInt()), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
            paint.shader = null
            val cw = w * 0.17f
            val ch = cw * 0.75f
            val gap = w * 0.04f
            val y = h * 0.60f
            val r = t.cornerDp / 4f * Dp.density
            for (i in 0 until 4) {
                val x = w * 0.08f + i * (cw + gap)
                paint.style = Paint.Style.FILL
                paint.color = Style.withAlpha(t.placeholder, 235)
                c.drawRoundRect(RectF(x, y, x + cw, y + ch), r, r, paint)
                if (i == 0) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * Dp.density; paint.color = t.ring
                    c.drawRoundRect(RectF(x, y, x + cw, y + ch), r, r, paint)
                }
            }
            paint.style = Paint.Style.FILL
            paint.color = t.text
            c.drawRoundRect(RectF(w * 0.08f, h * 0.20f, w * 0.44f, h * 0.20f + 3.5f * Dp.density), 2f, 2f, paint)
            paint.color = Style.withAlpha(t.text, 140)
            c.drawRoundRect(RectF(w * 0.08f, h * 0.33f, w * 0.31f, h * 0.33f + 2.5f * Dp.density), 2f, 2f, paint)
            cb(b)
        }
    }

    // ---------------------------------------------------------------- pages

    private fun minutesLabel(m: Int) = when (m) { 0 -> "Never"; 60 -> "Every hour"; else -> "Every $m minutes" }

    private fun appearancePage() = Page("Appearance") {
        listOf(
            Section("Theme"),
            ThemeCards,
            Section("Accent Colour"),
            Swatches,
            Section("Display"),
            Toggle("Show Clock", get = { p.showClock }, set = { p.showClock = it; activity.applyAppearance() }),
            Toggle("Show Date", get = { p.showDate }, set = { p.showDate = it; activity.applyAppearance() }),
            Choice("Clock Format", listOf("System", "12-hour", "24-hour"), { p.clockFormat }, { p.clockFormat = it; activity.applyAppearance() }),
            Choice("Background", listOf("Solid", "Ambient glow"), { if (p.wallpaperType == Prefs.TYPE_GLOW) 1 else 0 }, {
                p.wallpaperType = if (it == 1) Prefs.TYPE_GLOW else Prefs.TYPE_SOLID
                activity.wallpaper.refresh(); activity.applyAppearance()
            }, "Solid is the lightest; Ambient glow takes the colour of the focused app"),
            Toggle("Greeting", "“Good Evening” above the clock", { p.showGreeting }, { p.showGreeting = it; activity.applyAppearance() }),
        )
    }

    private fun wallpapersPage() = Page("Wallpapers") {
        val cat = activity.wallpaper.catalog
        val all = cat.all(p.importedWallpapers).map { it.id to it.name }
        val items = ArrayList<Item>()
        val types = listOf(Prefs.TYPE_SOLID, Prefs.TYPE_GLOW, Prefs.TYPE_IMAGE)
        items += Choice("Background", listOf("Solid", "Ambient glow", "Wallpaper"),
            { types.indexOf(p.wallpaperType).let { if (it < 0) 2 else it } }, {
            p.wallpaperType = types[it]
            activity.wallpaper.refresh()
            activity.applyAppearance()
        }, "Solid is the lightest; Ambient glow takes the colour of the focused app")
        items += WallpaperGrid(all, if (p.wallpaperType == Prefs.TYPE_IMAGE) p.wallpaperId else null) { id ->
            activity.wallpaper.select(id)
            activity.applyAppearance()
            reload()
        }
        items += Buttons(listOf(
            Triple(R.drawable.ic_add_image, "Add Custom Wallpaper") { activity.withStoragePermission { push(importPage()) } },
        ))
        items += Choice("Change Wallpaper", Prefs.ROTATION_CHOICES.map { minutesLabel(it) },
            { Prefs.ROTATION_CHOICES.indexOf(p.rotationMinutes).coerceAtLeast(0) },
            { p.rotationMinutes = Prefs.ROTATION_CHOICES[it]; p.lastWallpaperChange = System.currentTimeMillis(); activity.wallpaper.refresh() })
        items += Section("Video")
        items += Nav("Video Wallpaper", { if (p.wallpaperType == Prefs.TYPE_VIDEO) "On · " + File(p.videoPath).name else "Use a local MP4 (muted)" }) {
            activity.withStoragePermission { push(videoPage()) }
        }
        if (p.importedWallpapers.isNotEmpty()) items += Nav("Remove Imported Wallpapers") { push(removeImportsPage()) }
        items
    }

    private fun importPage() = Page("Add Custom Wallpaper") {
        val files = activity.wallpaper.catalog.scanMedia(video = false)
        if (files.isEmpty()) listOf(
            Info("No images found. Copy JPG or PNG files to /sdcard/AuraTV (for example with Downloader or adb push), then come back here.", ""),
        ) else files.map { f ->
            Pick(f.name, f.parentFile?.name + " · " + (f.length() / 1024) + " KB", false,
                thumb = { cb -> activity.loadFileThumb(f, cb) }) {
                activity.importWallpaper(f) { pop() }
            }
        }
    }

    private fun removeImportsPage() = Page("Remove Imported") {
        p.importedWallpapers.mapIndexed { i, id ->
            Pick("Imported ${i + 1}", "Select to delete", false, thumb = { cb -> activity.loadThumb(id, cb) }) {
                activity.removeImported(id)
                if (p.importedWallpapers.isEmpty()) pop() else reload()
            }
        }
    }

    private fun videoPage() = Page("Video Wallpaper") {
        val files = activity.wallpaper.catalog.scanMedia(video = true)
        val items = ArrayList<Item>()
        items += Info("Plays muted (the audio track is never opened) and is hardware decoded. Cinematic images use less power.", "")
        if (p.wallpaperType == Prefs.TYPE_VIDEO) items += Action("Switch Back to Images") {
            p.wallpaperType = Prefs.TYPE_IMAGE; activity.wallpaper.refresh(); reload()
        }
        if (files.isEmpty()) items += Info("No videos found. Copy an MP4 (H.264, 1080p recommended) to /sdcard/AuraTV or Movies.", "")
        for (f in files) items += Pick(f.name, f.parentFile?.name + " · " + (f.length() / (1024 * 1024)) + " MB",
            p.wallpaperType == Prefs.TYPE_VIDEO && p.videoPath == f.path, null) {
            p.videoPath = f.path
            p.wallpaperType = Prefs.TYPE_VIDEO
            p.videoWarningShown = false
            activity.wallpaper.refresh()
            reload()
        }
        items
    }

    private fun appsPage() = Page("Apps") {
        listOf(
            Nav("Favourites", { "${p.favorites.size} pinned · choose which apps appear" }) { push(favoritesPage()) },
            Nav("Hidden Apps", { "${p.hidden.size} hidden" }) { push(hiddenPage()) },
            Toggle("Add New TV Apps to Favourites", get = { p.autoAddNew }, set = { p.autoAddNew = it }),
            Toggle("Show System Utilities", "Non-TV system apps such as file managers", { p.showSystemApps },
                { p.showSystemApps = it; activity.rebuildHome() }),
            Choice("All Apps Order", listOf("A–Z", "Recently installed"), { p.allAppsSort }, { p.allAppsSort = it; activity.rebuildHome() }),
            Action("Refresh App List", "Re-scan installed apps and redraw icons") {
                activity.tiles.clearAll(); activity.repo.refresh(); activity.toast("Refreshing apps…")
            },
            Action("Reset Favourites", "Back to the automatic selection") {
                activity.openOverlay(ConfirmOverlay(activity, "Reset favourites?",
                    "Your pinned apps and their order will be replaced with the default selection.",
                    listOf("Reset" to { activity.resetFavorites() }, "Cancel" to {})))
            },
        )
    }

    private fun favoritesPage() = Page("Favourites") {
        val favs = p.favorites.toHashSet()
        AppOrdering.sortForAllApps(AppOrdering.visible(activity.repo.apps, p.hiddenSet(), p.showSystemApps), 0).map { e ->
            Pick(e.label, if (e.key in favs) "On Home" else null, e.key in favs,
                thumb = { cb -> activity.tiles.get(e, Style.tileW(p.iconSize), Style.tileH(p.iconSize)) { cb(it) }?.let(cb) }) {
                activity.setFavorite(e, e.key !in p.favorites)
                reload()
            }
        }
    }

    private fun hiddenPage() = Page("Hidden Apps") {
        val hidden = p.hiddenSet()
        val apps = activity.repo.apps.filter { it.key in hidden }
        if (apps.isEmpty()) listOf(Info("No hidden apps. Hold Select on any app and choose Hide.", ""))
        else apps.map { e ->
            Pick(e.label, "Select to show again", false,
                thumb = { cb -> activity.tiles.get(e, Style.tileW(p.iconSize), Style.tileH(p.iconSize)) { cb(it) }?.let(cb) }) {
                activity.setHidden(e, false)
                reload()
            }
        }
    }

    private fun layoutPage() = Page("Layout") {
        val items = ArrayList<Item>()
        items += Toggle("All Apps Grid on Home", "Every app in 4 columns; off shows one favourites row", { p.homeGrid },
            { p.homeGrid = it; activity.applyAppearance(); reload() })
        if (!p.homeGrid) { // these only apply to the single-row layout
            items += Choice("Favourite Size", Style.SIZE_NAMES, { p.iconSize }, { p.iconSize = it; activity.applyAppearance() })
            items += Choice("Position", listOf("Centred", "Lower"), { p.rowPosition }, { p.rowPosition = it; activity.applyAppearance() })
            items += Toggle("Recently Used Row", get = { p.showRecentsRow }, set = { p.showRecentsRow = it; activity.refreshRecents(); activity.rebuildHome() })
            items += Toggle("All Apps Row", "A third row with every other app", { p.showAppsRow }, { p.showAppsRow = it; activity.rebuildHome() })
        }
        items += Choice("Interface Animation", listOf("Relaxed", "Normal", "Quick", "Off"), { p.uiAnimSpeed }, { p.uiAnimSpeed = it; Style.load(p) })
        items
    }

    private fun systemPage() = Page("System") {
        val items = ArrayList<Item>()
        val fire = HomeRedirect.isFireOs(ctx)
        items += Action(if (fire) "Fire TV Settings" else "TV Settings", "Network, picture, sound, apps…") { activity.openSystemSettings() }
        if (fire) items += Nav("Home Button", { homeButtonSummary() }) { push(homeButtonPage()) }
        else items += Action("Make GlowHome Your Home Screen",
            if (DefaultHome.isAuraHome(ctx)) "Done: the Home button opens GlowHome" else "So the Home button opens GlowHome") { activity.offerDefaultHome() }
        items += Toggle("Keep the TV Light", "When you open an app, close other apps running in the background",
            { p.keepTvLight }, { p.keepTvLight = it })
        items += Section("Backup")
        items += Nav("Back Up Settings", { "To this TV or a network share" }) { push(backupPage()) }
        items += Nav("Restore From Backup", { "From this TV or a network share" }) { push(restorePage()) }
        items += Action("Reset GlowHome TV", "Theme, wallpaper, favourites — everything") {
            activity.openOverlay(ConfirmOverlay(activity, "Reset GlowHome TV?",
                "All settings, favourites and hidden apps go back to defaults. Imported images are kept.",
                listOf("Reset everything" to { activity.resetAll() }, "Cancel" to {})))
        }
        items
    }

    // ---------------------------------------------------------------- backup / restore

    private val logins by lazy { SmbLogins(ctx) }
    private var discovery: SmbDiscovery? = null
    /** The network page whose server list discovery updates. */
    private var networkPage: Page? = null

    /** Redraw the current page, unless another overlay (a dialog) is on top; closing it redraws anyway. */
    private fun refreshIfShown() {
        if (activity.isTopOverlay(this)) reload()
    }

    /** Swap the current page for [page]: a fresh copy after signing in, or "Try Again". */
    private fun replaceTop(page: Page) {
        if (stack.size <= 1) return
        stack[stack.size - 1] = page
        render(focusFirst = true)
    }

    /**
     * A page whose rows come from the network: shows [waiting] until [load] (on a background
     * thread) has the rows, then redraws if the page is still the one on screen.
     */
    private fun asyncPage(title: String, waiting: String, load: (Page) -> List<Item>): Page {
        var rows: List<Item>? = null
        var started = false
        lateinit var page: Page
        page = Page(title) {
            rows ?: run {
                if (!started) {
                    started = true
                    Bg.net.execute {
                        val r = try { load(page) } catch (e: Exception) { listOf(Info(SmbClient.describe(e), "")) }
                        Bg.main.post {
                            rows = r
                            if (stack.lastOrNull() === page) refreshIfShown()
                        }
                    }
                }
                listOf(Info(waiting, ""))
            }
        }
        return page
    }

    private fun backupPage() = Page("Back Up Settings") {
        listOf(
            Nav("Save Locally", { "Internal storage or a USB drive / SD card" }) { push(localPage(backup = true)) },
            Nav("Save to Network Share", { "A shared folder on a computer or NAS" }) { push(networkPage(backup = true)) },
        )
    }

    private fun restorePage() = Page("Restore From Backup") {
        listOf(
            Nav("From Local Storage", { "Internal storage or a USB drive / SD card" }) { push(localPage(backup = false)) },
            Nav("From Network Share", { "A shared folder on a computer or NAS" }) { push(networkPage(backup = false)) },
        )
    }

    /** Internal storage and each USB drive / SD card that is plugged in. */
    private fun localPage(backup: Boolean) = Page("Local Storage") {
        LocalStorage.volumes(ctx).map { v ->
            Nav(v.label, { v.root.path }) { activity.withStoragePermission { push(localFolderPage(v.root, v, backup)) } }
        }
    }

    /** A local folder, laid out like a network folder: save here / the backups, then sub-folders. */
    private fun localFolderPage(dir: File, vol: LocalStorage.Volume, backup: Boolean): Page =
        Page(if (dir == vol.root) vol.label else dir.name) {
            val l = LocalStorage.list(dir)
            val items = ArrayList<Item>()
            val name = BackupNames.forDate()
            if (backup) {
                val exists = l?.backups?.any { it.name == name } == true
                items += Action("Save Backup Here", "As $name" + if (exists) ", replacing today's backup" else "") {
                    saveLocal(dir, vol, name, exists)
                }
            }
            if (l == null) {
                items += Info("This folder can't be opened.", "")
                return@Page items
            }
            if (backup) {
                if (l.backups.isNotEmpty()) {
                    items += Section("Backups Here")
                    for (f in l.backups) items += Info(f.name, backupDetail(f.lastModified(), f.length()))
                }
            } else {
                if (l.backups.isEmpty()) items += Info("No backups in this folder.", "")
                for (f in l.backups) items += Pick(f.name, backupDetail(f.lastModified(), f.length()), false, null) {
                    confirmRestore(f.name) { activity.restoreFile(f) }
                }
            }
            if (l.dirs.isNotEmpty()) {
                items += Section("Folders")
                for (d in l.dirs) items += Nav(d.name) { push(localFolderPage(d, vol, backup)) }
            }
            items
        }

    private fun saveLocal(dir: File, vol: LocalStorage.Volume, name: String, exists: Boolean) {
        if (exists) {
            activity.openOverlay(ConfirmOverlay(activity, "Replace today's backup?",
                "$name is already in this folder. It will be replaced with your current settings.",
                listOf("Replace" to { writeLocal(dir, vol, name) }, "Cancel" to {})))
        } else writeLocal(dir, vol, name)
    }

    /** Writes the backup; where Android doesn't let apps write, offers GlowHome's own folder on that storage. */
    private fun writeLocal(dir: File, vol: LocalStorage.Volume, name: String) {
        val json = activity.backupJson()
        Bg.net.execute {
            val f = try { LocalStorage.write(dir, name, json) } catch (e: Exception) { null }
            Bg.main.post {
                if (f != null) {
                    activity.toast("Backed up to ${f.path}")
                    refreshIfShown() // the folder page lists the new file
                    return@post
                }
                val app = vol.appDir
                if (app == null || app == dir) {
                    activity.toast("Couldn't save $name in ${dir.path}")
                    return@post
                }
                activity.openOverlay(ConfirmOverlay(activity, "Can't save here",
                    "Android doesn't let apps write to ${dir.path}. Save to GlowHome's own folder on ${vol.label} instead?\n\n${app.path}",
                    listOf("Save There" to { writeLocal(app, vol, name) }, "Choose Another Folder" to {})))
            }
        }
    }

    private fun backupDetail(time: Long, size: Long): String =
        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(time)) +
            " · " + maxOf(1L, (size + 1023) / 1024) + " KB"

    private fun confirmRestore(name: String, run: () -> Unit) {
        activity.openOverlay(ConfirmOverlay(activity, "Restore settings?",
            "Your current settings will be replaced with $name.", listOf("Restore" to run, "Cancel" to {})))
    }

    private fun loginLabel(l: SmbLogins.Login?) = if (l == null || l.guest) "Guest" else l.user

    /**
     * Like CoreELEC Installer's share browser: what the search is doing, "Enter Address", then
     * every server found on the network (A–Z; hosts with a saved login are listed too).
     */
    private fun networkPage(backup: Boolean): Page {
        val d = discovery ?: SmbDiscovery(ctx, Bg.main, object : SmbDiscovery.Listener {
            override fun onServer(s: SmbDiscovery.Server) { if (stack.lastOrNull() === networkPage) refreshIfShown() }
            override fun onFinished() { if (stack.lastOrNull() === networkPage) refreshIfShown() }
        }).also { discovery = it }
        if (!d.running) d.start()
        val page = Page("Network Shares") {
            val items = ArrayList<Item>()
            val found = d.servers()
            val saved = logins.saved()
            items += Info(when {
                d.running -> "Searching the network…"
                found.isEmpty() -> "No servers found. Use \u201cEnter Address\u201d."
                else -> "Search finished."
            }, "")
            items += Action("Enter Address", "For example 192.168.1.10 or \\\\NAS\\Backups") { askAddress(backup) }
            class Row(val host: String, val label: String, val sub: String?, val name: String?)
            val rows = ArrayList<Row>()
            val hosts = HashSet<String>()
            for (s in found) {
                hosts += s.ip
                rows += Row(s.ip, s.label, (if (s.name != null) s.ip else null).let { ip ->
                    if (logins.isSaved(s.ip)) listOfNotNull(ip, "saved login").joinToString(" · ") else ip
                }, s.name)
            }
            for (sv in saved) if (sv.host !in hosts) {
                rows += Row(sv.host, sv.name ?: sv.host, listOfNotNull(sv.host.takeIf { sv.name != null }, "saved login").joinToString(" · "), sv.name)
            }
            rows.sortWith(compareBy<Row, String>(String.CASE_INSENSITIVE_ORDER) { it.label }.thenBy { it.host })
            for (r in rows) items += Nav(r.label, { r.sub }) { push(sharesPage(r.host, r.name, backup)) }
            if (!d.running) items += Action("Search Again") { d.start(); reload() }
            items
        }
        networkPage = page
        return page
    }

    /** Rows for a failed connection: sign in (the dialog opens by itself) or try again. */
    private fun failureRows(page: Page, e: Exception, host: String, name: String?, retry: () -> Unit): List<Item> {
        val who = name ?: host
        if (SmbClient.isAuthError(e)) {
            Bg.main.post { if (stack.lastOrNull() === page && activity.isTopOverlay(this)) askLogin(host, name, retry) }
            return listOf(
                Info("$who asks for a user name and password.", ""),
                Action("Sign In") { askLogin(host, name, retry) },
            )
        }
        return listOf(
            Info("Couldn't connect to $who: ${SmbClient.describe(e)}", ""),
            Action("Try Again") { retry() },
        )
    }

    private fun sharesPage(host: String, name: String?, backup: Boolean): Page =
        asyncPage(name ?: host, "Connecting to ${name ?: host}…") { page ->
            val login = logins.get(host)
            try {
                val shares = SmbClient.shares(SmbClient.context(login), host)
                val items = ArrayList<Item>()
                if (shares.isEmpty()) items += Info("${name ?: host} shares no folders.", "")
                for (sh in shares) items += Nav(sh) { push(folderPage("smb://$host/$sh/", backup)) }
                items += Section("Login")
                items += Info("Signed in as", loginLabel(login))
                items += Action("Sign In as Another User") { askLogin(host, name) { replaceTop(sharesPage(host, name, backup)) } }
                if (logins.isSaved(host)) items += Action("Forget Saved Login", "The next visit asks again") {
                    logins.forget(host)
                    activity.toast("Login forgotten")
                    pop()
                }
                items
            } catch (e: Exception) {
                failureRows(page, e, host, name) { replaceTop(sharesPage(host, name, backup)) }
            }
        }

    /** A folder of a share: backup mode saves here, restore mode lists the backups; sub-folders below. */
    private fun folderPage(url: String, backup: Boolean): Page {
        val host = SmbClient.hostOf(url)
        val title = url.removePrefix("smb://").trimEnd('/').substringAfterLast('/')
        return asyncPage(title, "Opening ${SmbClient.displayPath(url)}…") { page ->
            try {
                val entries = SmbClient.list(SmbClient.context(logins.get(host)), url)
                val dirs = entries.filter { it.dir }
                val files = entries.filter { !it.dir }
                val items = ArrayList<Item>()
                if (backup) {
                    val name = BackupNames.forDate()
                    val exists = files.any { it.name == name }
                    items += Action("Save Backup Here", "As $name" + if (exists) ", replacing today's backup" else "") {
                        saveToShare(url, name, exists)
                    }
                    if (files.isNotEmpty()) {
                        items += Section("Backups Here")
                        for (f in files) items += Info(f.name, backupDetail(f.time, f.size))
                    }
                } else {
                    if (files.isEmpty()) items += Info("No backups in this folder.", "")
                    for (f in files) items += Pick(f.name, backupDetail(f.time, f.size), false, null) {
                        confirmRestore(f.name) { restoreFromShare(f.url) }
                    }
                }
                if (dirs.isNotEmpty()) {
                    items += Section("Folders")
                    for (dir in dirs) items += Nav(dir.name) { push(folderPage(dir.url, backup)) }
                }
                items
            } catch (e: Exception) {
                failureRows(page, e, host, null) { replaceTop(folderPage(url, backup)) }
            }
        }
    }

    private fun saveToShare(folder: String, name: String, exists: Boolean) {
        if (exists) {
            activity.openOverlay(ConfirmOverlay(activity, "Replace today's backup?",
                "$name is already in this folder. It will be replaced with your current settings.",
                listOf("Replace" to { writeToShare(folder, name) }, "Cancel" to {})))
        } else writeToShare(folder, name)
    }

    private fun writeToShare(folder: String, name: String) {
        val json = activity.backupJson()
        val host = SmbClient.hostOf(folder)
        val login = logins.get(host)
        activity.toast("Saving $name…")
        Bg.net.execute {
            val err = try { SmbClient.writeText(SmbClient.context(login), folder + name, json); null } catch (e: Exception) { e }
            Bg.main.post {
                if (err != null) {
                    activity.toast("Couldn't save the backup: ${SmbClient.describe(err)}")
                    return@post
                }
                activity.toast("Backed up to ${SmbClient.displayPath(folder + name)}")
                if (stack.size > 1 && activity.isTopOverlay(this)) replaceTop(folderPage(folder, backup = true))
            }
        }
    }

    private fun restoreFromShare(fileUrl: String) {
        val login = logins.get(SmbClient.hostOf(fileUrl))
        val name = fileUrl.substringAfterLast('/')
        activity.toast("Reading $name…")
        Bg.net.execute {
            val r = try { Result.success(SmbClient.readText(SmbClient.context(login), fileUrl)) } catch (e: Exception) { Result.failure(e) }
            Bg.main.post {
                r.onSuccess { text -> activity.restoreText(text, name) }.onFailure { activity.toast("Couldn't read $name: ${SmbClient.describe(it)}") }
            }
        }
    }

    private fun askAddress(backup: Boolean) {
        activity.openOverlay(FormOverlay(activity, "Enter server address",
            "The name or IP address of the computer or NAS, optionally with a shared folder.",
            listOf(FormOverlay.Field("Address", hint = "192.168.1.10 or \\\\NAS\\Backups")), null,
            listOf(
                "Connect" to { r: FormOverlay.Result ->
                    val a = SmbClient.parseAddress(r.values[0])
                    when {
                        a == null -> activity.toast("Enter a server name or IP address")
                        a.second != null -> push(folderPage(a.second!!, backup))
                        else -> push(sharesPage(a.first, null, backup))
                    }
                },
                "Cancel" to { _: FormOverlay.Result -> },
            )))
    }

    /** User name (optionally DOMAIN\user) and password for [host]; Guest when the share allows it. */
    private fun askLogin(host: String, name: String?, then: () -> Unit) {
        val cur = logins.get(host)?.takeIf { !it.guest }
        val user = cur?.let { if (it.domain.isNotEmpty()) it.domain + "\\" + it.user else it.user } ?: ""
        activity.openOverlay(FormOverlay(activity, "Sign in to ${name ?: host}",
            "The user name and password of the computer or NAS that shares the folder.",
            listOf(FormOverlay.Field("User name", user), FormOverlay.Field("Password", password = true)),
            FormOverlay.Toggle("Remember this login", true),
            listOf(
                "Sign In" to { r: FormOverlay.Result ->
                    val typed = r.values[0]
                    if (typed.isEmpty()) {
                        activity.toast("Enter a user name, or continue as guest")
                    } else {
                        val domain = typed.substringBefore('\\', "")
                        val u = typed.substringAfter('\\')
                        logins.set(host, SmbLogins.Login(u, r.values[1], domain), r.toggled, name)
                        then()
                    }
                },
                "Continue as Guest" to { r: FormOverlay.Result ->
                    logins.set(host, SmbLogins.Login.GUEST, r.toggled, name)
                    then()
                },
                "Cancel" to { _: FormOverlay.Result -> },
            )))
    }

    private fun homeButtonSummary(): String = when {
        !HomeRedirect.isFireOs(ctx) -> "Not needed on this device"
        !p.redirectEnabled -> "Off"
        !HomeRedirect.hasUsageAccess(ctx) -> "On · needs one-time access"
        else -> "On"
    }

    private fun applyRedirect() = HomeRedirect.apply(ctx, p)

    private fun homeButtonPage() = Page("Home Button") {
        val items = ArrayList<Item>()
        if (!HomeRedirect.isFireOs(ctx)) {
            items += Info("This device lets you choose GlowHome as the Home app directly.", "")
            items += Action("Make GlowHome Your Home Screen") { activity.offerDefaultHome() }
            return@Page items
        }
        val hijack = HomeRedirect.installedHijack(ctx)
        items += Toggle("Open GlowHome With the Home Button", "Built into GlowHome — no extra app needed", { p.redirectEnabled }) { on ->
            if (on && hijack != null) {
                activity.openOverlay(ConfirmOverlay(activity, "Launcher Hijack is installed",
                    "If it is still redirecting Home to another launcher, the two will compete. While testing GlowHome's redirect, " +
                        "stop Launcher Hijack (or point it at GlowHome). You can switch back at any time.",
                    listOf("Turn on GlowHome's redirect" to { p.redirectEnabled = true; applyRedirect(); reload() }, "Cancel" to { reload() })))
            } else {
                p.redirectEnabled = on
                applyRedirect()
            }
        }
        val access = HomeRedirect.hasUsageAccess(ctx)
        items += Info("Usage access", if (access) "Granted ✓" else "Not granted")
        if (!access) items += Info("Grant it once from a computer:\n${HomeRedirect.usageAccessCommand(ctx)}", "")
        items += Choice("Detection Speed", HomeRedirect.SPEED_NAMES, { p.redirectSpeed }, { p.redirectSpeed = it; applyRedirect() },
            "Fire OS holds app switches for about 5 s after Home; faster detection mainly helps after Back")
        items += Toggle("Double-Press Home for Amazon Home", "Press Home again within 3 s of GlowHome appearing",
            { p.redirectDoublePress }, { p.redirectDoublePress = it; applyRedirect() })
        items += Toggle("Keep Running in Background", "Recommended on 1.5 GB devices", { p.redirectResident },
            { p.redirectResident = it; applyRedirect() })
        items += Action("Open Amazon Home", "Once, without being redirected") {
            if (!HomeRedirect.openAmazonHome(ctx)) activity.toast("Amazon Home is not available")
        }
        items += Section("Experimental")
        val acc = HomeRedirect.accessibilityEnabled(ctx)
        items += Toggle("Instant Mode (Accessibility)",
            if (acc) "Accessibility service enabled · hold Home for Amazon Home" else "Needs the GlowHome accessibility service enabled (see README)",
            { p.redirectInstant }, { p.redirectInstant = it; applyRedirect() })
        items += Section("Recent Redirects")
        val log = p.redirectLog
        if (log.isEmpty()) items += Info("None yet", "")
        val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
        for (line in log.take(8)) {
            val parts = line.split('|')
            if (parts.size < 4) continue
            items += Info("${fmt.format(java.util.Date(parts[0].toLong()))} · ${parts[1].substringBefore(':')}",
                "${parts[2]} ms  (detected in ${parts[3]} ms)")
        }
        items
    }

    private fun aboutPage() = Page("About") {
        val rt = Runtime.getRuntime()
        listOf(
            Info("Version", BuildConfig.VERSION_NAME),
            Info("Device", "${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
            Info("Home ready in", activity.startupMs?.let { "$it ms" } ?: "—"),
            Info("Launcher memory (PSS)", "${android.os.Debug.getPss() / 1024} MB"),
            Info("App heap in use", "${(rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)} MB"),
            Info("Apps found", "${activity.repo.apps.size}"),
            Info("Tuned for this TV", p.sp.getString("profile_label", null) ?: "—"),
            Info("Privacy", "No accounts, no tracking"),
        )
    }

    // ---------------------------------------------------------------- small views

    /** Dark glass over the wallpaper, fading out at the top so Home's brand and clock stay visible. */
    private class Backdrop(c: Context) : View(c) {
        private val paint = Paint()

        override fun onDraw(cv: Canvas) {
            val t = Style.theme
            val top = 70f * Dp.density
            val solid = 106f * Dp.density
            paint.shader = LinearGradient(0f, top, 0f, solid, Style.withAlpha(t.panel, 0), Style.withAlpha(t.panel, 255), Shader.TileMode.CLAMP)
            cv.drawRect(0f, top, width.toFloat(), solid, paint)
            paint.shader = null
            paint.color = Style.withAlpha(t.panel, 255)
            cv.drawRect(0f, solid, width.toFloat(), height.toFloat(), paint)
            paint.color = Style.withAlpha(t.shade, 90)
            cv.drawRect(0f, 0f, width.toFloat(), top, paint)
        }
    }

    private class SideItem(c: Context, icon: Int, label: String) : LinearLayout(c) {
        private val img = ImageView(c).apply { setImageResource(icon) }
        private val text = TextView(c).apply { text = label; sizeDp(16f); typeface = Style.REGULAR }
        private var isSelectedSection = false

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            setPadding(14.dp, 0, 12.dp, 0)
            addView(img, LayoutParams(20.dp, 20.dp))
            addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = 14.dp })
        }

        fun refresh(selected: Boolean) {
            isSelectedSection = selected
            val t = Style.theme
            background = when {
                isFocused -> Style.focusHighlight()
                selected -> GradientDrawable().apply { setColor(Style.withAlpha(t.text, 22)); cornerRadius = 10f * Dp.density }
                else -> null
            }
            val c = if (isFocused || selected) t.text else Style.withAlpha(t.text, 175)
            text.setTextColor(c)
            img.setColorFilter(c)
        }

        override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            refresh(isSelectedSection)
        }
    }

    /** Focusable picture card with a caption (themes, wallpapers). */
    private class Card(c: Context, w: Int, h: Int, title: String, private val selected: Boolean) : LinearLayout(c) {
        val image = ImageView(c).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val frame = FrameLayout(c)
        private val ring = View(c)
        private val badge = CheckBadge(c)
        private val caption = TextView(c).apply {
            text = title; sizeDp(12f); gravity = Gravity.CENTER_HORIZONTAL; maxLines = 1
        }

        init {
            orientation = VERTICAL
            isFocusable = true
            isClickable = true
            clipChildren = false
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            image.setBackgroundColor(Style.theme.placeholder)
            frame.clipToOutline = true
            frame.outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, 8f * Dp.density)
                }
            }
            frame.addView(image, FrameLayout.LayoutParams(w, h))
            frame.addView(ring, FrameLayout.LayoutParams(w, h))
            badge.visibility = if (selected) View.VISIBLE else View.GONE
            frame.addView(badge, FrameLayout.LayoutParams(22.dp, 22.dp, Gravity.TOP or Gravity.END).apply {
                topMargin = 6.dp; rightMargin = 6.dp
            })
            addView(frame, LayoutParams(w, h))
            addView(caption, LayoutParams(w, LayoutParams.WRAP_CONTENT).apply { topMargin = 6.dp })
            frame.pivotX = w / 2f; frame.pivotY = h / 2f
            refresh()
        }

        private fun refresh() {
            val t = Style.theme
            ring.background = GradientDrawable().apply {
                cornerRadius = 8f * Dp.density
                setColor(Color.TRANSPARENT)
                // Focus is the ring; "selected" is the ✓ badge, so focused + selected shows both.
                if (isFocused) setStroke((FocusSpec.ringDp(t.ringWidthDp) * Dp.density).toInt(), t.ring)
                else setStroke(1.dp, Style.withAlpha(t.text, 30))
            }
            caption.setTextColor(if (isFocused || selected) t.text else Style.withAlpha(t.text, 170))
        }

        override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            refresh()
            val s = if (gainFocus) FocusSpec.SMALL_SCALE else 1f
            frame.animate().scaleX(s).scaleY(s).setDuration(Style.dur(if (gainFocus) FocusSpec.IN_MS else FocusSpec.OUT_MS))
                .setInterpolator(Style.easeOut).start()
        }
    }

    private class Swatch(c: Context, private val color: Int, private val selected: Boolean, private val auto: Boolean) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            isFocusable = true
            isClickable = true
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            contentDescription = if (auto) "Theme colour" else "Accent colour"
        }

        override fun onDraw(cv: Canvas) {
            val r = width / 2f
            if (isFocused) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = FocusSpec.ringDp(Style.theme.ringWidthDp) * Dp.density
                paint.color = Style.theme.ring
                cv.drawCircle(r, r, r - paint.strokeWidth, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = color
            cv.drawCircle(r, r, r * 0.62f, paint)
            if (auto && !selected) { // inner ring marks "theme default"
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * Dp.density
                paint.color = Style.withAlpha(Color.BLACK, 120)
                cv.drawCircle(r, r, r * 0.36f, paint)
            }
            if (selected) CheckBadge.drawCheck(cv, r, r, r * 0.30f, Style.contrastOn(color), paint)
        }

        override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            invalidate()
            val s = if (gainFocus) FocusSpec.SMALL_SCALE else 1f
            animate().scaleX(s).scaleY(s).setDuration(Style.dur(if (gainFocus) FocusSpec.IN_MS else FocusSpec.OUT_MS))
                .setInterpolator(Style.easeOut).start()
        }
    }

    private class PillButton(c: Context, icon: Int, label: String) : LinearLayout(c) {
        private val img = ImageView(c).apply { setImageResource(icon) }
        private val text = TextView(c).apply { text = label; sizeDp(15f); typeface = Style.REGULAR }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            if (Build.VERSION.SDK_INT >= 26) defaultFocusHighlightEnabled = false
            addView(img, LayoutParams(20.dp, 20.dp))
            addView(text, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { leftMargin = 10.dp })
            refresh()
        }

        private fun refresh() {
            val t = Style.theme
            background = GradientDrawable().apply {
                cornerRadius = 10f * Dp.density
                setColor(if (isFocused) Style.withAlpha(Style.accent, FocusSpec.FILL_ALPHA) else Style.withAlpha(t.text, 18))
                setStroke(1.dp, if (isFocused) Style.withAlpha(Style.accent, FocusSpec.STROKE_ALPHA) else Style.withAlpha(t.text, 40))
            }
            text.setTextColor(t.text)
            img.setColorFilter(t.text)
        }

        override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
            super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
            refresh()
        }
    }

    /** "Selected" marker: accent disc with a white ✓, drawn in one pass. */
    private class CheckBadge(c: Context) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(cv: Canvas) {
            val r = width / 2f
            paint.style = Paint.Style.FILL
            paint.color = Style.accent
            cv.drawCircle(r, r, r, paint)
            drawCheck(cv, r, r, r * 0.55f, Style.contrastOn(Style.accent), paint)
        }

        companion object {
            fun drawCheck(cv: Canvas, cx: Float, cy: Float, s: Float, color: Int, paint: Paint) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = maxOf(1.6f * Dp.density, s * 0.28f)
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.color = color
                val path = android.graphics.Path()
                path.moveTo(cx - s * 0.75f, cy + s * 0.02f)
                path.lineTo(cx - s * 0.2f, cy + s * 0.55f)
                path.lineTo(cx + s * 0.8f, cy - s * 0.5f)
                cv.drawPath(path, paint)
                paint.strokeCap = Paint.Cap.BUTT
                paint.style = Paint.Style.FILL
            }
        }
    }

    /** iOS-style switch drawn in one pass. */
    /** The on/off switch of [Toggle] rows (also used by [FormOverlay]). */
    class ToggleView(c: Context) : View(c) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        var checked = false
            set(v) { field = v; invalidate() }

        override fun onDraw(cv: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            paint.color = if (checked) Style.accent else Style.withAlpha(Style.theme.text, 60)
            cv.drawRoundRect(RectF(0f, 0f, w, h), h / 2, h / 2, paint)
            // Knob contrasts with the track (a white accent, e.g. Minimal Dark, hid the "on" state).
            paint.color = if (checked) Style.contrastOn(Style.accent) else Color.WHITE
            val r = h / 2 - 3f * Dp.density
            cv.drawCircle(if (checked) w - h / 2 else h / 2, h / 2, r, paint)
        }
    }
}
