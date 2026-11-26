# Changelog

## Unreleased: back up to a network share

- **Back Up Settings and Restore From Backup** now ask where: *This TV* or *Network Share*.
- **Network shares (SMB 2/3)**: GlowHome finds computers and NAS on your network (mDNS, WS-Discovery, a scan of your /24, NetBIOS names), or you type the address (`192.168.1.10`, `\\NAS\Backups`). Then pick a shared folder and *Save Backup Here*, or pick a backup to restore. The last folder used is offered first next time.
- **Logins**: guest access is tried first; when a share asks, sign in (`DOMAIN\user` works) and optionally *Remember this login*. Saved logins are kept apart from the settings, so backups never contain them, and are encrypted with an Android Keystore key on Android 6+. *Forget Saved Login* on the server's page.
- **Backup files are named `YYYYMMDD_aura-backup.json`**, so backups of different days sit side by side. Restore lists them newest first, and still offers `aura-backup.json` from earlier versions.
- New dependency: `jcifs-ng` 2.1.10 (with Bouncy Castle and slf4j-api). GlowHome gives jcifs-ng its own small crypto provider (MD4, AES-CMAC) so R8 can drop the rest of Bouncy Castle's JCA classes. New permission: `CHANGE_WIFI_MULTICAST_STATE`.
- **Tests:** `NetworkBackupTest` (file names and order, typed addresses, subnet scan, NetBIOS names).

## 0.7.0 — Aura TV is now GlowHome TV (2026-11-24)

versionCode 19.

- **New name: GlowHome TV.** Another Android TV launcher is called "Aura", so the app shows a new name everywhere you see it: the app list, the TV banner, Settings, the prompts, the screensaver and the README.
- **Nothing else changes for you.** The package stays `com.auratv.launcher`, so 0.6.x updates in place and keeps your settings, favourites and backups. The download name `AuraTV.apk` and Downloader code 1212393 still work, and so do the backup file `/sdcard/AuraTV/aura-backup.json`, the `tools/aura-setup` and `tools/tv-cleanup` scripts, and log tags.
- The TV banner is redrawn with the new name (`tools/AuraArt.java`, `NAME`).
- After the release: the GitHub repo is now `sahil4512/glowhome-tv-launcher` (old links redirect), the download is also published as `GlowHomeTV.apk` / `GlowHomeTV-x.y.z.apk` (`AuraTV.apk` keeps working), `tools/aura-setup` accepts both names in `dist/`, and the promo video and GIF show the new name. The new Downloader code is **8015828**; the old code 1212393 still works.

## 0.6.7 — Aura asks to become your home screen (2026-11-23)

versionCode 18.

