# Testing AutoCockpit

## Pyramid

| Layer | Location | Runtime | What it proves |
|---|---|---|---|
| Pure JVM unit | `core/src/test` | `./gradlew :core:test` | codec round-trips, chunking, CRC rejection, link state transitions, backoff, degradation tiers, handoff client/server over loopback and lossy transports, simulation determinism |
| Gauge JVM unit | `gauge/src/test` | `./gradlew :gauge:test` | tessellation vertex counts/bounds/angles, allocation-free updates, percentile math on a fixture array, geometry micro-benchmark |
| Robolectric | `app/src/test` | `./gradlew :app:testDebugUnitTest` | MediaBrowserService browse tree and playback transitions, ViewModel degradation walk, gateway fallback, link controller |
| Instrumented (Espresso/Compose) | `app/src/androidTest`, `gauge/src/androidTest` | `./gradlew connectedDebugAndroidTest` | real activity, real MediaBrowser binding, real GL surface and frame timing, AAOS emulator behaviour |
| Headless simulation | `core/src/main` | `./gradlew :core:run --args="simulate …"` | 2,000 seeded sessions through the real handoff pipeline and property monitor |

Everything above the instrumented layer runs without an emulator. The `:core` module has **zero
Android imports**; `:gauge` keeps geometry and percentile math free of Android types; `:app` hides
`android.car` behind `CarPropertyGateway` and BLE behind `HandoffTransport`.

## Test seams

* `CarPropertyGateway` — `AndroidCarPropertyGateway` (car bus) vs `FakeCarPropertyGateway` (scripted
  samples). `CarPropertyGatewayFactory` falls back to the fake with a visible banner when
  `Car.createCar` throws, the automotive feature is missing or `android.car` is absent.
* `HandoffTransport` — `LoopbackTransport` (loss-free, virtual clock) and
  `LossyTransport(dropRate, corruptRate, disconnectAfterChunks)` (injects link faults) alongside the
  real `BleCompanionClient`.
* `Clock` — the ViewModels take an injected clock so `LIVE → LAST_KNOWN → UNAVAILABLE` can be
  stepped instantly in tests instead of waiting 12 seconds.
* `AppGraph.gatewayOverride` / `clockOverride` — instrumented tests swap the car feed without a
  dependency-injection framework.

## Running

```bash
./gradlew :core:test                     # pure JVM
./gradlew :gauge:test                    # geometry, frame stats, benchmark
./gradlew :app:testDebugUnitTest         # Robolectric
./gradlew :app:assembleDebug             # debug APK
bash tools/dev.sh unit                   # all three unit suites
bash tools/dev.sh sim                    # 500 sessions x 4 loss rates
bash tools/dev.sh all                    # unit + lint + sim + benchmark + emulator + metrics
bash tools/emulator.sh                   # AAOS emulator, instrumented suites, metrics pull
bash tools/metrics.sh                    # aggregate results/*.json
```

`SESSIONS` and `SEED` tune the simulation, `OUT` its output path, `SKIP_EMULATOR=1` skips the device
run, `BOOT_TIMEOUT` bounds emulator boot, and `KEEP_EMULATOR=1` leaves the emulator running.

## What is measured where

| Metric | Produced by | File |
|---|---|---|
| Simulation: success rate, retransmits, recovery latency, degradation tiers | `:core:run` | `results/simulation.json` |
| Tessellation throughput at 64/256/1024 segments | `:gauge:test` (`GaugeGeometryBenchmarkTest`) | `results/geometry_benchmark.json` |
| Device frame times (Choreographer) | `GaugeView`/`FrameTimeTracker`, benchmark toggle or gfxinfo | `results/frame_metrics.json` |
| Offscreen EGL ms/frame | `gauge/src/androidTest/OffscreenGaugeBenchmark` | `results/gauge_egl_benchmark_device.json` |
| Unit test counts/pass rate/wall time | JUnit XML via `tools/metrics.py` | `results/unit_tests.json` |
| Instrumented test counts/pass rate/wall time | instrumented XML via `tools/metrics.py` | `results/instrumented_tests.json` |
| Environment / emulator failures | `tools/emulator.sh` | `results/ENVIRONMENT.md` |

## CI gates

`.github/workflows/ci.yml` has two required jobs:

1. **unit** — JDK 17, Gradle cache, `:core:test :gauge:test :app:testDebugUnitTest lint
   assembleDebug`. Test reports and the APK are uploaded. A lint error fails the job.
2. **instrumented** — `reactivecircus/android-emulator-runner` with
   `system-images;android-33;android-automotive;x86_64` (fallbacks documented in
   `tools/emulator.sh`), running `:app:connectedDebugAndroidTest`, the offscreen EGL benchmark and
   `tools/metrics.py`, then uploading `results/`. `continue-on-error: false`: the AAOS image is part
   of the delivery contract, so a missing image fails loudly rather than silently passing.

## Determinism

The simulation is seeded and uses a simulated clock epoch (`1_700_000_000_000`), never the wall
clock, so the committed `results/simulation.json` is byte-identical for the same seed. The
`SimulationHarnessTest.sameSeedProducesIdenticalReports` test enforces this. Benchmark timings are
inherently machine-dependent and are labelled with the JVM/OS that produced them.
