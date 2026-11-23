#!/usr/bin/env python3
"""
Live "task manager" for an Android TV over adb, viewed in a browser on this Mac.

    tools/tv_monitor.py <tv-ip>:5555        # then open http://localhost:8765

Read-only. Built to stay light on slow TV chips: Android's `top` costs ~30 % of a 900 MHz core,
so this reads the kernel's counters instead, in ONE small shell command per tick:
/proc/stat (CPU), /proc/meminfo (memory), /proc/[pid]/stat (per-process CPU + resident memory).
Process names come from `ps` every 30 s; the app on screen and Wi-Fi every 10 s.
"""
import http.server
import json
import re
import subprocess
import sys
import threading
import time

if len(sys.argv) < 2:
    sys.exit("usage: tools/tv_monitor.py <tv-ip>:5555 [port]")
DEV = sys.argv[1]
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8765
TICK = 3.0
PAGE_KB = 4

state = {"ok": False, "error": "starting…", "history": [], "names": {}}
lock = threading.Lock()


def sh(cmd, timeout=15):
    r = subprocess.run(["adb", "-s", DEV, "shell", cmd], capture_output=True, timeout=timeout)
    err = r.stderr.decode(errors="replace")
    if r.returncode != 0 and ("offline" in err or "not found" in err or "no devices" in err or "closed" in err):
        raise ConnectionError(err.strip().splitlines()[-1] if err.strip() else "adb failed")
    return r.stdout.decode(errors="replace").replace("\r", "")


def parse_cpu_lines(text):
    cpus = {}
    for line in text.splitlines():
        if line.startswith("cpu"):
            f = line.split()
            vals = list(map(int, f[1:8]))
            cpus[f[0]] = (sum(vals), vals[3] + vals[4])  # total, idle+iowait
    return cpus


def parse_proc_stats(text):
    procs = {}
    for line in text.splitlines():
        m = re.match(r"(\d+) \((.*)\) \S+ (.*)", line)
        if not m:
            continue
        rest = m.group(3).split()
        try:
            utime, stime, rss = int(rest[10]), int(rest[11]), int(rest[20])
        except (IndexError, ValueError):
            continue
        procs[int(m.group(1))] = (m.group(2), utime + stime, rss * PAGE_KB)
    return procs


def refresh_names():
    names = {}
    for line in sh("ps -A -o PID,NAME").splitlines()[1:]:
        p = line.split(None, 1)
        if len(p) == 2 and p[0].isdigit():
            names[int(p[0])] = p[1].strip()
    return names


def side_info():
    front = re.search(r"mCurrentFocus=Window\{\S+ \S+ ([^/} ]+)", sh("dumpsys window | grep -m1 mCurrentFocus"))
    wifi = sh("dumpsys wifi | grep -m1 'mWifiInfo SSID'")
    ssid = re.search(r"SSID: ([^,]+)", wifi)
    rssi = re.search(r"RSSI: (-?\d+)", wifi)
    freq = re.search(r"Frequency: (\d+)", wifi)
    speed = re.search(r"Link speed: (\d+)", wifi)
    return {
        "front": front.group(1) if front else "—",
        "wifi": {"ssid": ssid.group(1) if ssid else "—", "rssi": int(rssi.group(1)) if rssi else None,
                 "ghz": (round(int(freq.group(1)) / 1000, 1) if freq else None), "mbps": int(speed.group(1)) if speed else None},
    }


