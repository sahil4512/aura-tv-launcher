#!/usr/bin/env python3
"""
Aura TV remote-navigation and layout regression checks.

EMULATOR ONLY: the serial must look like ``emulator-NNNN``; anything else (such as a TV at
``192.0.2.10:5555``) is refused before a single adb command runs.

    ./gradlew :app:assembleDebug :testfixture:assembleDebug
    tools/ui_check.py --serial emulator-5554 --apk app/build/outputs/apk/debug/app-debug.apk \
        --fixture testfixture/build/outputs/apk --out /tmp/aura-ui

The APK must be debuggable (preferences are seeded with ``run-as``). Each check records PASS/FAIL
with evidence; screenshots land in ``--out``. Focus is read from ``uiautomator dump`` (wallpaper motion
is switched off during the run so the window goes idle). Pixel checks use ffmpeg crops.
"""
import argparse
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PKG = "com.auratv.launcher"
FIX = "com.auratv.testfixture"
ACT = PKG + "/.ui.MainActivity"
SIDEBAR = ["Appearance", "Apps", "Layout", "System", "About"]
THEMES = ["Midnight", "Minimal Dark", "Soft Glass", "Cinematic", "Warm Ambient"]
LONG_NAME = "Aurora Streaming Service with an Extremely Long Name"


# ----------------------------------------------------------------------------- device

class Dev:
    def __init__(self, serial, out):
        if not re.fullmatch(r"emulator-\d+", serial):
            sys.exit(f"refusing to run on {serial!r}: UI checks are emulator-only")
        self.serial = serial
        self.out = out
        os.makedirs(out, exist_ok=True)
        self.w, self.h, self.density = self.screen()

    def adb(self, *args, binary=False, timeout=120):
        r = subprocess.run(["adb", "-s", self.serial, *args], capture_output=True, timeout=timeout)
        return r.stdout if binary else r.stdout.decode(errors="replace")

    def sh(self, cmd):
        return self.adb("shell", cmd)

    def screen(self):
        size = self.sh("wm size")
        m = re.findall(r"(\d+)x(\d+)", size)
        w, h = map(int, m[-1])
        dens = re.findall(r"density: (\d+)", self.sh("wm density"))
        return w, h, int(dens[-1]) / 160.0

    def dp(self, v):
        return int(v * self.density + 0.5)

    def key(self, *names, wait=0.45):
        for n in names:
            self.sh(f"input keyevent {n}")
            time.sleep(wait)

    def burst(self, name, n):
        """Rapid input: n key presses in one shell call (no pause between them)."""
        self.sh("input keyevent " + " ".join([name] * n))

    def shot(self, name):
        path = os.path.join(self.out, name + ".png")
        with open(path, "wb") as f:
            f.write(self.adb("exec-out", "screencap", "-p", binary=True))
        return path

    def tree(self):
        for _ in range(6):
            self.sh("rm -f /sdcard/aura_ui.xml")
            self.sh("uiautomator dump /sdcard/aura_ui.xml")
            x = self.adb("exec-out", "cat", "/sdcard/aura_ui.xml")
            if x.startswith("<?xml"):
                try:
                    return ET.fromstring(x)
                except ET.ParseError:
                    pass
            time.sleep(0.8)
        return None

    def focus(self):
        """(description, (l, t, r, b)) of the focused view inside Aura, or (None, None)."""
        root = self.tree()
        if root is None:
            return "<uiautomator dump failed>", None
        for n in root.iter("node"):
            if n.get("focused") == "true" and n.get("package") == PKG:
                return describe(n), bounds(n)
        return None, None

    def texts(self):
        root = self.tree()
        return [] if root is None else [n.get("text") for n in root.iter("node") if n.get("text")]

    def find(self, text):
        root = self.tree()
        if root is None:
            return None
        for n in root.iter("node"):
            if n.get("text") == text:
                return bounds(n)
        return None

    # -- preferences (debuggable builds only)

    def prefs(self):
        return self.adb("shell", f"run-as {PKG} cat shared_prefs/aura_prefs.xml")

    def write_prefs(self, xml):
        tmp = os.path.join(self.out, "_prefs.xml")
        with open(tmp, "w") as f:
            f.write(xml)
        self.adb("push", tmp, "/data/local/tmp/aura_prefs.xml")
        self.sh(f"run-as {PKG} sh -c 'cat /data/local/tmp/aura_prefs.xml > shared_prefs/aura_prefs.xml'")

    def pref(self, name):
        m = re.search(rf'name="{re.escape(name)}"(?: value="([^"]*)"\s*/>|>([^<]*)</string>)', self.prefs())
        if not m:
            return None
        return m.group(1) if m.group(1) is not None else m.group(2)

    def stop(self):
        # Android 7 restarts the process for a moment while force-finishing a foreground activity;
        # that cached process would keep stale preferences in memory. Stop until nothing is left.
        for _ in range(6):
            self.sh(f"am force-stop {PKG}")
            time.sleep(1.0)
            if not self.sh(f"pidof {PKG}").strip():
                return

    def start(self, settle=5.0):
        # A key press first takes the emulator out of touch mode, as on a real TV (no touchscreen),
        # so Aura's initial requestFocus() is honoured.
        self.sh("input keyevent DPAD_DOWN")
        self.sh(f"am start -n {ACT}")
        time.sleep(settle)


