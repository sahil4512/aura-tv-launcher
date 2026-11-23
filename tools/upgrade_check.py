#!/usr/bin/env python3
"""
In-place upgrade check (release builds), EMULATOR ONLY, rootable userdebug image (reads Aura's private
files). Installs --old, applies custom settings, then `adb install -r` --new over it and verifies the
app identity, preferences, BootReceiver state and Home.

    tools/upgrade_check.py --serial emulator-5556 --old dist/GlowHomeTV-0.3.2.apk --new dist/GlowHomeTV-0.3.3.apk --out /tmp/aura-upgrade
"""
import argparse
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from resource_check import Dev, PKG, PREFS, TILES, set_pref, tiles  # noqa: E402

CUSTOM = [("string", "theme", "warm"), ("int", "accent", "3"), ("int", "clock_format", "2"),
          ("int", "ui_anim", "2"), ("int", "icon_size", "2"), ("boolean", "motion_cinematic", "false")]
TRANSIENT = ("known_apps", "wp_last_change", "redirect_log", "launch_history")


def pkg_info(d):
    dump = d.sh(f"dumpsys package {PKG}")
    g = lambda pat: (re.search(pat, dump) or [None, None])[1]
    recv = "disabled" if re.search(r"disabledComponents:\s*\n(?:\s+\S+\n)*?\s+\S*BootReceiver", dump) else "enabled/default"
    return {"userId": g(r"userId=(\d+)"), "versionCode": g(r"versionCode=(\d+)"), "versionName": g(r"versionName=(\S+)"),
            "firstInstallTime": g(r"firstInstallTime=([^\n]+)"), "lastUpdateTime": g(r"lastUpdateTime=([^\n]+)"), "receiver": recv}


def lasting_prefs(xml):
    """Preference entries minus the transient keys Aura rewrites on its own."""
    return sorted(l.strip() for l in xml.splitlines() if "name=" in l and not any(f'name="{k}"' in l for k in TRANSIENT))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--old", required=True)
    ap.add_argument("--new", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    d = Dev(a.serial)
    if not d.root():
        sys.exit("adb root is required")
    old_home = d.sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").strip().splitlines()[-1]
    d.sh("cmd package set-home-activity com.android.launcher3/.Launcher")
    d.adb("uninstall", PKG)
    print("old:", d.adb("install", a.old).strip().splitlines()[-1:])
    d.start(8)
    d.stop()
    xml = d.sh(f"cat {PREFS}")
    for kind, name, val in CUSTOM:
        xml = set_pref(xml, kind, name, val)
    tmp = os.path.join(a.out, "_prefs.xml")
    with open(tmp, "w") as f:
        f.write(xml)
    d.adb("push", tmp, "/data/local/tmp/aura_prefs.xml")
    d.sh(f"cat /data/local/tmp/aura_prefs.xml > {PREFS}")
    d.start(10)  # old build runs with the custom settings (renders tiles, may rewrite prefs)
    d.adb("exec-out", "screencap", "-p")
    with open(os.path.join(a.out, "home-old.png"), "wb") as f:
        import subprocess
        f.write(subprocess.run(["adb", "-s", a.serial, "exec-out", "screencap", "-p"], capture_output=True).stdout)
    d.stop()
    before = {"pkg": pkg_info(d), "prefs": lasting_prefs(d.sh(f"cat {PREFS}")), "tiles": sorted(tiles(d))}
    # Update while Aura is running, as on a TV: Android doesn't deliver MY_PACKAGE_REPLACED to a
    # force-stopped app, so stopping it first would hide the BootReceiver reconcile.
    d.start(8)
    before["md5"] = d.sh(f"md5sum {PREFS}").split()[0]
    print("new:", d.adb("install", "-r", a.new).strip().splitlines()[-1:])
    time.sleep(12)  # MY_PACKAGE_REPLACED -> BootReceiver
    mid = {"pkg": pkg_info(d), "md5": d.sh(f"md5sum {PREFS}").split()[0]}
    d.start(12)
    import subprocess
    with open(os.path.join(a.out, "home-new.png"), "wb") as f:
        f.write(subprocess.run(["adb", "-s", a.serial, "exec-out", "screencap", "-p"], capture_output=True).stdout)
    d.stop()
    after = {"pkg": pkg_info(d), "prefs": lasting_prefs(d.sh(f"cat {PREFS}")), "tiles": sorted(tiles(d))}
    d.sh(f"cmd package set-home-activity {old_home}")

    checks = [
        ("same app user ID", before["pkg"]["userId"] == after["pkg"]["userId"], f"{before['pkg']['userId']} -> {after['pkg']['userId']}"),
        ("same first install time", before["pkg"]["firstInstallTime"] == after["pkg"]["firstInstallTime"], after["pkg"]["firstInstallTime"]),
        ("version upgraded", after["pkg"]["versionCode"] != before["pkg"]["versionCode"],
         f"{before['pkg']['versionName']} ({before['pkg']['versionCode']}) -> {after['pkg']['versionName']} ({after['pkg']['versionCode']})"),
        ("settings file untouched by the install", before["md5"] == mid["md5"], f"md5 {before['md5']} -> {mid['md5']}"),
        ("custom settings kept after first launch", all(any(f'name="{n}"' in l and (f'value="{v}"' in l or f">{v}<" in l)
                                                           for l in after["prefs"]) for _, n, v in CUSTOM), ", ".join(f"{n}={v}" for _, n, v in CUSTOM)),
        ("all lasting settings identical", before["prefs"] == after["prefs"], f"{len(before['prefs'])} entries"),
        ("BootReceiver reconciled on update (redirect off)", mid["pkg"]["receiver"] == "disabled",
         f"{before['pkg']['receiver']} -> {mid['pkg']['receiver']}"),
        ("old-format tiles replaced", not any(re.fullmatch(r"[^~]+~[0-9a-f]+\.png", n) for n in after["tiles"]),
         f"{len(before['tiles'])} files -> {len(after['tiles'])} files"),
    ]
    passed = sum(1 for c in checks if c[1])
    with open(os.path.join(a.out, "upgrade.md"), "w") as f:
        f.write(f"# Upgrade check: {a.serial}, {os.path.basename(a.old)} -> {os.path.basename(a.new)}\n\n{passed}/{len(checks)} passed\n\n"
                "| Check | Result | Evidence |\n|---|---|---|\n")
        for name, ok, det in checks:
            f.write(f"| {name} | {'PASS' if ok else '**FAIL**'} | {det} |\n")
            print(f"  {'PASS' if ok else 'FAIL'}  {name}  {det}")
    print(f"{passed}/{len(checks)} -> {a.out}/upgrade.md")
    sys.exit(0 if passed == len(checks) else 1)


if __name__ == "__main__":
    main()