- **First start: "Make Aura your home screen".** Android never lets an app make itself the Home app, so Aura asks once and does the most each TV allows:
  - **Android 10+:** Android's own dialog (`RoleManager`, `ROLE_HOME`): select Aura TV, then Set as default.
  - **Older Android TV:** the TV's Home app settings (`ACTION_HOME_SETTINGS`), or Android's "which app?" chooser.
  - **TVs where nothing on the TV works** (Google's stock TV launcher has a priority-2 Home filter that always wins): Aura says so plainly and shows the computer command, `tools/aura-setup <tv-ip>:5555 --set-home` or `tools/tv-cleanup … --replace-home`.
  - **Fire TV:** no Home app can be chosen. Aura explains this and opens its own Settings → System → Home Button.
  - Shown only when Aura isn't already Home. *Not now* (or Back) is remembered. If an Android dialog is in front (such as the Wi-Fi permission), the prompt waits for it.
- **Aura checks the result itself** when the viewer comes back, and says "Aura is now your home screen". It reads the Home role on Android 10+ and declares a HOME `<queries>` entry, so Android 11+ package visibility can't hide the other launcher.
- **Settings → System → Make Aura Your Home Screen** offers it again at any time (Android TV; Fire TV keeps Home Button).
- **Wi-Fi name permission explained first.** Before Android's "location" dialog, Aura says it needs the permission only to show the Wi-Fi network's name, never uses the location, and that Skip / Don't allow is fine (the chip then says "Wi-Fi").
- **`tools/aura-setup`:** "Make Aura the Home app?" now defaults to yes (press Enter). Without a terminal it still changes nothing.
- **Tests:** `DefaultHomePolicyTest` (route choice, show-once rule, prompt texts); unit tests 75/75. Emulators (`aura_androidtv` API 31, `aura_api25` API 25): the prompt shows on first start, waits behind the Wi-Fi dialog, remembers *Not now*, stays away when Aura is Home, and is offered again from Settings → System. On API 31, Android's "Set Aura TV as your default home app?" dialog made Aura hold the Home role, and the Home key opened Aura. On API 25, Aura opened the Home app settings. Home and Settings UI checks: 54/54 on both. Not yet tried on a real TV.

## 0.6.6 — simpler settings, TV settings one press away, tuned per TV (2026-11-08)

versionCode 17.

- **The Settings chip on Home opens the TV's own settings** (network, picture, sound, apps). Aura's options: hold Select on the chip, or press ≡ (menu) on Home. A hint line says so when the chip is focused.
- **Aura's settings slimmed down:**
  - the Wallpapers section is gone (picture grid, import, video wallpaper, change timer, dim);
  - the screensaver section is gone;
  - row-only layout options show only when the grid is off;
  - Home Button shows only on Fire OS.
  - Background (Solid / Ambient glow) moved into Appearance.
- **Screensaver photos** are downloaded only if Aura is actually the TV's chosen screensaver (no background network use otherwise).
- **Tuned per TV:** on first start, `DeviceProfile` reads low-RAM mode, total memory and CPU speed. Slow or small TVs get the solid background, Quick animations and Keep the TV Light. About shows "Tuned for this TV". `tools/install-androidtv.sh` also precompiles.
- **Tests:** unit tests 68/68; UI checks on the API 25 emulator 70/72 in the full run, plus a Settings re-run at 40/40 after a script fix. The 2 full-run failures were the script's extra Back, not Aura. Updated checks: S30, S35/S36 (new sidebar), S43 (Settings chip opens the TV's own settings). Installed on the TCL TV while YouTube kept playing; precompile still to do when the TV is idle.

## 0.6.5 — Keep the TV Light (2026-11-08)

versionCode 16.

- **Keep the TV Light** (Settings → System, on by default): 4 s after Home is left for an app, Aura ends the background processes of every other launchable app. This includes service-kept ones like Netflix's `NetflixService` (`stopIfKilled=true`, so it stays closed). The app on screen is never touched.
- **Left alone:** TCL, Android, Amazon, Realtek and MediaTek system parts, Google Play Services, keyboards and Aura itself. Coming back to Home within those 4 s cancels it.
- **Why:** on the TCL TV, Netflix's idle service held 141 MB and about 11 % of a 900 MHz core while YouTube dropped frames.
- **Uses `KILL_BACKGROUND_PROCESSES`** (an ordinary permission). Android 14+ restricts it to an app's own processes, so the setting has no effect there.
- **Verified:** on the API 25 emulator, the background app was closed and the app being opened kept running. Unit test `KeepLightTest`; unit tests 68/68; Settings and Home UI checks 56/56. Not yet verified on the TV (it was unreachable).

## 0.6.4 — Wi-Fi chip shows the network name on Android 9+ (2026-11-08)

versionCode 15.

- On Android 8.1 and later (e.g. the TCL TV, Android 9), the Wi-Fi chip said just "Wi-Fi": Android shows the network's name only to apps with location permission.
- Aura now declares `ACCESS_COARSE_LOCATION`, used only to read the network name; it never reads or requests a location. It asks once with Android's prompt; declining keeps "Wi-Fi".
- Falls back to `NetworkInfo.extraInfo`, which still carries the name on some TVs.
- `android.hardware.location` is marked not required.
- Not yet installed on a TV at release time.

## 0.6.3 — tuned on the TCL TV (2026-11-08)

versionCode 14. Measured on the real TCL TV; full table in [docs/perf-tcl-tv.md](docs/perf-tcl-tv.md).

- **Start-up log covers the first frame:** checkpoints now run through the first layout and draw, so slow-TV profiling sees everything up to the frame on screen.
- **New `tools/install-androidtv.sh`:** installs the latest `dist/` APK (refusing while something is playing), then precompiles all of Aura's code with `cmd package compile -m speed`. Android resets that on every update.
- **Results on the TCL TV (0.5.1 → 0.6.3):**
  - Aura's own cold launch: 587–802 → 271–286 ms;
  - warm Home: 350 → 179 ms;
  - idle CPU: 26 % → 0 %;
  - memory: 38.2 → 26.5 MB;
  - GPU textures: 8.9 → 1.8 MB;
  - janky navigation frames: 9.6 → 1.8 %.

