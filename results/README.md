# Measured results

Every number quoted in the top-level README comes from one of these files. They are produced by
commands in this repository, on the machine and emulator described in `ENVIRONMENT.md`.

| Artifact | Produced by | Contents |
|---|---|---|
| `simulation.json` | `./gradlew :core:run --args="simulate --sessions 500 --seed 42 --out results/simulation.json"` | 2,000 seeded sessions (500 per chunk-loss rate) through the real `CompanionClient`/`HandoffServerCore`/`LossyTransport` pipeline plus the property-degradation monitor: success rate, retransmits, reconnect attempts, recovery latency, fault counts, LIVE/LAST_KNOWN/UNAVAILABLE frames, payload sizes |
| `simulation_run.txt` | the same command (`tee`) | stdout of that run, including the measured wall time |
| `geometry_benchmark.json` | `./gradlew :gauge:test` (runs `GaugeGeometryBenchmarkTest`) | tessellation throughput at 64/256/1024 segments: iterations, ns/update, vertices/ms, allocation count |
| `frame_metrics.json` | `bash tools/metrics.sh` | device frame times: count, mean, p50/p95/p99, max, jank %, plus the offscreen EGL summary when available |
| `gauge_frame_metrics_device.json` | `GaugeAndSettingsTest` via `FrameTimeTracker.exportJson` (also logged as `AUTOCOCKPIT_FRAME_JSON`) | raw Choreographer frame window from the emulator |
| `gauge_egl_benchmark_device.json` | `gauge/src/androidTest/OffscreenGaugeBenchmark` (logged as `AUTOCOCKPIT_EGL_JSON`) | 1,000 offscreen EGL pbuffer frames on the emulator GPU: ms/frame, fps, GL version/renderer |
| `unit_tests.json` | `bash tools/metrics.sh` | JUnit XML aggregation per module: test count, failures, pass rate, wall time |
| `instrumented_tests.json` / `.txt` | `bash tools/metrics.sh` | instrumented suite aggregation: count, failures, pass rate, wall time per suite |
| `ENVIRONMENT.md` | `bash tools/emulator.sh` | host, system image, AVD, boot time, instrumentation exit codes, or the exact failure reason |
| `gfxinfo_framestats.txt` / `gfxinfo.txt` | `adb shell dumpsys gfxinfo com.autocockpit.app [framestats]` | raw Android frame statistics pulled from the device |
| `dev_all.log` | `bash tools/dev.sh all` | full local pipeline log (unit, lint, simulation, benchmark, emulator, metrics) |
| `connected_app.log`, `connected_gauge.log`, `emulator.log` | `tools/emulator.sh` | instrumented run and emulator boot logs |
| `device_*.txt` | `tools/emulator.sh` | device SDK, build flavor, EGL/GL strings |

All measurement runs are reproducible: the simulation is seeded and clock-injected, the geometry
benchmark re-runs on every `:gauge:test`, and the instrumented suites are re-run via
`bash tools/emulator.sh`.
