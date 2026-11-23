#!/usr/bin/env python3
"""
Before/after performance comparison for Aura TV release builds. EMULATOR ONLY.

    tools/perf_compare.py --serial emulator-5554 --out /tmp/aura-perf \
        --apk before=dist/AuraTV-0.3.0.apk --apk after=dist/AuraTV-0.3.1.apk

For each APK (fresh install, default settings, so wallpaper motion is on at 30 fps):
  * cold start: `am start -W` TotalTime and Aura's own "home ready" log, 5 runs (median); only
    launches Android reports as COLD (and Aura logs as cold) count — a surviving process is retried
  * memory: total PSS and GPU texture memory, 8 s after start
  * idle: frames, janky frames, frame-time percentiles over 10 s; CPU % of one core over 10 s
  * navigation: 40 DPAD presses across the favourites row, then frame statistics
  * 4K: PSS and textures at `wm size 3840x2160` / `wm density 640` (reset afterwards)
Emulator numbers are relative (same machine, same image, same run) — not Fire TV measurements.
"""
import argparse
import os
import re
import statistics
import subprocess
import sys
import time

PKG = "com.auratv.launcher"
ACT = PKG + "/.ui.MainActivity"


class Dev:
    def __init__(self, serial):
        if not re.fullmatch(r"emulator-\d+", serial):
            sys.exit(f"refusing to run on {serial!r}: performance runs are emulator-only")
        self.serial = serial

    def sh(self, cmd, timeout=120):
        return subprocess.run(["adb", "-s", self.serial, "shell", cmd], capture_output=True, timeout=timeout).stdout.decode(errors="replace")

    def adb(self, *a):
        return subprocess.run(["adb", "-s", self.serial, *a], capture_output=True, timeout=300).stdout.decode(errors="replace")

    def stop(self):
        for _ in range(6):
            self.sh(f"am force-stop {PKG}")
            time.sleep(1.0)
            if not self.sh(f"pidof {PKG}").strip():
                return

    def pid(self):
        return self.sh(f"pidof {PKG}").strip().split(" ")[0]


def gfx(d):
    out = d.sh(f"dumpsys gfxinfo {PKG}")
    def num(pat, default=0.0):
        m = re.search(pat, out)
        return float(m.group(1)) if m else default
    tex = sum(float(x) for x in re.findall(r"Texture: ([\d.]+) MB", out))
    return {
        "frames": int(num(r"Total frames rendered: (\d+)")),
        "janky": int(num(r"Janky frames: (\d+)")),
        "p50": num(r"50th percentile: (\d+)ms"),
        "p90": num(r"90th percentile: (\d+)ms"),
        "p99": num(r"99th percentile: (\d+)ms"),
        "textures_mb": round(tex, 1),
    }


def pss_mb(d):
    m = re.search(r"TOTAL PSS:\s+(\d+)", d.sh(f"dumpsys meminfo {PKG}"))
    if not m:
        m = re.search(r"TOTAL\s+(\d+)", d.sh(f"dumpsys meminfo {PKG}"))
    return round(int(m.group(1)) / 1024, 1) if m else None


def cpu_pct(d, seconds=10):
    pid = d.pid()
    hz = 100
    def ticks():
        f = d.sh(f"cat /proc/{pid}/stat").split()
        return int(f[13]) + int(f[14])
    a = ticks(); t0 = time.time()
    time.sleep(seconds)
    b = ticks(); t1 = time.time()
    return round((b - a) / hz / (t1 - t0) * 100, 1)


def cold_start(d):
    """One launch that Android itself reports as COLD (retries if the process survived the stop)."""
    for _ in range(4):
        d.stop()
        d.sh("logcat -c")
        d.sh("input keyevent DPAD_DOWN")
        out = d.sh(f"am start -W -n {ACT}")
        state = re.search(r"LaunchState: (\w+)", out)
        m = re.search(r"TotalTime: (\d+)", out)
        time.sleep(4)
        r = re.search(r"home ready in (\d+) ms \(cold\)", d.sh("logcat -d -s Aura"))
        if m and (state is None or state.group(1) == "COLD") and r:
            return int(m.group(1)), int(r.group(1))
    return None, None