## 0.6.2 — solid background, no motion (2026-11-08)

versionCode 13. Tuned after measuring 0.5.1 on the TCL Android TV (Realtek RTD284x, 4 × 900 MHz, GLES 2.0, low-RAM mode, 720p). On that TV, Home with a moving wallpaper redrew 31 times a second while idle (about 26 % of a core), and a cold start took 2.9–3.4 s.

- **Solid background** (new default): one flat colour; nothing is decoded, animated or redrawn. Settings → Wallpapers → Background offers Solid, Ambient glow, or Wallpaper (a still picture).
- **Wallpaper motion removed:** no Ken Burns pan, transition zoom or focus parallax on Home, and the Motion settings are gone. The screensaver keeps its own slow pan.
- **One-time switch:** existing installs move to Solid once (`bg_solid_062`); another background can be chosen again afterwards.
- **Idle guard:** if anything ever animates the wallpaper, it pauses after 45 s without a remote press.
- **Start-up checkpoints:** a single log line ("startup: app … · views … · rows … · frame … · ready …", ms since the process started) for profiling slow TVs with `adb logcat`.
- **Verified on the API 36 emulator:** idle frames 132 → 0 per 5 s; upgrade from a moving wallpaper switches to solid; UI checks 74/74, unit tests 67/67. Not yet measured on the TV.

## 0.6.1 — Home returns to the app you were using (2026-11-03)

versionCode 12.

- **Found on the TCL TV:** Hotstar was closed in the middle of playback, by background app updates ("installPackageLI", "pkg removed") and by TCL's memory manager (`com.tcl.framework.custom` force-stops apps, Aura included, every few minutes). The TV dropped back to Aura with focus on the first card, Netflix, so the next OK press opened Netflix.
- **Fix:** Aura remembers the last app opened from it (`last_launched`, so it survives Aura being killed), and Home focuses that app's card. Back on Home still goes to the first card.
- **Verified on the API 36 emulator:**
  - app force-stopped → focus on that app;
  - Home from another app → focus on that app;
  - Aura killed and restarted → focus on that app;
  - Back → first card.

  UI checks 74/74, unit tests 67/67.

## 0.6.0 — screensaver with a photo of the day (2026-11-03)

versionCode 11. Same signing key as every earlier release.