def describe(n):
    if n.get("text"):
        return n.get("text")
    if n.get("content-desc"):
        return n.get("content-desc")
    for c in n.iter("node"):
        if c.get("text"):
            return c.get("text")
    return n.get("class", "?").split(".")[-1]


def bounds(n):
    l, t, r, b = map(int, re.findall(r"-?\d+", n.get("bounds")))
    return l, t, r, b


def set_pref(xml, kind, name, value):
    if kind == "string":
        value = value.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "&#10;")
        new = f'    <string name="{name}">{value}</string>'
        pat = rf'\s*<string name="{re.escape(name)}">.*?</string>'
    else:
        new = f'    <{kind} name="{name}" value="{value}" />'
        pat = rf'\s*<{kind} name="{re.escape(name)}" value="[^"]*" />'
    xml = re.sub(pat, "", xml, flags=re.S)
    return xml.replace("</map>", new + "\n</map>")


# ----------------------------------------------------------------------------- pixels (ffmpeg)

def _ff(png, vf):
    r = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", png, "-vf", vf, "-f", "rawvideo", "-"],
                       capture_output=True)
    return list(r.stdout)


def lumas(png, x, y, w, h, cols):
    """Mean luma of `cols` equal vertical strips across the crop."""
    x, y = max(0, x), max(0, y)
    return _ff(png, f"crop={w}:{h}:{x}:{y},format=gray,scale={cols}:1:flags=area")


def diff(a, b, x, y, w, h):
    r = subprocess.run(["ffmpeg", "-loglevel", "error", "-i", a, "-i", b, "-filter_complex",
                        f"[0]crop={w}:{h}:{x}:{y}[a];[1]crop={w}:{h}:{x}:{y}[b];"
                        "[a][b]blend=all_mode=difference,format=gray,scale=1:1:flags=area",
                        "-f", "rawvideo", "-"], capture_output=True)
    return r.stdout[0] if r.stdout else 255


# ----------------------------------------------------------------------------- checks

class Run:
    def __init__(self, d):
        self.d = d
        self.results = []

    def check(self, cid, name, ok, detail=""):
        self.results.append((cid, name, bool(ok), detail))
        print(f"  {'PASS' if ok else 'FAIL'}  {cid:5s} {name}  {detail}", flush=True)

    def expect_focus(self, cid, name, want, extra=""):
        got, b = self.d.focus()
        ok = got == want if not callable(want) else want(got, b)
        self.check(cid, name, ok, f"focus={got!r} {b or ''} {extra}".strip())
        return got, b


def seed(d, fav_count=13, history=""):
    """Fresh install state with deterministic prefs."""
    d.stop()
    xml = d.prefs()
    known = re.search(r'name="known_apps">(.*?)</string>', xml, re.S)
    keys = [k for k in (known.group(1).split("&#10;") if known else []) if k and not k.startswith(PKG)]
    fixture = [k for k in keys if k.startswith(FIX)]
    others = [k for k in keys if not k.startswith(FIX)]
    first = [k for k in fixture if k.split("/")[0].endswith((".longname", ".bright"))]
    favs = (others[:2] + first + others[2:] + [k for k in fixture if k not in first])[:fav_count]
    for kind, name, val in [
        ("string", "favorites", "\n".join(favs)), ("boolean", "favorites_seeded", "true"),
        ("boolean", "system_apps", "true"), ("boolean", "motion_cinematic", "false"),
        ("boolean", "parallax", "false"), ("boolean", "recents_row", "true"),
        ("string", "launch_history", history), ("int", "ui_anim", "1"),
        ("string", "theme", "midnight"), ("int", "accent", "0"), ("boolean", "clock", "true"),
        ("int", "clock_format", "0"), ("int", "icon_size", "1"), ("boolean", "apps_row", "false"),
        ("int", "wp_rotate_min", "0"), ("boolean", "home_grid", "false"),
    ]:
        xml = set_pref(xml, kind, name, val)
    d.write_prefs(xml)
    d.start()
    d.key("BACK")  # leaves touch mode; Back on Home focuses the first favourite
    return favs