def poller():
    prev_cpu, prev_procs, t_names, t_side = None, None, 0, 0
    names, side = {}, {"front": "—", "wifi": {}}
    model = ""
    while True:
        try:
            if not model:
                model = sh("getprop ro.product.model").strip()
            now = time.time()
            if now - t_names > 30:
                names, t_names = refresh_names(), now
            if now - t_side > 10:
                side, t_side = side_info(), now
            out = sh("cat /proc/stat; echo @@; cat /proc/meminfo; echo @@; cat /proc/[0-9]*/stat 2>/dev/null; echo @@; cat /proc/loadavg /proc/uptime")
            stat_txt, mem_txt, procs_txt, misc = (out.split("@@\n") + ["", "", "", ""])[:4]
            cpu = parse_cpu_lines(stat_txt)
            procs = parse_proc_stats(procs_txt)
            mem = {k: int(v) for k, v in re.findall(r"^(\w+):\s+(\d+) kB", mem_txt, re.M)}
            snap = None
            if prev_cpu and "cpu" in cpu:
                dt, di = cpu["cpu"][0] - prev_cpu["cpu"][0], cpu["cpu"][1] - prev_cpu["cpu"][1]
                ncores = len([k for k in cpu if k != "cpu"]) or 1
                total_pct = round(100 * (dt - di) / dt, 1) if dt else 0
                cores = []
                for k in sorted(k for k in cpu if k != "cpu"):
                    if k in prev_cpu:
                        a, b = cpu[k][0] - prev_cpu[k][0], cpu[k][1] - prev_cpu[k][1]
                        cores.append(round(100 * (a - b) / a, 1) if a else 0)
                per_core_jiffies = dt / ncores if ncores else dt
                rows = []
                for pid, (comm, ticks, rss) in procs.items():
                    if pid in prev_procs:
                        d = ticks - prev_procs[pid][1]
                        pct = 100 * d / per_core_jiffies if per_core_jiffies else 0
                    else:
                        pct = 0
                    rows.append({"pid": pid, "name": names.get(pid, comm), "cpu": round(pct, 1), "rss": round(rss / 1024, 1)})
                load = misc.split()
                snap = {
                    "ok": True, "model": model, "time": time.strftime("%H:%M:%S"),
                    "cpu": total_pct, "cores": cores,
                    "mem": {"total": mem.get("MemTotal", 0) // 1024, "avail": mem.get("MemAvailable", 0) // 1024,
                            "swap_total": mem.get("SwapTotal", 0) // 1024, "swap_free": mem.get("SwapFree", 0) // 1024},
                    "load": load[:3], "uptime_min": int(float(load[5]) // 60) if len(load) > 5 else None,
                    "top_cpu": sorted(rows, key=lambda r: -r["cpu"])[:10],
                    "top_mem": sorted(rows, key=lambda r: -r["rss"])[:10],
                    "procs": len(procs), **side,
                }
            prev_cpu, prev_procs = cpu, procs
            with lock:
                if snap:
                    hist = state.get("history", [])[-79:] + [snap["cpu"]]
                    state.clear(); state.update(snap); state["history"] = hist
        except Exception as e:  # TV asleep / Wi-Fi dropped: keep trying
            with lock:
                state["ok"] = False
                state["error"] = f"{type(e).__name__}: {e}"
            subprocess.run(["adb", "disconnect", DEV], capture_output=True, timeout=10)
            subprocess.run(["adb", "connect", DEV], capture_output=True, timeout=20)
            prev_cpu, prev_procs, t_names, t_side = None, None, 0, 0
        time.sleep(TICK)


PAGE = r"""<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>TV task manager</title><style>
:root{--bg:#f6f6f3;--card:#fff;--ink:#141413;--ink2:#5e5d59;--mute:#8b8a85;--line:#e4e3dd;--blue:#2a78d6;--red:#d64545;--gray:#9a998f}
@media (prefers-color-scheme:dark){:root{--bg:#141413;--card:#1e1e1c;--ink:#f0efec;--ink2:#c3c2b7;--mute:#8b8a85;--line:#2e2e2b;--gray:#6d6c66}}
body{margin:0;background:var(--bg);color:var(--ink);font:14px/1.5 -apple-system,system-ui,sans-serif;padding:20px}
h1{font-size:20px;font-weight:600;margin:0}.sub{color:var(--mute);font-size:13px}
.g{display:grid;grid-template-columns:repeat(auto-fit,minmax(170px,1fr));gap:12px;margin:16px 0}
.c{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:12px 14px}
.l{color:var(--ink2);font-size:12px}.v{font-size:24px;font-weight:600;font-variant-numeric:tabular-nums}.s{color:var(--mute);font-size:12px}
.t{height:6px;background:var(--line);border-radius:3px;margin-top:6px;overflow:hidden}.t i{display:block;height:100%;background:var(--blue);border-radius:3px;transition:width .4s}
.cols{display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));gap:16px}
.r{display:grid;grid-template-columns:minmax(0,1.4fr) minmax(0,1fr) 58px;gap:8px;align-items:center;font-size:13px;margin:4px 0}
.r .n{white-space:nowrap;overflow:hidden;text-overflow:ellipsis;color:var(--ink2)}.r .b{height:10px;border-radius:0 3px 3px 0;background:var(--gray);transition:width .4s}.r .x{text-align:right;font-variant-numeric:tabular-nums}
.r.f .b{background:var(--blue)}.r.f .n{color:var(--ink);font-weight:600}
canvas{width:100%;height:90px;display:block}.err{color:var(--red)}
.cores{display:flex;gap:6px;margin-top:6px}.cores span{flex:1;font-size:11px;color:var(--mute);text-align:center}
</style></head><body>
<h1>TV task manager <span id="model" class="sub"></span></h1><div class="sub"><span id="status">connecting…</span></div>
<div class="g">
 <div class="c"><div class="l">CPU</div><div class="v" id="cpu">–</div><div class="t"><i id="cpub"></i></div><div class="cores" id="cores"></div></div>
 <div class="c"><div class="l">Memory used</div><div class="v" id="mem">–</div><div class="s" id="mems"></div><div class="t"><i id="memb"></i></div></div>
 <div class="c"><div class="l">Swap (zram)</div><div class="v" id="swap">–</div><div class="s" id="swaps"></div><div class="t"><i id="swapb"></i></div></div>
 <div class="c"><div class="l">On screen</div><div class="v" id="front" style="font-size:17px">–</div><div class="s" id="up"></div></div>
 <div class="c"><div class="l">Wi-Fi</div><div class="v" id="wifi" style="font-size:17px">–</div><div class="s" id="wifis"></div></div>
</div>
<div class="c" style="margin-bottom:16px"><div class="l">CPU, last 4 minutes</div><canvas id="hist"></canvas></div>
<div class="cols">
 <div class="c"><div class="l" style="margin-bottom:6px">CPU by process <span class="s">(% of one core; 400% = all four)</span></div><div id="tcpu"></div></div>
 <div class="c"><div class="l" style="margin-bottom:6px">Memory by process <span class="s">(MB resident)</span></div><div id="tmem"></div></div>
</div>
<script>
const $=id=>document.getElementById(id);
function rows(el,list,key,unit,max,front){el.innerHTML=list.map(r=>{const w=Math.max(1,Math.min(100,100*r[key]/max));const f=front&&r.name.startsWith(front)?' f':'';
return `<div class="r${f}"><span class="n" title="${r.name} (pid ${r.pid})">${r.name}</span><div class="b" style="width:${w}%"></div><span class="x">${r[key].toFixed(r[key]<10?1:0)}${unit}</span></div>`}).join('')}
function draw(h){const c=$('hist'),d=devicePixelRatio||1,w=c.clientWidth,ht=c.clientHeight;c.width=w*d;c.height=ht*d;const x=c.getContext('2d');x.scale(d,d);
x.strokeStyle=getComputedStyle(document.body).getPropertyValue('--line');x.lineWidth=1;[25,50,75].forEach(p=>{const y=ht-ht*p/100;x.beginPath();x.moveTo(0,y);x.lineTo(w,y);x.stroke()});
if(h.length<2)return;x.beginPath();h.forEach((v,i)=>{const px=w*i/79,py=ht-ht*Math.min(v,100)/100;i?x.lineTo(px,py):x.moveTo(px,py)});x.strokeStyle='#2a78d6';x.lineWidth=2;x.lineJoin='round';x.stroke();
x.lineTo(w*(h.length-1)/79,ht);x.lineTo(0,ht);x.fillStyle='rgba(42,120,214,.1)';x.fill()}
async function tick(){try{const s=await (await fetch('/api')).json();
if(!s.ok){$('status').innerHTML='<span class="err">TV not reachable ('+(s.error||'')+') — is it on? retrying…</span>';return}
$('model').textContent=s.model;$('status').textContent='live · updated '+s.time+' · every 3 s · '+s.procs+' processes';
$('cpu').textContent=s.cpu.toFixed(0)+'%';$('cpub').style.width=s.cpu+'%';$('cores').innerHTML=s.cores.map((v,i)=>`<span>core ${i+1}<br>${v.toFixed(0)}%</span>`).join('');
const used=s.mem.total-s.mem.avail;$('mem').textContent=used+' MB';$('mems').textContent=`of ${s.mem.total} MB · ${s.mem.avail} MB free`;$('memb').style.width=(100*used/s.mem.total)+'%';
const su=s.mem.swap_total-s.mem.swap_free;$('swap').textContent=su+' MB';$('swaps').textContent=`of ${s.mem.swap_total} MB`;$('swapb').style.width=(100*su/(s.mem.swap_total||1))+'%';
$('front').textContent=s.front;$('up').textContent=s.uptime_min!=null?`TV up ${Math.floor(s.uptime_min/60)} h ${s.uptime_min%60} min`:'';
$('wifi').textContent=s.wifi.ssid||'–';$('wifis').textContent=[s.wifi.rssi!=null?s.wifi.rssi+' dBm':'',s.wifi.mbps?s.wifi.mbps+' Mbps':'',s.wifi.ghz?s.wifi.ghz+' GHz':''].filter(Boolean).join(' · ');
const mc=Math.max(100,...s.top_cpu.map(r=>r.cpu));rows($('tcpu'),s.top_cpu,'cpu','%',mc,s.front);rows($('tmem'),s.top_mem,'rss','',Math.max(...s.top_mem.map(r=>r.rss)),s.front);draw(s.history)}
catch(e){$('status').innerHTML='<span class="err">monitor stopped on the Mac</span>'}}
tick();setInterval(tick,3000);
</script></body></html>"""


class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path.startswith("/api"):
            with lock:
                body = json.dumps(state).encode()
            ctype = "application/json"
        else:
            body, ctype = PAGE.encode(), "text/html; charset=utf-8"
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    try:
        server = http.server.ThreadingHTTPServer(("127.0.0.1", PORT), H)
    except OSError:
        print(f"The TV task manager is already running: open http://localhost:{PORT}")
        sys.exit(0)
    subprocess.run(["adb", "connect", DEV], capture_output=True, timeout=20)
    threading.Thread(target=poller, daemon=True).start()
    print(f"TV task manager for {DEV}: http://localhost:{PORT}")
    server.serve_forever()