- **Aura screensaver** (`AuraDreamService`): a new photo every minute with a slow Ken Burns pan, the clock and date in Aura's style, and the photo's title and credit. Android starts it after the idle time chosen in Settings → Device Preferences → Screen saver; video apps keep the screen awake, so it doesn't interrupt playback.
- **Photo sources** (Settings → System → Screensaver Photos):
  - Bing photo of the day (default, the last 8 days);
  - Wikipedia picture of the day (CC-licensed, portrait pictures skipped);
  - NASA picture of the day (its API wasn't reachable on 2026-11-03);
  - Aura's built-in wallpapers (offline, no network).
- **Caching:** photos are cached in app storage (about 3 MB) and refreshed at most every 6 hours in the background. With no internet and nothing cached, the screensaver falls back to the built-in wallpapers.
- **Network:** Aura now has the INTERNET permission, used only by the screensaver's photo download. Choosing "Aura wallpapers (offline)" stops it.
- **Tests:** photo-feed parsers have unit tests (67/67 in total). On the API 36 emulator, the screensaver started after the idle time, changed photo after a minute, exited on a key press, and worked with Bing, Wikipedia and offline. UI checks 74/74 (G5 now counts grid positions, so two apps with the same name both count).

## 0.5.1 — app grid on Home (2026-10-22)

versionCode 10. Same signing key as every earlier release.

- **Every app on Home in a 4-column grid** (`HomeGrid`): favourites first, in your order, then all other apps. ◀▶ move across a row and stop at its ends, ▼ goes to the card below and scrolls the grid with part of the next row in view, ▲ from the top row reaches the chips. Settings → Layout → "All Apps Grid on Home" switches back to the single favourites row.
- Grid checks G1–G6 in `tools/ui_check.py` (every app reachable with the remote, focused card always fully on screen). UI checks 74/74 on API 25 and API 36; unit tests 63/63. Emulators only.

## 0.5.0 — new Home design (2026-10-19)

versionCode 9.

- Top left: greeting, large clock and date. Top right: All Apps, Wi-Fi (network name or "Offline"; opens network settings) and Settings chips.
- The focused app's full name is the title above the cards; cards sit in a rounded dock.
- **Ambient glow** background (new default): a soft glow in the focused app's own colour. It decodes no wallpaper and runs no motion loop. Settings → Wallpapers → Background switches back to wallpapers.
- New read-only permissions ACCESS_NETWORK_STATE and ACCESS_WIFI_STATE for the Wi-Fi chip. Aura still has no INTERNET permission.
- Removed the Signature Line and Branding options (not part of this design).
- Installed on the Fire TV on 2026-10-22 and confirmed to render correctly there.

## 0.4.0 — Home-redirect fixes (2026-10-14)

versionCode 7.

- A Home press during Fire OS's 5 s hold made Android show Aura and then Amazon Home 5 ms later, which was taken as a deliberate double press and left you on Amazon Home. Reproduced on the emulator 2/2 and on the Fire TV; fixed (`RedirectPolicy.MIN_REACTION_MS`).
- Fire TV Settings sub-screens (`com.amazon.tv.settings*`) no longer end an explicit Amazon Home visit. Known gap: the Live TV settings page (`com.amazon.tv.livetv`) still bounces Back to Aura.
- On the Fire TV (Launcher Hijack stopped): Home from Aura, YouTube and the Appstore, rapid presses, a press during the hold and the double-press escape all passed; Aura appears 5.0–5.1 s after Home. Reboot, navigation and performance weren't tested before the TV was reset.

## 0.3.3 — resource and lifecycle release (2026-10-14)

versionCode 6. Signed with the same key as 0.3.0–0.3.2. The manifest, permissions and components are unchanged. The Home-button redirect behaviour and Launcher Hijack handling are unchanged, and there are no new features. Verified on emulators only; 0.3.3 has not been installed on the Fire TV. Full report: docs/fixes-0.3.3 (in git history).

From the REA analysis of 0.3.2:
- **Tiles shown at two sizes were re-rendered on every cold start.** Favourites and Recently used asked for different sizes, and each disk write deleted the other size. Measured on API 25: 4 re-renders per cold start before, 0 after.
- **"Open Amazon Home" started the Home-button process even with the redirect off.**
- **A recreated Home screen leaked the previous one** through the hourly greeting timer. The new check L2 counts 11 live activities after 10 recreations without the fix, and 1 with it.
- **The redirect poll asked AppOps for usage access on every tick.** It now asks once a minute while access is granted.
- **The Home-button process started at every boot even with the redirect off.** BootReceiver is now disabled while the redirect is off. Measured on API 25: before, the process started at boot and held 5.2 MB; after, it doesn't start.

From the final reliability review:
- **The tile disk cache is pruned after every app scan.** This removes tiles of apps uninstalled while Aura wasn't running and tiles from older app versions or styles. A 24 MB cap is a hard bound.
- **BootReceiver always reconciles with the saved preference**, including the first update from 0.3.2. Restore From Backup and Reset now re-apply the redirect settings too, so the service and receiver can't drift from them.
- **An accessibility window event could start a second redirect poll loop.** This doubled polling in instant mode. Fixed.
- **A denied usage-access check is cached for 9 s**, so bursts of polls don't call AppOps repeatedly. A restored grant is still noticed on the next 10 s retry.

Tooling:
- `ui_check.py` has a `lifecycle` group (recreation leak).
- New scripts: `redirect_check.py` (redirect and BootReceiver), `resource_check.py` (tile cache and boot) and `upgrade_check.py`. All are emulator-only.
- The project is now a local git repository. Signing passwords moved from `app/build.gradle.kts` to the git-ignored `keystore/signing.properties`.

Verification:
- Unit tests: 56/56.
- UI checks: 69/69 on API 25 and on API 36.
- Redirect and BootReceiver: 15/15 on API 25, including two reboots.
- Upgrade from 0.3.2: 8/8. Same app user ID and install date, settings file byte-identical, custom settings kept, BootReceiver reconciled on update.
- Performance (API 36, both orders): no measurable difference in the launcher UI process. Cold start medians were 472→438 ms and 444→412 ms, within run-to-run noise.

## 0.3.2 — stability release (2026-10-14)

versionCode 5. Signed with the same key as 0.3.0 and 0.3.1. Still minSdk 22 with no native code, so the APK is ABI-neutral and runs on the Fire TV Stick 4K's 32-bit ARM. The Home-button redirect and Launcher Hijack handling are unchanged. Android's default directional focus handling is unchanged.

Found while checking 0.3.1 on the Fire TV, then reproduced and fixed on emulators only. 0.3.2 has not been installed on the Fire TV.

- **The full name was missing when All Apps opened.** All Apps focuses its first card before the grid has been laid out. The card's name had no width yet, so it never counted as cut off, and the header showed the usual hint until focus moved. The check now waits for the card's first layout (`TileCell.whenLaidOut`). Home's rows get the same guard. Check A7 fails on 0.3.1 and passes with the fix.
- **Not reproduced: ▶ jumping rows in All Apps.** On the TV, one ▶ from the first card once landed on row 2's last card. It didn't happen in any emulator run: 38 apps and the TV's 17-app layout, with animations off and with wallpaper motion on. Aura doesn't handle ◀/▶ in All Apps itself; Android's own focus search does. Check A8 now covers that path.

Verification (docs/fixes-0.3.2/results (in git history)):

- **UI checks:** 66/66 on API 25 and on API 36 (1080p, debug build of the same source). The All Apps group scores 7/8 without the fix (A7 fails).
- **Unit tests:** 44/44.
- **Performance:** release builds of 0.3.1 and 0.3.2 on a freshly booted API 36 emulator, run in both orders (run A (in git history), run B (in git history)). There is no consistent difference.
  - Run B, cold start (median): 460 ms vs 452 ms.
  - Run A, cold start (median): 0.3.1 came out at 131 ms against 397 ms. 0.3.1 measured 480–540 ms earlier the same day, and run B doesn't repeat the gap, so this is treated as noise.
  - Memory (1080p PSS) and 4K memory change direction between the runs (about ±10 MB).
  - Idle CPU, idle frame timing and navigation frame timing match.
  - Two earlier pairs of runs were discarded. The Mac was under heavy load (load average 11–13), which slowed both builds about three-fold.
- **Upgrade:** the 0.3.1 release was installed on an API 25 emulator with custom settings (Warm Ambient theme, accent, 24-hour clock, a custom favourites order, animation speed). Installing the 0.3.2 release over it with `adb install -r` kept the same app user ID and install date. The settings file is byte-identical (same MD5), the cached files are kept, and Home looks the same.

## 0.3.1 — UI/UX bug-fix release (2026-10-14)

A focused fix pass for the issues in AURA_UI_UX_AUDIT.md (in git history). No redesign, no new
features, no network access. The Home-button integration (redirect service, accessibility mode,
Launcher Hijack compatibility) is unchanged.

Every fix below was verified on emulators only (`tools/ui_check.py`, API 25 and API 36 at 1080p, and
API 36 at 4K). Nothing was installed on, or measured from, the Fire TV. Results are in
docs/fixes-0.3.1/ (in git history).

### Phase 1: remote navigation (critical)

| Audit | Fix | Verified by |
|---|---|---|
| A4 | **◀/▶ worked nowhere in Settings.** Settings swallowed every ◀/▶ in `Activity.onKeyDown`, which runs *before* Android's focus search. Theme cards, accent swatches and wallpaper columns couldn't be navigated, and ◀ never returned to the sidebar. Only ◀ on the sidebar itself is consumed now (`SettingsKeys`). | S2–S9, S13, S36–S37; `UiFixesTest.settingsOnlyConsumesLeftOnTheSidebar` |
| A5 | **Switches flipped while navigating.** ◀ turned a switch off, and ▶ turned it on. Now ◀ ▶ only move focus and **Select** toggles. Choice rows (Clock Format, Dim Level, …) still change their value with ◀ ▶, as before. | S18, S20–S25 |
| — | **▲ from the first Settings row jumped into the sidebar** (and switched section). **Rapid ▼ in the sidebar escaped** into the page. Focus now stays inside its pane at both ends. | S40, S41 |
| A1 | **Settings content drew over the page heading.** The page scroller now clips at its top and bottom (a RenderNode clip, so no extra layer). Its sides stay open, so focus rings are never cut. | S26 |
| A2 | **All Apps drew over its title, the brand and the clock** when scrolled. Same scroller. | A2, A3 |
| A3 | **Focused theme and wallpaper cards lost their border** on the first and last items. Three clippers were involved: the card rows' padding, the scroller, and the Settings pane's right padding. All three now let the scaled card draw. | S3, S6, S38 (1080p and 4K) |
| A6 | **Back from a sub-page focused the first row.** Each page remembers its focused row and scroll offset. Back restores both, and ▶ from the sidebar returns to the row you left. | S29, S33, S34 |
| A6 | **Empty sub-pages (e.g. "No hidden apps") left focus on the sidebar,** where ▲▼ would silently switch section. The message row now takes focus. | S32 |
| A8 | **▶ into a page could leave its first section label hidden** under the heading. A first visit now starts at the top. | S2 (screenshot) |
| — | **Clock showed "10:39 A".** The time line was sized to the date line's width, so "AM" was clipped whenever the hour had two digits. This wasn't in the audit: it was found in the baseline emulator screenshots. | screenshots (`home_*`) |

### Phase 2: one focus style

`FocusSpec` now defines focus for every control:

- **Focused** = ring in the theme colour. The width comes from the theme, never thinner than 2 dp (themes previously had widths that were never used).
- **Scale:** app cards use the theme's own scale; Settings cards, swatches and the gear use 1.08 (previously 1.06, 1.12 and 1.15).
- **Timing:** 180 ms in, 140 ms out, always ease-out and always scaled by the Interface Animation setting.
- **Highlight:** one accent fill and stroke for focused rows, sidebar entries and buttons (previously three different strengths).
- **Selected** = a ✓ badge (themes, wallpapers, accent swatches) instead of a second ring, so "focused and selected" shows both cues. The tick colour contrasts with the accent.
- **Glow:** the mask is softer (alpha 150 → 110), and on light cards (white banners) the glow is drawn at 60 %, so white banners no longer bloom.
- **No end-of-row bump** when Interface Animation is Off (it used a fixed 70 ms before).
- **Switch knob contrast:** on light accents (e.g. Minimal Dark's white) the switch's "on" state was white on white. The knob now contrasts with the track.

Verified by S1–S42 and `UiFixesTest`, including a source scan that fails the build if any focus animation hard-codes a duration.

### Phase 3: small UX improvements

- **Bright cards:** near-white banner backgrounds get a light, uniform shade (at most 12 %, logos untouched) and no glare sheen. Generated icon cards are a little lighter (HSV value 0.42 → 0.50). This happens once at render time and is cached, so it has no runtime cost. The tile cache style is now v4, so cards re-render once.
- **Long names:** names stay on one line with "…". When the focused card's name is cut, the complete name appears in the row header ("Favourites   Aurora Streaming Service…"). In All Apps it appears in the header's right-hand slot. Checks H12 and A5.
- **No lone "Apps" row:** when Recently Used is empty, All Apps ends the favourites row instead of taking a whole row (H8, H9). With recently used apps, the layout is unchanged: All Apps ends that row (H13, H14). Favourites keeps its two-row position, clear of the signature line (H15).
- Horizontal row scrolling is unchanged (H2–H7).
- App cards expose their name as a content description (accessibility).

### Phase 4: performance

- **Wallpaper decode capped at 1920×1080** (keeping the aspect ratio, `DecodeSize`). On a 4K display the decode was 3840×2160 ARGB (≈33 MB, doubled during a crossfade). The art is 1080p and the GPU scales it anyway. 1080p screens, including the Fire TV Stick 4K's UI, are unaffected. Imported wallpapers are stored at the same cap.
- **Display changes:** if the screen density or size changes while Aura is open, the UI is rebuilt with the new metrics instead of keeping stale pixel sizes (audit D2).
- No new background work, layers or animations.

### Testing added

- `app/src/test/.../UiFixesTest.kt`: 11 JVM tests (44 in total, all passing).
- `tools/ui_check.py`: 64 remote-navigation and layout checks driven by key events. Focus is read from `uiautomator`, and pixel checks prove that nothing draws over fixed elements. It refuses any non-emulator serial.
- `tools/perf_compare.py`: before/after start-up, memory, CPU and frame timing on an emulator.
- `testfixture/`: 20 tiny emulator-only packages (long names, white and dark banners) for 20+ app, long-name and bright-card tests.

### Not changed

- Home-button redirect, the accessibility service, boot behaviour and Launcher Hijack handling.
- Layout and visual design, apart from the fixes above.
- The audit's optional D3 (UI scale on 1280 dp-wide displays) was left out as a new feature.