def home_checks(R):
    d = R.d
    print("== Home rows")
    favs = seed(d)
    R.expect_focus("H1", "initial focus on first favourite", lambda g, b: g is not None and b and b[0] < d.dp(80))
    _, fb = d.focus()
    root = d.tree()
    tag = [bounds(n) for n in root.iter("node") if (n.get("text") or "").startswith("A calmer home")] if root is not None else []
    if fb and tag:
        R.check("H15", "favourites row (with names) stays clear of the signature line",
                fb[3] + d.dp(30) < tag[0][1], f"row bottom≈{fb[3] + d.dp(30)} tagline top={tag[0][1]}")
    d.shot("home_start")
    # Walk right one press at a time to the very end.
    seen = [d.focus()[0]]
    for _ in range(len(favs) + 2):
        d.key("DPAD_RIGHT", wait=0.4)
        seen.append(d.focus()[0])
    got, b = d.focus()
    d.shot("home_end")
    R.check("H2", "last card of favourites reachable", got in ("All Apps", "Add"), f"focus={got!r}")
    R.check("H3", "last card fully on screen (inside 48 dp margin)",
            b is not None and b[2] <= d.w - d.dp(40) and b[0] >= d.dp(40), f"bounds={b} screen={d.w}")
    d.key("DPAD_RIGHT", wait=0.8)
    got2, _ = d.focus()
    R.check("H4", "RIGHT at the end keeps focus", got2 == got, f"focus={got2!r}")
    # The last real application (one before Add / All Apps) fully visible when focused.
    d.key("DPAD_LEFT", wait=0.5)
    if got == "All Apps":
        d.key("DPAD_LEFT", wait=0.5)
    ga, ba = d.focus()
    R.check("H5", "last application fully visible", ba is not None and ba[2] <= d.w - d.dp(40), f"{ga!r} {ba}")
    # Rapid input back to the start.
    d.burst("DPAD_LEFT", len(favs) + 6)
    time.sleep(1.5)
    got, b = d.focus()
    R.check("H6", "rapid LEFT x N lands on first favourite, row reset", b is not None and b[0] < d.dp(80),
            f"focus={got!r} {b}")
    d.burst("DPAD_RIGHT", 6)
    time.sleep(1.2)
    got, b = d.focus()
    R.check("H7", "rapid RIGHT x6 moves exactly 6 cards", got == seen[6], f"focus={got!r} expected={seen[6]!r}")
    d.burst("DPAD_LEFT", 10)
    time.sleep(1.0)
    # Row structure with no recently-used apps.
    t = d.texts()
    R.check("H9", "no recents: All Apps ends the favourites row", seen[-1] == "All Apps", f"last={seen[-1]!r}")
    tl = [x.lower() for x in t]
    R.check("H8", "no lone 'Apps' row when Recently Used is empty",
            "apps" not in tl and "recently used" not in tl, f"headers={[x for x in t if x.lower() in ('apps', 'recently used')]}")
    d.key("DPAD_UP")
    got, b = d.focus()
    R.check("H10", "UP from favourites reaches the quick-action chips", b is not None and b[1] < d.dp(110), f"{got!r} {b}")
    d.key("DPAD_DOWN")
    got, b = d.focus()
    R.check("H11", "DOWN from the chips returns to favourites", b is not None and b[1] > d.dp(150), f"{got!r} {b}")
    # Long name: label stays one line, full name shown in the details area.
    idx = next((i for i, k in enumerate(favs) if k.split("/")[0].endswith(".longname")), None)
    if idx is not None:
        d.burst("DPAD_LEFT", 20)
        time.sleep(1)
        d.key(*["DPAD_RIGHT"] * idx, wait=0.4)
        time.sleep(0.6)
        got, b = d.focus()
        root = d.tree()
        # The big title above the dock shows the focused card's full name (cards carry no names).
        hero = [bounds(n) for n in root.iter("node") if n.get("text") == LONG_NAME
                and b is not None and bounds(n)[3] <= b[1]] if root is not None else []
        d.shot("home_long_name")
        R.check("H12", "focused long name: full name in the title above the dock", got == LONG_NAME and bool(hero),
                f"focus={got!r} title={hero}")
    # Recently Used present: All Apps ends that row instead.
    d.stop()
    seed(d, history="com.auratv.testfixture.app01\ncom.auratv.testfixture.app02")  # fixture launched before
    t = d.texts()
    d.shot("home_with_recents")
    R.check("H13", "with recents: 'Recently Used' row shown", any(x.lower() == "recently used" for x in t),
            f"{[x for x in t if x.lower() in ('apps', 'recently used')]}")
    d.key("DPAD_DOWN")
    d.burst("DPAD_RIGHT", 12)
    time.sleep(1.2)
    got, _ = d.focus()
    R.check("H14", "with recents: All Apps ends the Recently Used row", got == "All Apps", f"focus={got!r}")


