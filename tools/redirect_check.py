#!/usr/bin/env python3
"""
Home-redirect and BootReceiver regression checks. EMULATOR ONLY (serial must be emulator-NNNN).

    tools/redirect_check.py --serial emulator-5556 --apk app/build/outputs/apk/debug/app-debug.apk --out /tmp/aura-redirect

Needs a debuggable Aura build: the debug-only `redirect_test_home` preference makes the emulator's
launcher (com.android.launcher3) play the part of Amazon Home, so the redirect runs as on Fire OS.
Covers redirect enabled/disabled, BootReceiver component state, emulator reboot, app update,
redirect-process restart, and usage access granted -> revoked -> restored.
"""
import argparse
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ui_check import Dev, Run, set_pref, PKG  # noqa: E402

TEST_HOME = "com.android.launcher3"
HOME_ACT = TEST_HOME + "/.Launcher"
RECEIVER = "com.auratv.launcher.redirect.BootReceiver"
SERVICE = PKG + "/.redirect.HomeRedirectService"


def receiver_state(d):
    dump = d.sh(f"dumpsys package {PKG}")
    for kind in ("disabledComponents", "enabledComponents"):
        m = re.search(kind + r":\s*\n((?:\s+\S+\n)+)", dump)
        if m and RECEIVER in m.group(1):
            return "disabled" if kind.startswith("disabled") else "enabled"
    return "default"


def redirect_pid(d):
    return d.sh(f"pidof {PKG}:redirect").strip()


def service_running(d):
    return "ServiceRecord" in d.sh(f"dumpsys activity services {SERVICE}")


def resumed(d):
    out = d.sh("dumpsys activity activities")
    m = re.search(r"(?:mResumedActivity|ResumedActivity)[:=].*? (\S+/\S+)", out)
    return m.group(1) if m else ""


def configure(d, enabled):
    d.stop()
    xml = d.prefs()
    xml = set_pref(xml, "boolean", "redirect_enabled", "true" if enabled else "false")
    xml = set_pref(xml, "string", "redirect_test_home", TEST_HOME)
    xml = set_pref(xml, "boolean", "motion_cinematic", "false")
    d.write_prefs(xml)
    d.start(6)  # MainActivity.onCreate -> HomeRedirect.apply


def press_home_and_wait(d, wait=9.0):
    d.sh("input keyevent HOME")
    time.sleep(wait)  # Android defers background activity starts ~5 s after Home
    return resumed(d)


OTHER_APP = "com.android.calculator2"


def open_app(d, pkg, wait=3.0):
    d.sh(f"monkey -p {pkg} -c android.intent.category.LAUNCHER 1")
    time.sleep(wait)


def redirect_count(d):
    """Redirect requests and arrivals logged since the last `logcat -c`."""
    log = d.sh("logcat -d -s AuraRedirect:I Aura:I")
    req = re.findall(r"redirect \((\S+)\): requested (-?\d+) ms after", log)
    arr = re.findall(r"redirect arrived: total (\d+) ms \(detect (-?\d+) ms", log)
    return req, arr


def timing(d):
    req, arr = redirect_count(d)
    if not arr:
        return f"requests={len(req)} arrivals=0"
    total, detect = arr[-1]
    return f"requests={len(req)} arrivals={len(arr)} detect={detect} ms, Aura visible {total} ms after the launcher appeared"


def redirect_procs(d):
    return [l for l in d.sh("ps").splitlines() if l.rstrip().endswith(PKG + ":redirect")]


def walk_to(d, text, key="DPAD_DOWN", tries=12):
    for _ in range(tries):
        got, _ = d.focus()
        if got and text in got:
            return True
        d.key(key, wait=0.5)
    got, _ = d.focus()
    return bool(got and text in got)


