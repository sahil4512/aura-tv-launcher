#!/usr/bin/env python3
"""
Resource measurements for the 0.3.3 fixes, release builds, EMULATOR ONLY (rootable userdebug image,
e.g. the API 25 `aura_api25` AVD, for reading Aura's private tile cache).

    tools/resource_check.py --serial emulator-5556 --fixture testfixture/build/outputs/apk \
        --apk 0.3.2=dist/GlowHomeTV-0.3.2.apk --apk 0.3.3=dist/GlowHomeTV-0.3.3.apk --out /tmp/aura-res [--reboot]

Per APK (fresh install, Home pointed at the emulator launcher):
  * tiles: favourites + recents seeded so 4 apps appear in both rows; tile files rewritten per cold
    start (each rewrite = a render from PackageManager), file count and bytes
  * orphans: an app uninstalled while Aura isn't running; are its tile files left behind?
  * boot (--reboot): redirect off, Aura launched once, emulator rebooted; is a ":redirect" process
    spawned at boot, and how much memory does it hold?
"""
import argparse
import os
import re
import subprocess
import sys
import time

PKG = "com.auratv.launcher"
FIX = "com.auratv.testfixture"
TILES = f"/data/data/{PKG}/cache/tiles"
PREFS = f"/data/data/{PKG}/shared_prefs/aura_prefs.xml"


class Dev:
    def __init__(self, serial):
        if not re.fullmatch(r"emulator-\d+", serial):
            sys.exit(f"refusing to run on {serial!r}: emulator-only")
        self.serial = serial

    def adb(self, *a, timeout=300):
        return subprocess.run(["adb", "-s", self.serial, *a], capture_output=True, timeout=timeout).stdout.decode(errors="replace")

    def sh(self, cmd):
        return self.adb("shell", cmd)

    def root(self):
        self.adb("root")
        time.sleep(3)
        self.adb("wait-for-device")
        return self.sh("id -u").strip() == "0"

    def stop(self):
        for _ in range(6):
            self.sh(f"am force-stop {PKG}")
            time.sleep(1.0)
            if not self.sh(f"pidof {PKG}").strip():
                return

    def start(self, settle):
        self.sh("input keyevent DPAD_DOWN")
        self.sh(f"am start -n {PKG}/.ui.MainActivity")
        time.sleep(settle)

    def wait_boot(self):
        self.adb("wait-for-device")
        for _ in range(150):
            if self.sh("getprop sys.boot_completed").strip() == "1":
                break
            time.sleep(2)
        time.sleep(30)


def tiles(d):
    """{name: (mtime, bytes)} of Aura's tile disk cache."""
    out = {}
    for line in d.sh(f"stat -c '%n %Y %s' {TILES}/* 2>/dev/null").splitlines():
        p = line.rsplit(" ", 2)
        if len(p) == 3 and p[0].startswith(TILES):
            out[os.path.basename(p[0])] = (int(p[1]), int(p[2]))
    return out


def set_pref(xml, kind, name, value):
    xml = re.sub(rf'\s*<{kind} name="{re.escape(name)}"[^>]*?(?:/>|>.*?</{kind}>)', "", xml, flags=re.S)
    el = f'<{kind} name="{name}">{value}</{kind}>' if kind == "string" else f'<{kind} name="{name}" value="{value}" />'
    return xml.replace("</map>", f"    {el}\n</map>")


def seed(d, out):
    d.stop()
    xml = d.sh(f"cat {PREFS}")
    known = re.search(r'name="known_apps">(.*?)</string>', xml, re.S)
    keys = [k for k in (known.group(1).split("&#10;") if known else []) if k.startswith(FIX)]
    favs = keys[:8]
    hist = [k.split("/")[0] for k in favs[:4]]
    for kind, name, val in [("string", "favorites", "&#10;".join(favs)), ("boolean", "favorites_seeded", "true"),
                            ("boolean", "recents_row", "true"), ("string", "launch_history", "&#10;".join(hist)),
                            ("boolean", "motion_cinematic", "false"), ("int", "icon_size", "1")]:
        xml = set_pref(xml, kind, name, val)
    tmp = os.path.join(out, "_prefs.xml")
    with open(tmp, "w") as f:
        f.write(xml)
    d.adb("push", tmp, "/data/local/tmp/aura_prefs.xml")
    d.sh(f"cat /data/local/tmp/aura_prefs.xml > {PREFS}")  # keeps the file's owner
    return favs, hist


