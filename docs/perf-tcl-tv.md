# Aura on the TCL Android TV: before and after (2026-11-08)

TV: TCL "UnionTV", Android TV 9 (API 28), Realtek RTD284x (4 × 900 MHz), OpenGL ES 2.0, 1.4 GB in Android low-RAM mode, 1280×720 @ 213 dpi. Measured over adb on the real TV while idle, with `tools`-style scripts (cold launch = Aura force-stopped with TV Settings in front, then `am start`).

| | 0.5.1 (moving wallpaper) | 0.6.2 (solid) | **0.6.3 (solid, precompiled)** |
|---|---|---|---|
| Aura's own launch to first frame (cold, Android's `am_activity_launch_time`) | 587–802 ms | 292–376 ms | **271–286 ms** |
| Warm return to Home | 350 ms | 182 ms | **179 ms** |
| CPU while idle on Home | ~26 % of a core (31 fps redraw) | 0 % | **0 %** |
| Memory (PSS) | 38.2 MB | 28.7 MB | **26.5 MB** |
| GPU textures | 8.9 MB | 1.8 MB | **1.8 MB** |
| Navigation (14 presses): janky frames | 9.6 % | 2.2 % | **1.8 %** |
| Navigation 99th percentile | 20 ms | 17 ms | 19 ms |

The "total" launch time Android reports (about 1.1–1.3 s, and 2.7–3.0 s in the first 0.5.1 runs) also includes TV Settings pausing and its exit animation, so it isn't Aura's own cost.

What made the difference:
- A moving wallpaper redrew the whole screen 31 times a second, even with nobody watching. Solid background is now the default and the motion is gone.
- Precompiling all of Aura's code (`cmd package compile -m speed -f com.auratv.launcher`) cut its own launch by about 20 % on this chip. Android resets this on every update, so `tools/aura-setup` redoes it after each install.

0.6.3 start-up checkpoints (ms since the process started, a launch where the process already existed): screen created → views +55 → content bound +85 → first layout +160 → first frame drawn +210, i.e. about 0.5 s from screen creation to the first frame.

## Kids mode on the TCL (2026-11-25)

We compared two 0.8.0 release candidates on the same TV with four approved apps, cached artwork and the button watcher off. Both builds used release optimizations and Android's speed precompilation. We used a separate install to preserve the household PIN and settings. Each cold start began with TV Settings in front and the launcher process stopped.

| Measurement | Initial kids mode | Revised kids mode |
|---|---:|---:|
| Cold start to first frame, median of three | 529 ms | 396 ms |
| Cold start including the switch from TV Settings, median of three | 866 ms | 766 ms |
| Idle launcher RAM, median of three PSS readings | 31.7 MiB | 16.3 MiB |
| Frames drawn over eight idle seconds | 0 | 0 |
| Process CPU ticks over the same interval | 2 | 0 |

First frame is the activity-specific duration in Android's `am_activity_launch_time` event. RAM was sampled with `dumpsys meminfo` four seconds after each cold start. Idle ticks are the change in the process's user and system CPU counters.

In a separate remote-input test on the same TV, the fourth digit to confirmed kids-mode exit took 8.5 seconds with the original verifier and about 1.4 seconds across three repeat entries with the revised verifier. That timing includes dispatching the remote key over ADB and observing the mode change. An existing PIN still needs one successful check with the old verifier before it upgrades. Android 5.1 retains the original verifier.