def scenario_checks(d, R):
    """Home-button scenarios from the v0.4.0 Fire TV plan, with timings from Aura's own logs."""
    print("== Home-button scenarios")
    d.start(4)
    d.sh("logcat -c")
    top = press_home_and_wait(d)
    R.check("H1", "Home pressed while Aura is in front -> Aura again", top.startswith(PKG), f"resumed={top}; {timing(d)}")

    open_app(d, OTHER_APP)
    d.sh("logcat -c")
    top = press_home_and_wait(d)
    R.check("H2", "Home from another app -> Aura", top.startswith(PKG), f"resumed={top}; {timing(d)}")

    open_app(d, OTHER_APP)
    d.sh("logcat -c")
    d.sh("input keyevent HOME HOME HOME")  # rapid presses in one shell call
    time.sleep(15)
    first = resumed(d)
    time.sleep(10)
    second = resumed(d)
    req, arr = redirect_count(d)
    R.check("H3", "rapid Home x3: lands on Aura, stays put, no repeated redirects",
            first.startswith(PKG) and second == first and len(req) <= 2,
            f"t+15s={first} t+25s={second}; {timing(d)}")

    # Impatient user: Home again 1.5 s later, while the system still holds our start. Android then shows
    # Aura and puts the launcher back on top a few ms later; that must not count as a double press.
    open_app(d, OTHER_APP)
    d.sh("logcat -c")
    d.sh("input keyevent HOME")
    time.sleep(1.5)
    d.sh("input keyevent HOME")
    time.sleep(14)
    first = resumed(d)
    time.sleep(6)
    second = resumed(d)
    req, arr = redirect_count(d)
    R.check("H3b", "Home again during the 5 s hold: still ends on Aura, stays put",
            first.startswith(PKG) and second == first and len(req) <= 3,
            f"t+15s={first} t+21s={second}; {timing(d)}")

    # Double-press escape: Home again within 3 s of Aura appearing keeps the launcher ("Amazon Home").
    open_app(d, OTHER_APP)
    d.sh("logcat -c")
    d.sh("input keyevent HOME")
    for _ in range(40):
        time.sleep(0.25)
        if redirect_count(d)[1]:
            break
    d.sh("input keyevent HOME")
    time.sleep(10)
    stay = resumed(d)
    open_app(d, OTHER_APP)
    d.sh("logcat -c")
    back = press_home_and_wait(d)
    R.check("H4", "double-press Home keeps Amazon Home; the next Home from an app redirects again",
            stay.startswith(TEST_HOME) and back.startswith(PKG), f"after double press={stay}; next Home={back}")

    # Settings -> Home Button -> Open Amazon Home: allowed once, then the window expires.
    d.start(4)
    d.key("BACK")
    d.key("DPAD_UP", wait=0.5)
    d.key("DPAD_CENTER", wait=1.5)
    ok = walk_to(d, "System")
    d.key("DPAD_RIGHT", wait=0.8)
    ok = ok and walk_to(d, "Home Button")
    d.key("DPAD_CENTER", wait=1.5)
    ok = ok and walk_to(d, "Open Amazon Home", tries=14)
    d.sh("logcat -c")
    d.key("DPAD_CENTER", wait=1.0)
    time.sleep(12)
    stayed = resumed(d)
    req, _ = redirect_count(d)
    R.check("H5", "Settings > Open Amazon Home: Amazon Home shown and not redirected (12 s)",
            ok and stayed.startswith(TEST_HOME) and not req, f"menu reached={ok} resumed={stayed} redirects={len(req)}")
    time.sleep(5)  # past the 10 s allow window
    open_app(d, OTHER_APP)
    d.sh("logcat -c")
    top = press_home_and_wait(d)
    R.check("H6", "after Open Amazon Home, the suppression expires: next Home redirects", top.startswith(PKG),
            f"resumed={top}; {timing(d)}")

    # Aura's UI process killed (low memory) while another app is in front; :redirect survives.
    open_app(d, OTHER_APP)
    ui_pid = d.sh(f"pidof {PKG}").strip()
    rpid = redirect_pid(d)
    if ui_pid:
        d.sh(f"run-as {PKG} kill -9 {ui_pid}")
    time.sleep(2)
    d.sh("logcat -c")
    top = press_home_and_wait(d)
    cold = "(cold)" in d.sh("logcat -d -s Aura:I")
    R.check("H7", "UI process killed: Home still brings Aura back (cold start)",
            top.startswith(PKG) and redirect_pid(d) == rpid and cold,
            f"ui pid {ui_pid} killed; redirect pid {rpid}->{redirect_pid(d)}; cold={cold}; resumed={top}; {timing(d)}")

    procs = redirect_procs(d)
    R.check("H8", "exactly one :redirect process", len(procs) == 1, f"{len(procs)} process(es)")