def allapps_checks(R):
    d = R.d
    print("== All Apps")
    seed(d)
    # Reach the All Apps card at the end of favourites.
    d.burst("DPAD_RIGHT", 30)
    time.sleep(1.5)
    got, _ = d.focus()
    if got != "All Apps":
        d.key("DPAD_DOWN")
        d.burst("DPAD_RIGHT", 12)
        time.sleep(1)
    d.key("DPAD_CENTER", wait=1.5)
    t = d.texts()
    count = next((int(x) for x in t if x.isdigit()), 0)
    R.check("A1", "All Apps lists 20+ applications", count >= 20, f"count={count}")
    before = d.shot("allapps_top")
    root = d.tree()
    heads = [bounds(n) for n in root.iter("node") if n.get("text") == "All Apps"] if root is not None else []
    hb = min(heads, key=lambda x: x[1]) if heads else None  # the overlay title, not Home's card label
    d.key(*["DPAD_DOWN"] * 5, wait=0.5)
    time.sleep(0.6)
    after = d.shot("allapps_scrolled")
    if hb:
        # Everything above the grid (brand, header) must be unchanged; skip the clock on the right.
        top = hb[3] + d.dp(6)
        dv = diff(before, after, 0, 0, d.w // 2, top)
        R.check("A2", "scrolled grid never draws over title/brand", dv <= 2, f"mean diff={dv} (region y<{top})")
        dv2 = diff(before, after, int(d.w * 0.6), 0, int(d.w * 0.4), d.dp(40))
        R.check("A3", "scrolled grid never draws over the clock area", dv2 <= 3, f"mean diff={dv2}")
    got, b = d.focus()
    R.check("A4", "DOWN x5 keeps focus inside the grid", b is not None and b[1] > (hb[3] if hb else 0), f"{got!r} {b}")
    # Long name in the grid: details area shows the full name.
    d.burst("DPAD_UP", 8)
    time.sleep(1)
    for _ in range(40):
        got, b = d.focus()
        if got == LONG_NAME:
            break
        d.key("DPAD_RIGHT", wait=0.35)
    count = sum(1 for x in d.texts() if x == LONG_NAME)
    d.shot("allapps_long_name")
    R.check("A5", "All Apps long name: full name in header details", got == LONG_NAME and count >= 2,
            f"focus={got!r} occurrences={count}")
    d.key("BACK", wait=1.2)
    got, _ = d.focus()
    R.check("A6", "Back from All Apps restores Home focus", got == "All Apps", f"focus={got!r}")

    # The Fire TV's layout: 17 apps (rows of 6, 6, 5) with a long name first. Only test apps stay
    # visible, so the long name sorts first.
    seed(d)
    d.stop()
    xml = d.prefs()
    known = [k for k in re.search(r'name="known_apps">(.*?)</string>', xml, re.S).group(1).split("&#10;")
             if k and not k.startswith(PKG)]
    fixture = sorted(k for k in known if k.startswith(FIX))
    keep = set([k for k in fixture if ".longname" in k] + [k for k in fixture if ".longname" not in k][:16])
    d.write_prefs(set_pref(xml, "string", "hidden", "\n".join(k for k in known if k not in keep)))
    d.start()
    d.key("BACK")
    d.burst("DPAD_RIGHT", 30)
    time.sleep(1.5)
    if d.focus()[0] != "All Apps":
        d.key("DPAD_DOWN")
        d.burst("DPAD_RIGHT", 12)
        time.sleep(1)
    d.key("DPAD_CENTER", wait=1.5)
    got, first = d.focus()
    root = d.tree()
    # The header slot sits above the grid; Home's own card label (behind the overlay) does not count.
    header = [bounds(n) for n in root.iter("node") if n.get("text") == LONG_NAME
              and first is not None and bounds(n)[3] <= first[1]] if root is not None else []
    d.shot("allapps_open_long_name")
    R.check("A7", "All Apps opening on a long name shows it in full at once", got == LONG_NAME and bool(header),
            f"focus={got!r} header={header}")
    d.key("DPAD_RIGHT")
    _, b1 = d.focus()
    d.key("DPAD_LEFT", "DPAD_DOWN", "DPAD_DOWN", "DPAD_UP", "DPAD_UP", "DPAD_RIGHT", wait=0.6)
    _, b2 = d.focus()
    ok = all(b is not None and first is not None and b[1] == first[1] and b[0] > first[2] for b in (b1, b2))
    R.check("A8", "17 apps: RIGHT from the first card reaches the second (also after DOWN DOWN UP UP)", ok,
            f"first={first} right={b1} after-walk={b2}")
    d.key("BACK", wait=1.2)
    d.stop()
    d.write_prefs(set_pref(d.prefs(), "string", "hidden", ""))  # later groups expect every app


def grid_checks(R):
    """Home's 4-column app grid: every app reachable with the remote, focus always fully visible."""
    d = R.d
    print("== Home grid")
    seed(d, fav_count=6)
    d.stop()
    d.write_prefs(set_pref(d.prefs(), "boolean", "home_grid", "true"))
    d.start()
    d.key("BACK")
    first, fb = d.focus()
    R.check("G1", "grid: initial focus on the top-left card", fb is not None and fb[0] < d.dp(80), f"{first!r} {fb}")
    d.key("DPAD_RIGHT", "DPAD_RIGHT", "DPAD_RIGHT", wait=0.45)
    g4, b4 = d.focus()
    d.key("DPAD_RIGHT", wait=0.6)
    g5, b5 = d.focus()
    R.check("G2", "grid: RIGHT x3 reaches the 4th column, RIGHT again stays",
            b4 is not None and fb is not None and b4[1] == fb[1] and b4[0] > d.w // 2 and g5 == g4, f"{g4!r} {b4} then {g5!r}")
    d.burst("DPAD_LEFT", 4)
    time.sleep(0.6)
    d.key("DPAD_DOWN", wait=0.7)
    g, b = d.focus()
    R.check("G3", "grid: DOWN goes to the card below", b is not None and fb is not None and abs(b[0] - fb[0]) < d.dp(10) and b[1] > fb[1],
            f"{g!r} {b}")
    root = d.tree()
    hero = [n.get("text") for n in root.iter("node") if n.get("text") == g and b is not None and bounds(n)[3] <= b[1]] if root is not None else []
    R.check("G4", "grid: the title above shows the focused app's name", bool(hero), f"focus={g!r}")
    # Walk every row (DOWN), and every column in it, collecting names; each focused card must be on screen.
    d.key("BACK", wait=1.0)  # Back on Home returns to the first card (UP would leave the grid for the chips)
    # Count grid positions (row, x), not names: two apps may share a name (e.g. debug/preview builds).
    seen, offscreen, prev_row = set(), [], None
    for r in range(40):
        row_names = []
        for c in range(4):
            g, b = d.focus()
            if g:
                seen.add((r, b[0] if b else c)); row_names.append(g)
            if b is not None and (b[3] > d.h or b[1] < 0):
                offscreen.append((g, b))
            if c < 3:
                d.key("DPAD_RIGHT", wait=0.35)
        d.burst("DPAD_LEFT", 4)
        time.sleep(0.4)
        if row_names == prev_row:
            seen = {p for p in seen if p[0] != r}  # DOWN didn't move: this "row" is the previous one again
            break
        prev_row = row_names
        d.key("DPAD_DOWN", wait=0.6)
    d.shot("grid_bottom")
    # Compare with All Apps' own count (the leftmost chip).
    d.burst("DPAD_UP", 40)
    time.sleep(1)
    d.key("DPAD_UP", wait=0.5)
    d.burst("DPAD_LEFT", 3)
    time.sleep(0.4)
    d.key("DPAD_CENTER", wait=1.5)
    count = next((int(x) for x in d.texts() if x.isdigit()), 0)
    d.key("BACK", wait=1.0)
    R.check("G5", "grid: every app is reachable with the remote", count > 0 and len(seen) == count, f"walked {len(seen)} / All Apps {count}")
    R.check("G6", "grid: the focused card is always fully on screen", not offscreen, f"{offscreen[:3]}")


def open_settings(d):
    # UP from the favourites dock reaches the quick-action chips; Settings is the rightmost one.
    # Select on it opens the TV's own settings; holding Select opens Aura's settings.
    d.key("DPAD_UP", wait=0.5)
    d.burst("DPAD_RIGHT", 3)
    time.sleep(0.5)
    d.key("MENU", wait=1.5)  # ≡ on Home opens Aura's settings (a real remote can also hold Select on the chip)


def ring_visible(png, b, side, scale_hint=1.10):
    """Mean luma of the brightest 2-px column just outside/at the card edge on `side`."""
    l, t, r, bt = b
    h = bt - t
    y = t + h // 4
    grow = int((r - l) * 0.07) + 6  # a focused card grows ~4 % per side; sample past that, any density
    if side == "left":
        cols = lumas(png, l - grow, y, grow + 6, h // 2, grow + 6)
    else:
        cols = lumas(png, r - 6, y, grow + 6, h // 2, grow + 6)
    return max(cols) if cols else 0


def settings_checks(R):
    d = R.d
    print("== Settings")
    seed(d)
    open_settings(d)
    R.expect_focus("S1", "Settings opens on sidebar 'Appearance'", "Appearance")
    d.key("DPAD_RIGHT", wait=0.7)
    got, b = R.expect_focus("S2", "RIGHT from sidebar -> first theme card", "Midnight")
    p = d.shot("settings_first_card")
    if b:
        lum = ring_visible(p, b, "left")
        R.check("S3", "first theme card: left focus border fully visible", lum >= 170, f"edge luma={lum}")
    d.key("DPAD_RIGHT", wait=0.6)
    R.expect_focus("S4", "RIGHT -> Minimal Dark", "Minimal Dark")
    d.key("DPAD_RIGHT", "DPAD_RIGHT", "DPAD_RIGHT", wait=0.5)
    got, b = R.expect_focus("S5", "RIGHT x3 -> last theme card", "Warm Ambient")
    time.sleep(0.4)
    p = d.shot("settings_last_card")
    if b:
        lum = ring_visible(p, b, "right")
        R.check("S6", "last theme card: right focus border fully visible", lum >= 170, f"edge luma={lum}")
    d.key("DPAD_RIGHT", wait=0.6)
    R.expect_focus("S7", "RIGHT on last card stays", "Warm Ambient")
    d.burst("DPAD_LEFT", 4)
    time.sleep(1.0)
    R.expect_focus("S8", "rapid LEFT x4 -> Midnight", "Midnight")
    d.key("DPAD_LEFT", wait=0.6)
    R.expect_focus("S9", "LEFT from first card -> sidebar", "Appearance")
    # Theme selection
    d.key("DPAD_RIGHT", "DPAD_RIGHT", wait=0.6)
    d.key("DPAD_CENTER", wait=1.2)
    R.check("S10", "SELECT on Minimal Dark applies theme", d.pref("theme") == "minimal", f"theme={d.pref('theme')}")
    R.expect_focus("S11", "focus stays on Minimal Dark after theme change", "Minimal Dark")
    d.shot("settings_theme_selected")
    d.key("DPAD_LEFT", wait=0.6)
    d.key("DPAD_CENTER", wait=1.2)
    R.check("S12", "SELECT on Midnight restores theme", d.pref("theme") == "midnight", f"theme={d.pref('theme')}")
    # Accent swatches
    d.key("DPAD_DOWN", wait=0.6)
    got, b0 = d.focus()
    d.key("DPAD_RIGHT", wait=0.6)
    got1, b1 = d.focus()
    R.check("S13", "RIGHT between accent swatches", b0 and b1 and b1[0] > b0[0], f"{b0} -> {b1}")
    d.key("DPAD_CENTER", wait=1.0)
    acc = d.pref("accent")
    R.check("S14", "SELECT on swatch applies accent", acc not in (None, "0"), f"accent={acc}")
    got2, b2 = d.focus()
    R.check("S15", "focus stays on chosen swatch", b2 == b1, f"{b1} vs {b2}")
    for _ in range(3):  # ▼ from Midnight lands on the nearest swatch; walk left to "Theme colour"
        if d.focus()[0] == "Theme colour":
            break
        d.key("DPAD_LEFT", wait=0.5)
    d.key("DPAD_CENTER", wait=1.0)
    R.check("S16", "first swatch (theme colour) restores accent=0", d.pref("accent") == "0", f"accent={d.pref('accent')}")
    # Switch rows: arrows navigate, SELECT toggles
    d.key("DPAD_DOWN", wait=0.6)
    R.expect_focus("S17", "DOWN -> Show Clock switch", "Show Clock")
    before = d.pref("clock")
    d.key("DPAD_LEFT", wait=0.6)
    got, _ = d.focus()
    R.check("S18", "LEFT on a switch moves to sidebar without changing it",
            got == "Appearance" and d.pref("clock") == before, f"focus={got!r} clock {before}->{d.pref('clock')}")
    d.key("DPAD_RIGHT", wait=0.7)
    R.expect_focus("S19", "RIGHT from sidebar returns to the row you left", "Show Clock")
    d.key("DPAD_RIGHT", wait=0.6)
    R.check("S20", "RIGHT on a switch does not change it", d.pref("clock") == before, f"clock={d.pref('clock')}")
    d.key("DPAD_CENTER", wait=0.8)
    R.check("S21", "SELECT toggles the switch", d.pref("clock") == "false", f"clock={d.pref('clock')}")
    d.key("DPAD_CENTER", wait=0.8)
    R.check("S22", "SELECT toggles it back", d.pref("clock") == "true", f"clock={d.pref('clock')}")
    # Choice row: arrows change the value (by design)
    d.key("DPAD_DOWN", "DPAD_DOWN", wait=0.6)
    R.expect_focus("S23", "DOWN x2 -> Clock Format", "Clock Format")
    d.key("DPAD_RIGHT", wait=0.6)
    R.check("S24", "RIGHT on a choice row changes its value", d.pref("clock_format") == "1", f"fmt={d.pref('clock_format')}")
    d.key("DPAD_LEFT", wait=0.6)
    R.check("S25", "LEFT on a choice row changes it back", d.pref("clock_format") == "0", f"fmt={d.pref('clock_format')}")
    # Scrolling: heading never overdrawn
    hb = d.find("Appearance")  # sidebar item comes first; heading is the second match
    root = d.tree()
    heads = [bounds(n) for n in root.iter("node") if n.get("text") == "Appearance"] if root is not None else []
    heading = max(heads, key=lambda x: x[0]) if heads else None
    d.key("DPAD_UP", "DPAD_UP", "DPAD_UP", "DPAD_UP", wait=0.5)
    top = d.shot("settings_top")
    d.key(*["DPAD_DOWN"] * 12, wait=0.45)
    time.sleep(0.6)
    bottom = d.shot("settings_scrolled")
    if heading:
        x0 = heading[0] - d.dp(4)
        dv = diff(top, bottom, x0, heading[1] - d.dp(4), d.w - x0 - d.dp(48), heading[3] - heading[1] + d.dp(12))
        R.check("S26", "scrolled content never draws over the page heading", dv <= 2, f"mean diff={dv}")
    got, b = d.focus()
    R.check("S27", "DOWN x12 reaches the end of Appearance", got in ("Greeting", "Aura TV Branding", "Signature Line"), f"focus={got!r}")
    # Return to sidebar, back into content: same row
    d.key("BACK", wait=0.6)
    R.expect_focus("S28", "BACK from content -> sidebar", "Appearance")
    d.key("DPAD_RIGHT", wait=0.8)
    R.expect_focus("S29", "RIGHT returns to the last row", got)
    # Sub-page focus restoration
    d.key("BACK", wait=0.5)
    d.key("DPAD_DOWN", wait=0.8)
    R.expect_focus("S30", "sidebar DOWN -> Apps", "Apps")
    d.key("DPAD_RIGHT", wait=0.7)
    d.key("DPAD_DOWN", wait=0.5)
    R.expect_focus("S31", "Apps page: DOWN -> Hidden Apps", "Hidden Apps")
    d.key("DPAD_CENTER", wait=1.0)
    got, _ = d.focus()
    d.shot("settings_empty_subpage")
    R.check("S32", "empty sub-page keeps focus in the page", got is not None and got.startswith("No hidden apps"), f"focus={got!r}")
    d.key("BACK", wait=1.0)
    R.expect_focus("S33", "BACK from sub-page -> the row that opened it", "Hidden Apps")
    d.key("DPAD_UP", wait=0.5)
    d.key("DPAD_CENTER", wait=1.2)
    d.key("BACK", wait=1.0)
    R.expect_focus("S34", "BACK from Favourites picker page -> Favourites row", "Favourites")
    # No Wallpapers section any more (0.6.6): UP from Apps goes straight to Appearance.
    d.key("BACK", wait=0.5)
    d.key("DPAD_UP", wait=0.8)
    R.expect_focus("S35", "sidebar UP from Apps -> Appearance", "Appearance")
    R.check("S36", "no Wallpapers section in Settings", "Wallpapers" not in d.texts(), "")
    # Rapid sidebar movement and exit (focus is already on the sidebar after S35/S36)
    d.burst("DPAD_DOWN", 8)
    time.sleep(1.2)
    R.expect_focus("S40", "rapid DOWN x8 in sidebar stops at About", "About")
    d.burst("DPAD_UP", 8)
    time.sleep(1.2)
    R.expect_focus("S41", "rapid UP x8 in sidebar stops at Appearance", "Appearance")
    d.key("BACK", wait=1.2)
    got, b = d.focus()
    R.check("S42", "BACK from sidebar closes Settings (focus on Home)", b is not None and "Appearance" not in d.texts(), f"{got!r}")
    # The Settings chip itself (plain Select) opens the TV's own settings, not Aura's.
    d.key("DPAD_UP", wait=0.5)
    d.burst("DPAD_RIGHT", 3)
    time.sleep(0.5)
    d.key("DPAD_CENTER", wait=2.5)
    front = d.sh("dumpsys window | grep -m1 mCurrentFocus")
    R.check("S43", "Settings chip opens the TV's own settings", "settings" in front.lower() and PKG not in front, front.strip()[-80:])
    d.key("BACK", wait=1.0)
    d.sh(f"am start -n {ACT}")
    time.sleep(1.5)


def anim_off_checks(R):
    d = R.d
    print("== Animation off")
    seed(d)
    d.stop()
    d.write_prefs(set_pref(d.prefs(), "int", "ui_anim", "3"))
    d.start()
    d.key("BACK")
    d.burst("DPAD_RIGHT", 30)
    time.sleep(1.5)
    a = d.shot("animoff_end_a")
    got, b = d.focus()
    d.sh("input keyevent DPAD_RIGHT & sleep 0.12; screencap -p /sdcard/aura_b.png")
    bpath = os.path.join(d.out, "animoff_end_b.png")
    with open(bpath, "wb") as f:
        f.write(d.adb("exec-out", "cat", "/sdcard/aura_b.png", binary=True))
    if b:
        dv = diff(a, bpath, max(0, b[0] - d.dp(30)), b[1], b[2] - b[0] + d.dp(60), b[3] - b[1])
        R.check("N1", "no end-of-row bounce with animations off", dv <= 1, f"mean diff={dv}")


def live_activities(d, pid):
    """Activity instances alive in Aura's process after a forced GC (from dumpsys meminfo)."""
    d.sh(f"run-as {PKG} kill -10 {pid}")  # SIGUSR1: ART runs a full GC
    time.sleep(2.0)
    m = re.search(r"Activities:\s+(\d+)", d.sh(f"dumpsys meminfo {PKG}"))
    return int(m.group(1)) if m else -1


def lifecycle_checks(R, cycles=5):
    """Recreation leaks: a density change makes MainActivity recreate() itself. Afterwards exactly one
    Activity may remain; a pending main-thread callback (e.g. the hourly greeting tick) would keep
    every destroyed instance reachable."""
    d = R.d
    print("== Lifecycle")
    seed(d)
    pid = d.sh(f"pidof {PKG}").strip()
    R.check("L1", "one activity after launch", live_activities(d, pid) == 1, f"pid={pid}")
    override = re.search(r"Override density: (\d+)", d.sh("wm density"))
    base = int(override.group(1)) if override else int(d.density * 160)
    alt = base - 80 if base > 240 else base + 80
    for _ in range(cycles):
        for dens in (alt, base):
            d.sh(f"wm density {dens}")
            time.sleep(3.0)
    if not override:
        d.sh("wm density reset")
        time.sleep(3.0)
    pid2 = d.sh(f"pidof {PKG}").strip()
    n = live_activities(d, pid2)
    R.check("L2", f"no activity retained after {cycles * 2} recreations", n == 1 and pid2 == pid,
            f"activities={n} pid {pid}->{pid2}")
    d.key("BACK")
    got, _ = d.focus()
    R.check("L3", "Home still navigable after recreation", bool(got) and not str(got).startswith("<"), f"focus={got!r}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--apk")
    ap.add_argument("--fixture")
    ap.add_argument("--out", required=True)
    ap.add_argument("--only", default="grid,home,allapps,settings,animoff,lifecycle")
    ap.add_argument("--display", help="emulate a display, e.g. 3840x2160@640 (reset afterwards)")
    a = ap.parse_args()
    if not re.fullmatch(r"emulator-\d+", a.serial):
        sys.exit(f"refusing to run on {a.serial!r}: UI checks are emulator-only")
    if a.display:
        size, dens = a.display.split("@")
        subprocess.run(["adb", "-s", a.serial, "shell", f"wm size {size}; wm density {dens}"])
        time.sleep(2)
    d = Dev(a.serial, a.out)
    print(f"device {a.serial}: {d.w}x{d.h} @ {d.density * 160:.0f} dpi")
    if a.fixture:  # a directory: every APK below it (one package per fixture flavor)
        for root, _, files in os.walk(a.fixture):
            for fn in sorted(files):
                if fn.endswith(".apk"):
                    d.adb("install", "-r", os.path.join(root, fn))
    if a.apk:
        d.adb("uninstall", PKG)
        print(d.adb("install", "-r", a.apk).strip().splitlines()[-1:])
        d.start(6)  # first launch scans apps and writes known_apps
    # If Aura is the default Home app, Android relaunches it the instant it is force-stopped, with
    # the old preferences still in memory, which defeats seeding. Point Home at another launcher for
    # the run and restore it afterwards.
    home = d.sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").strip().splitlines()
    restore_home = None
    if home and home[-1].startswith((PKG, "android/")):  # Aura, or the chooser that can pick Aura
        cands = [l.strip() for l in d.sh("cmd package query-activities --brief -a android.intent.action.MAIN "
                                         "-c android.intent.category.HOME").splitlines() if "/" in l]
        other = next((c for c in cands if not c.startswith((PKG, "com.android.settings", "android/"))), None)
        if other:
            print(f"temporarily setting Home to {other} (was {home[-1]})")
            d.sh(f"cmd package set-home-activity {other}")
            restore_home = home[-1] if home[-1].startswith(PKG) else None
    R = Run(d)
    groups = {"grid": grid_checks, "home": home_checks, "allapps": allapps_checks, "settings": settings_checks, "animoff": anim_off_checks,
              "lifecycle": lifecycle_checks}
    for g in a.only.split(","):
        try:
            groups[g](R)
        except Exception as ex:  # keep going; a crash in one group is itself a failure
            R.check(g.upper()[:3] + "!", f"{g} group crashed", False, repr(ex))
    d.stop()
    if restore_home:
        d.sh(f"cmd package set-home-activity {restore_home}")
    if a.display:
        d.sh("wm size reset; wm density reset")
    passed = sum(1 for r in R.results if r[2])
    with open(os.path.join(a.out, "results.md"), "w") as f:
        f.write(f"# UI check: {a.serial} {d.w}x{d.h} @ {d.density * 160:.0f} dpi\n\n")
        f.write(f"{passed}/{len(R.results)} passed\n\n| ID | Check | Result | Evidence |\n|---|---|---|---|\n")
        for cid, name, ok, det in R.results:
            f.write(f"| {cid} | {name} | {'PASS' if ok else '**FAIL**'} | {det.replace('|', '/')} |\n")
    print(f"\n{passed}/{len(R.results)} passed -> {a.out}/results.md")
    sys.exit(0 if passed == len(R.results) else 1)


if __name__ == "__main__":
    main()
