# Contributing to Aura TV

Thanks for helping. A few things keep Aura fast and safe:

- **Keep it light.** Aura uses Android's built-in views only: no AndroidX, Leanback, Compose,
  WebView or Play Services. A new dependency needs a very good reason and measurements.
- **Measure on a slow TV or the emulator** before and after a change that could affect speed
  (`tools/perf_compare.py`). Numbers beat opinions.
- **Run the tests:** `./gradlew :app:testDebugUnitTest`. For UI changes, also run
  `tools/ui_check.py` on an Android TV emulator (see the README).
- **Never test on a TV someone is watching.** The test scripts refuse real TVs, and
  `tools/aura-setup` stops if anything is playing. Please keep it that way.
- **Never commit signing keys** (`keystore/`) or your own device details (IP addresses, reports).

Reporting a problem on a specific TV? Use the "Problem on my TV" issue form and attach the
`aura-setup` report.