def wait_boot(d):
    d.adb("wait-for-device", timeout=300)
    for _ in range(120):
        if d.sh("getprop sys.boot_completed").strip() == "1":
            break
        time.sleep(2)
    time.sleep(25)  # let BOOT_COMPLETED receivers run


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--out", required=True)
    ap.add_argument("--no-reboot", action="store_true")
    a = ap.parse_args()
    d = Dev(a.serial, a.out)
    R = Run(d)
    d.adb("uninstall", PKG)
    print(d.adb("install", "-r", a.apk).strip().splitlines()[-1:])
    d.sh(f"appops set {PKG} GET_USAGE_STATS allow")
    old_home = d.sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME").strip().splitlines()[-1]
    d.sh(f"cmd package set-home-activity {HOME_ACT}")
    d.start(6)  # first launch writes prefs

    print("== Redirect enabled")
    configure(d, True)
    R.check("B1", "enabled: BootReceiver on", receiver_state(d) in ("enabled", "default"), receiver_state(d))
    R.check("R1", "enabled: service running in :redirect", service_running(d) and redirect_pid(d), f"pid={redirect_pid(d)}")
    top = press_home_and_wait(d)
    R.check("R2", "enabled: Home -> launcher -> Aura", top.startswith(PKG), f"resumed={top}")
    scenario_checks(d, R)

    print("== Redirect process restart")
    pid = redirect_pid(d)
    d.sh(f"run-as {PKG} kill -9 {pid}")
    for _ in range(30):
        time.sleep(2)
        if redirect_pid(d) and redirect_pid(d) != pid and service_running(d):
            break
    R.check("P1", "sticky service restarts after its process dies", redirect_pid(d) not in ("", pid) and service_running(d),
            f"pid {pid}->{redirect_pid(d)}")
    time.sleep(3)
    top = press_home_and_wait(d)
    R.check("P2", "redirect works after restart (fresh usage-access cache)", top.startswith(PKG), f"resumed={top}")

    print("== Usage access revoked / restored")
    d.sh(f"appops set {PKG} GET_USAGE_STATS ignore")
    time.sleep(65)  # past the 60 s grant cache: the service must notice the revocation
    top = press_home_and_wait(d)
    R.check("U1", "revoked: no redirect", top.startswith(TEST_HOME), f"resumed={top}")
    d.sh(f"appops set {PKG} GET_USAGE_STATS allow")
    time.sleep(12)  # next 10 s wait-for-grant retry re-checks
    top = press_home_and_wait(d)
    R.check("U2", "restored: redirect resumes", top.startswith(PKG), f"resumed={top}")

    print("== App update (redirect enabled)")
    print(d.adb("install", "-r", a.apk).strip().splitlines()[-1:])
    time.sleep(10)
    R.check("A1", "update keeps BootReceiver on and restarts the service", receiver_state(d) in ("enabled", "default")
            and service_running(d), f"receiver={receiver_state(d)} pid={redirect_pid(d)}")

    if not a.no_reboot:
        print("== Reboot (redirect enabled)")
        d.adb("reboot")
        wait_boot(d)
        R.check("BT1", "after reboot: service started by BootReceiver", service_running(d) and redirect_pid(d),
                f"pid={redirect_pid(d)} resumed={resumed(d)}")

    print("== Redirect disabled")
    configure(d, False)
    R.check("B2", "disabled: BootReceiver off", receiver_state(d) == "disabled", receiver_state(d))
    R.check("R3", "disabled: no service, no :redirect process", not service_running(d) and not redirect_pid(d),
            f"pid={redirect_pid(d)!r}")
    top = press_home_and_wait(d)
    R.check("R4", "disabled: Home stays on the launcher", top.startswith(TEST_HOME), f"resumed={top}")

    print("== App update (redirect disabled)")
    print(d.adb("install", "-r", a.apk).strip().splitlines()[-1:])
    time.sleep(10)
    R.check("A2", "update keeps BootReceiver off; no :redirect process", receiver_state(d) == "disabled" and not redirect_pid(d),
            f"receiver={receiver_state(d)} pid={redirect_pid(d)!r}")

    if not a.no_reboot:
        print("== Reboot (redirect disabled)")
        d.adb("reboot")
        wait_boot(d)
        R.check("BT2", "after reboot: BootReceiver off, no :redirect process", receiver_state(d) == "disabled" and not redirect_pid(d),
                f"receiver={receiver_state(d)} pid={redirect_pid(d)!r}")

    print("== Re-enable")
    configure(d, True)
    R.check("B3", "re-enabled: BootReceiver back on, service running", receiver_state(d) == "enabled" and service_running(d),
            f"receiver={receiver_state(d)} pid={redirect_pid(d)}")
    configure(d, False)  # leave the emulator with the redirect off

    d.stop()
    d.sh(f"cmd package set-home-activity {old_home}")
    passed = sum(1 for r in R.results if r[2])
    with open(os.path.join(a.out, "results.md"), "w") as f:
        f.write(f"# Redirect / BootReceiver check: {a.serial}\n\n{passed}/{len(R.results)} passed\n\n"
                "| ID | Check | Result | Evidence |\n|---|---|---|---|\n")
        for cid, name, ok, det in R.results:
            f.write(f"| {cid} | {name} | {'PASS' if ok else '**FAIL**'} | {str(det).replace('|', '/')} |\n")
    print(f"\n{passed}/{len(R.results)} passed -> {a.out}/results.md")
    sys.exit(0 if passed == len(R.results) else 1)


if __name__ == "__main__":
    main()