def measure(d, label, apk, fixture_dir, out, cold_starts=3):
    print(f"== {label}: {apk}")
    d.adb("uninstall", PKG)
    print("  install:", d.adb("install", "-r", apk).strip().splitlines()[-1:])
    d.start(8)  # first launch: app scan writes known_apps
    favs, hist = seed(d, out)
    d.start(15)  # first render of every tile
    d.stop()
    before = tiles(d)
    rewrites = []
    for _ in range(cold_starts):
        time.sleep(1.2)  # mtime has 1 s resolution
        snap = tiles(d)
        d.start(12)
        d.stop()
        after = tiles(d)
        rewrites.append(sum(1 for n, (m, _) in after.items() if n not in snap or snap[n][0] != m))
    final = tiles(d)
    both_rows = len(hist)
    # Orphans: uninstall a fixture app while Aura is stopped, then cold start Aura.
    victim = favs[-1].split("/")[0]
    had = sum(1 for n in final if n.startswith(victim + "~"))
    d.adb("uninstall", victim)
    d.start(12)
    d.stop()
    left = sum(1 for n in tiles(d) if n.startswith(victim + "~"))
    for root, _, files in os.walk(fixture_dir):  # reinstall it for the next build
        for fn in files:
            if fn.endswith(".apk") and victim.rsplit(".", 1)[-1] in fn:
                d.adb("install", "-r", os.path.join(root, fn))
    r = {"label": label, "apps_in_both_rows": both_rows, "rewrites_per_cold_start": rewrites,
         "files": len(final), "bytes": sum(b for _, b in final.values()),
         "files_after_first_render": len(before), "orphan_files_before": had, "orphan_files_left": left}
    print("  ", r)
    return r


def boot(d, label, apk):
    print(f"== boot {label}")
    d.adb("uninstall", PKG)
    d.adb("install", "-r", apk)
    d.start(8)
    d.stop()
    recv = "disabled" if "BootReceiver" in (re.search(r"disabledComponents:\s*\n((?:\s+\S+\n)+)",
                                                     d.sh(f"dumpsys package {PKG}")) or [None, ""])[1] else "enabled/default"
    d.adb("reboot")
    d.wait_boot()
    pid = d.sh(f"pidof {PKG}:redirect").strip()
    pss = None
    if pid:
        m = re.search(r"TOTAL\s+(\d+)", d.sh(f"dumpsys meminfo {pid}"))
        pss = int(m.group(1)) / 1024 if m else None
    r = {"label": label, "receiver_before_reboot": recv, "redirect_pid_after_boot": pid or None,
         "redirect_pss_mb": round(pss, 1) if pss else None, "main_pid_after_boot": d.sh(f"pidof {PKG}").strip() or None}
    print("  ", r)
    return r


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--apk", action="append", required=True, help="label=path")
    ap.add_argument("--fixture", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--reboot", action="store_true")
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    d = Dev(a.serial)
    if not d.root():
        sys.exit("adb root is required (userdebug emulator image)")
    old_home = d.sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").strip().splitlines()[-1]
    d.sh("cmd package set-home-activity com.android.launcher3/.Launcher")
    apks = [x.split("=", 1) for x in a.apk]
    res = [measure(d, l, p, a.fixture, a.out) for l, p in apks]
    boots = []
    if a.reboot:
        for l, p in apks:
            boots.append(boot(d, l, p))
        d.root()
    d.sh(f"cmd package set-home-activity {old_home}")
    with open(os.path.join(a.out, "resources.md"), "w") as f:
        f.write(f"# Resource check: {a.serial} (API {d.sh('getprop ro.build.version.sdk').strip()})\n\n")
        f.write("| Build | Apps in both rows | Tile files rewritten per cold start | Tile files | Tile bytes | Orphan tiles left after uninstall-while-stopped |\n|---|---|---|---|---|---|\n")
        for r in res:
            f.write(f"| {r['label']} | {r['apps_in_both_rows']} | {r['rewrites_per_cold_start']} | {r['files']} | {r['bytes']} | "
                    f"{r['orphan_files_left']} of {r['orphan_files_before']} |\n")
        if boots:
            f.write("\n| Build | BootReceiver before reboot | :redirect process after boot | its PSS (MB) |\n|---|---|---|---|\n")
            for b in boots:
                f.write(f"| {b['label']} | {b['receiver_before_reboot']} | {b['redirect_pid_after_boot'] or 'none'} | {b['redirect_pss_mb'] or '-'} |\n")
    print(f"-> {a.out}/resources.md")


if __name__ == "__main__":
    main()