def cold_starts(d, n=5):
    totals, ready = [], []
    for _ in range(n):
        t, r = cold_start(d)
        if t is not None:
            totals.append(t)
            ready.append(r)
    return (statistics.median(totals) if totals else None, statistics.median(ready) if ready else None, totals, ready)


def measure(d, apk, label):
    print(f"== {label}: {apk}", flush=True)
    d.adb("uninstall", PKG)
    print("  install:", d.adb("install", "-r", apk).strip().splitlines()[-1:], flush=True)
    d.sh("input keyevent DPAD_DOWN")
    d.sh(f"am start -W -n {ACT}")
    time.sleep(10)  # first run: app scan, card rendering, disk caches
    res = {}
    med_total, med_ready, totals, ready = cold_starts(d)
    res["cold_start_ms"] = med_total
    res["home_ready_ms"] = med_ready
    res["cold_start_runs"] = totals
    cold_start(d)  # memory is measured from a verified fresh process
    time.sleep(6)
    res["pss_mb"] = pss_mb(d)
    d.sh(f"dumpsys gfxinfo {PKG} reset")
    time.sleep(10)
    g = gfx(d)
    res["idle"] = g
    res["textures_mb"] = g["textures_mb"]
    res["idle_cpu_pct"] = cpu_pct(d)
    d.sh(f"dumpsys gfxinfo {PKG} reset")
    for i in range(40):
        d.sh("input keyevent " + ("DPAD_RIGHT" if (i // 10) % 2 == 0 else "DPAD_LEFT"))
        time.sleep(0.15)
    time.sleep(1)
    res["nav"] = gfx(d)
    # 4K memory
    d.sh("wm size 3840x2160; wm density 640")
    time.sleep(2)
    cold_start(d)
    time.sleep(10)
    res["pss_4k_mb"] = pss_mb(d)
    res["textures_4k_mb"] = gfx(d)["textures_mb"]
    d.sh("wm size reset; wm density reset")
    time.sleep(2)
    d.stop()
    print("  ", res, flush=True)
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", required=True)
    ap.add_argument("--apk", action="append", required=True, help="label=path")
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    d = Dev(a.serial)
    os.makedirs(a.out, exist_ok=True)
    results = {}
    for spec in a.apk:
        label, path = spec.split("=", 1)
        results[label] = measure(d, path, label)
    labels = list(results)
    rows = [
        ("Cold start, `am start -W` TotalTime (median of 5)", "cold_start_ms", "ms"),
        ("Home ready (Aura log, median of 5)", "home_ready_ms", "ms"),
        ("Total PSS, 1080p", "pss_mb", "MB"),
        ("GPU textures, 1080p", "textures_mb", "MB"),
        ("Idle CPU, motion on (% of one core, 10 s)", "idle_cpu_pct", "%"),
        ("Total PSS, 4K (3840×2160 @ 640 dpi)", "pss_4k_mb", "MB"),
        ("GPU textures, 4K", "textures_4k_mb", "MB"),
    ]
    with open(os.path.join(a.out, "perf.md"), "w") as f:
        f.write(f"# Performance: {a.serial}\n\n| Metric | " + " | ".join(labels) + " |\n|---|" + "---|" * len(labels) + "\n")
        for name, key, unit in rows:
            f.write(f"| {name} | " + " | ".join(f"{results[l].get(key)} {unit}" for l in labels) + " |\n")
        for phase in ("idle", "nav"):
            for k in ("frames", "janky", "p50", "p90", "p99"):
                f.write(f"| {phase} {k} | " + " | ".join(str(results[l][phase][k]) for l in labels) + " |\n")
        f.write("\nCold start runs: " + "; ".join(f"{l}: {results[l]['cold_start_runs']}" for l in labels) + "\n")
    print(open(os.path.join(a.out, "perf.md")).read())


if __name__ == "__main__":
    main()
