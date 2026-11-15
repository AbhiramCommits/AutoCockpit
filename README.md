# AutoCockpit

AutoCockpit is an Android Automotive OS (AAOS) in-vehicle cockpit: a Kotlin head-unit app with a
MediaBrowserService-backed media browser, a CarPropertyManager-backed vehicle settings screen, and a
custom OpenGL ES 2.0/3.0 speed + energy gauge composited into Jetpack Compose with on-device
frame-time instrumentation. A BLE GATT companion link hands the driver profile and recent media queue
from a phone to the head unit over a small MTU, with chunking, CRC validation, retransmit and
resume-on-reconnect. Everything is designed to degrade rather than crash: missing car properties
become LAST_KNOWN/UNAVAILABLE instead of blank screens, and a dropped phone link never blocks the UI.
The protocol, state machines, degradation policy and a seeded simulation harness live in a pure-JVM
`:core` module, so the whole system (including the BLE transport) is testable headless; the full app
and instrumented suite were also run on the **Android Automotive OS emulator**
(`system-images;android-33;android-automotive;arm64-v8a`, see `results/ENVIRONMENT.md`).

---

## Features and where they live

| Feature | Code |
|---|---|
| AAOS manifest: automotive feature, `android.car` library, media app descriptor, car + BLE permissions | `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/automotive_app_desc.xml` |
| Hierarchical media browser (root → Playlists / Recently Played / Handoff Queue → tracks), 40-track synthetic catalog | `app/src/main/kotlin/com/autocockpit/app/media/AutoCockpitMediaService.kt`, `MediaBrowseTree.kt`, `app/src/main/assets/catalog.json` |
| Real playback: queue/transport state, position ticking, synthesised audio, `MediaSessionCompat` state/actions/metadata | `core/src/main/kotlin/com/autocockpit/core/playback/MediaPlaybackEngine.kt`, `app/src/main/kotlin/com/autocockpit/app/media/TonePlayer.kt` |
| Handoff Queue node populated from the BLE handoff repository | `core/src/main/kotlin/com/autocockpit/core/handoff/HandoffRepository.kt`, `AutoCockpitMediaService.kt` |
| Vehicle properties: `CarPropertyManager` gateway for speed, EV battery, range, HVAC | `app/src/main/kotlin/com/autocockpit/app/car/AndroidCarPropertyGateway.kt` |
| Graceful gateway fallback with a visible banner (never crashes without a car service) | `app/src/main/kotlin/com/autocockpit/app/car/CarPropertyGatewayFactory.kt`, `FakeCarPropertyGateway.kt` |
| Settings screen with LIVE / LAST KNOWN / UNAVAILABLE badges, HVAC write, link state + Re-sync | `app/src/main/kotlin/com/autocockpit/app/ui/screens/SettingsScreen.kt`, `ui/vm/SettingsViewModel.kt` |
| OpenGL ES gauge geometry (arc strip, ticks, energy bar), allocation-free mesh | `gauge/src/main/kotlin/com/autocockpit/gauge/math/GaugeGeometry.kt` |
| GLSL ES 2.0/3.0 renderer: smooth threshold sweep, AA edges, regen/consumption ramp, VBOs, 3 draw calls | `gauge/src/main/kotlin/com/autocockpit/gauge/render/GaugeRenderer.kt` |
| Compose integration + desaturated degradation visual + 1800-frame benchmark toggle | `app/src/main/kotlin/com/autocockpit/app/ui/screens/GaugeScreen.kt`, `gauge/src/main/kotlin/com/autocockpit/gauge/GaugeView.kt` |
| Frame-time instrumentation (count, mean, p50/p95/p99, max, jank %, windowed, JSON export) | `gauge/src/main/kotlin/com/autocockpit/gauge/render/FrameTimeTracker.kt` |
| Handoff wire codec: 6-byte header, CRC-16/CCITT, length-prefixed TLV body | `core/src/main/kotlin/com/autocockpit/core/codec/HandoffCodec.kt` |
| Chunker, reassembler, missing/duplicate/out-of-order handling | `core/src/main/kotlin/com/autocockpit/core/codec/Chunker.kt` |
| GATT protocol engine: BEGIN/COMMIT/ABORT/RESUME, retransmit bitmaps, idempotent commit, schema abort | `core/src/main/kotlin/com/autocockpit/core/handoff/HandoffServerCore.kt`, `HandoffProtocol.kt` |
| Phone-side client: MTU negotiation, resume-from-chunk-N, backoff reconnect, CRC restart | `core/src/main/kotlin/com/autocockpit/core/handoff/CompanionClient.kt` |
| Head-unit BluetoothGattServer / phone BluetoothGatt transport | `app/src/main/kotlin/com/autocockpit/app/link/HandoffGattServer.kt`, `BleCompanionClient.kt` |
| Link state machine: scan → connect → MTU → sync, DEGRADED, exponential backoff with jitter | `core/src/main/kotlin/com/autocockpit/core/link/LinkStateMachine.kt` |
| Degradation policy (staleness windows, error bursts, fallback value + age) | `core/src/main/kotlin/com/autocockpit/core/health/PropertyHealthMonitor.kt` |
| Seeded, deterministic simulation harness + CLI | `core/src/main/kotlin/com/autocockpit/core/simulation/SimulationHarness.kt`, `core/src/main/kotlin/com/autocockpit/core/cli/Main.kt` |
| Tests | `core/src/test` (160), `gauge/src/test` (28), `app/src/test` (46), `app/src/androidTest`, `gauge/src/androidTest` |
| Tooling / CI | `tools/dev.sh`, `tools/emulator.sh`, `tools/metrics.sh`, `tools/metrics.py`, `.github/workflows/ci.yml` |

---

## Architecture

### Modules

```
                            :app  (AAOS APK, minSdk 29 / target 34)
        ┌────────────────────────────────────────────────────────────────────┐
        │ MainActivity ─ Compose bottom nav                                  │
        │   ├── MediaScreen ── MediaBrowserCompat ──┐                        │
        │   ├── GaugeScreen ── AndroidView(GaugeView)│  CarPropertyGateway   │
        │   └── SettingsScreen ─ SettingsViewModel ──┤   ├ AndroidCarProperty-│
        │                                            │   │ Gateway (car bus)│
        │ AutoCockpitMediaService (MediaBrowserService)  └ FakeCarProperty-  │
        │   └ MediaPlaybackEngine + MediaSessionCompat      Gateway (tests)  │
        │ HandoffGattServer ── BluetoothGattServer ── HandoffServerCore      │
        │ BleCompanionClient ── BluetoothGatt ── HandoffTransport             │
        └───────────────┬────────────────────────────────────────────────────┘
                        │ pure-JVM API
        ┌───────────────▼────────────────────────────────────────────────────┐
        │ :core  (JVM 17, ZERO Android imports)                              │
        │ HandoffCodec · Chunker · Reassembler · HandoffProtocol             │
        │ HandoffServerCore · CompanionClient · Loopback/Lossy transports    │
        │ LinkStateMachine · PropertyHealthMonitor · HandoffRepository       │
        │ MediaPlaybackEngine · SimulationHarness · CLI                      │
        └───────────────┬────────────────────────────────────────────────────┘
                        │ geometry + stats types
        ┌───────────────▼────────────────────────────────────────────────────┐
        │ :gauge  (Android library)                                          │
        │ GaugeGeometry (pure Kotlin) · GaugeRenderer (GLSL) · GaugeView      │
        │ FrameTimeTracker + FrameStatsCalculator                            │
        └────────────────────────────────────────────────────────────────────┘
```

### The boundaries that make it headless-testable

* **`CarPropertyGateway`** — `AndroidCarPropertyGateway` wraps `Car`/`CarPropertyManager`; the fake
  replays scripted samples. `CarPropertyGatewayFactory` selects the real gateway only when the device
  is automotive, `android.car` loads and the car service answers; any failure returns the fake plus a
  reason string that the settings screen shows as a banner. Nothing above the interface knows which
  one is running.
* **`HandoffTransport`** — `LoopbackTransport` (in-memory, virtual clock), `LossyTransport`
  (configurable drop/corruption/disconnect), and the real `BleCompanionClient`. The protocol engine
  (`CompanionClient` + `HandoffServerCore`) is identical in all three cases.
* **`Clock`** — injected into the ViewModels, so a test can step time and watch a property walk
  `LIVE → LAST_KNOWN → UNAVAILABLE → LIVE` immediately instead of waiting 12 real seconds.

The SDK path: this build compiles against the Android SDK's optional stub
`$ANDROID_HOME/platforms/android-34/optional/android.car.jar` via `compileOnly(files(...))`
(`app/build.gradle.kts`). When that jar is absent the build adds `app/src/carStubs` instead; the
stub source set (Car, VehiclePropertyIds, CarPropertyValue, CarPropertyManager) is committed so the
project still compiles on an SDK without the automotive stub.

### BLE GATT service and characteristics

| Characteristic | UUID | Properties | Purpose |
|---|---|---|---|
| Handoff service | `a11c0c70-7e11-4a01-9d00-000000000001` | primary | container |
| `HANDOFF_WRITE` | `a11c0c70-7e11-4a01-9d00-000000000002` | write-without-response | chunked frame payload |
| `HANDOFF_CONTROL` | `a11c0c70-7e11-4a01-9d00-000000000003` | write, read | BEGIN/COMMIT/ABORT/RESUME; read returns schema + MTU |
| `HANDOFF_STATUS` | `a11c0c70-7e11-4a01-9d00-000000000004` | notify, read | progress, retransmit bitmap, commit result, abort |

Full protocol (opcodes, resume, idempotency, error codes): [`docs/HANDOFF_PROTOCOL.md`](docs/HANDOFF_PROTOCOL.md).

### Handoff wire frame

```
 0      1       2      3      4      5      6                    N
+------+-------+-------------+-------------+--------------------------------+
| 0xAC |schema | bodyLength  |   crc16     |  body: TLV(tag u8, len u16)    |
| magic| 0x01  |   u16 BE    |CRC-16/CCITT |  0x01 profile  0x02 queue      |
+------+-------+-------------+-------------+  0x03 meta(generatedAtMillis)  |
                                                                     ... 0x03
each chunk on HANDOFF_WRITE:
+-------------+-------------+---------------------------+
| index u16   | total u16   | up to (ATT payload - 4)   |
+-------------+-------------+---------------------------+
MTU 185 → ATT payload 182 → 178 data bytes/chunk; MTU 23 → 20 ATT → 16 data bytes.
```

### Link state machine

```
        start            peer found         connected        mtu >= 23
DISCONNECTED ─▶ SCANNING ─▶ CONNECTING ─▶ NEGOTIATING_MTU ─▶ SYNCING ──▶ SYNCED
     ▲             │  ▲          │               │              │
     │ reset       │  │ tick     │ fail          │ fail         │ complete
     │             ▼  │          ▼               ▼              ▼
     └────────── DEGRADED ◀──────────────────────────────────────┘
                    │ ▲
                    │ └─ reconnect tick while budget remains
                    └─── 5 failed attempts → exhausted (UI shows reason, stays responsive)
```

Backoff: `500 ms × 2^(attempt-1)`, capped at 30 s, ±50 % jitter from a seeded RNG; schema mismatch
is fatal and never retried.

---

## How to run

### Prerequisites

* JDK 17 (`brew install openjdk@17` or any Temurin 17)
* Android SDK with `platforms;android-34`, `build-tools;34.0.0`, `platform-tools`
* For device tests: `emulator` plus `system-images;android-33;android-automotive;x86_64` (Linux CI)
  or `system-images;android-33;android-automotive;arm64-v8a` (Apple Silicon)
* `local.properties` with `sdk.dir=...`, or `ANDROID_HOME` exported (`tools/env.sh` autodetects both)

### Build and test

```bash
./gradlew :core:test                        # 160 pure-JVM tests
./gradlew :gauge:test                       # 28 gauge tests + tessellation benchmark
./gradlew :app:testDebugUnitTest            # 46 Robolectric tests
./gradlew :core:test :gauge:test :app:testDebugUnitTest lint spotlessCheck assembleDebug
bash tools/dev.sh unit                      # all three unit suites
bash tools/dev.sh lint                      # Android lint + spotless (ktlint)
```

### Headless handoff simulation

```bash
./gradlew :core:run --args="simulate --sessions 500 --seed 42 --out results/simulation.json"
bash tools/dev.sh sim                       # same, 500 sessions x 4 loss rates
```

The harness runs the real client/server over `LossyTransport`, so success rate, retransmits, resume
behaviour and recovery latency are measured from the production protocol code, not a model.

### Emulator and instrumented tests

```bash
bash tools/emulator.sh        # installs the AAOS image if needed, boots headless, runs both suites,
                              # pulls frame metrics + gfxinfo into results/
bash tools/dev.sh all         # unit + lint + sim + benchmark + emulator + metrics
bash tools/dev.sh metrics     # re-aggregate results/*.json from existing artifacts
```

`tools/emulator.sh` prefers `android-33;android-automotive;<host arch>`, then
`android-automotive-playstore`, then documents `android-34;google_apis` as a fallback (the app
declares `android.hardware.type.automotive` as required, so it will not install there). It boots with
`-no-window -gpu swiftshader_indirect -no-snapshot`, waits for `sys.boot_completed`, grants the
Bluetooth permissions and runs `connectedDebugAndroidTest` plus the offscreen EGL benchmark.

### Driving the handoff from the companion side

* **Headless / CI:** construct `HandoffServerCore(HandoffRepository)` and `LoopbackTransport(server)`,
  wrap in `LossyTransport`, and call `CompanionClient(transport).send(payload)` — see
  `core/src/test/kotlin/com/autocockpit/core/handoff/CompanionClientTest.kt` and
  `app/src/androidTest/kotlin/com/autocockpit/app/InstrumentedHarness.kt`. The committed session
  lands in the repository, which immediately repopulates the media browser's Handoff Queue node.
* **Phone:** the same `CompanionClient` with `BleCompanionClient(context, deviceAddress)` after
  scanning for the advertised service UUID. The head unit side is `HandoffGattServer`, started from
  `MainActivity` once `BLUETOOTH_CONNECT`/`BLUETOOTH_ADVERTISE` are granted.

---

## RESULTS

Every number below was produced in this build. Sources are the files under `results/`, described in
[`results/README.md`](results/README.md).

### Test suites

| Suite | Tests | Pass rate | Wall time | Source |
|---|---|---|---|---|
| `:core` (pure JVM) | 160 | 100 % | 1.72 s | `results/unit_tests.json` |
| `:gauge` (JVM) | 28 | 100 % | 3.51 s | `results/unit_tests.json` |
| `:app` (Robolectric) | 46 | 100 % | 4.76 s | `results/unit_tests.json` |
| **Unit total** | **234** | **100 %** | **9.99 s** | `results/unit_tests.json` |
| Instrumented (AAOS emulator, 6 app + 1 gauge) | 7 | 100 % | 34.41 s | `results/instrumented_tests.json` |

Unit-test wall time is the sum of the JUnit XML suite times, not Gradle wall time. The final
`bash tools/dev.sh all` run is logged in `results/dev_all.log`, with instrumentation logs in
`results/connected_app.log` and `results/connected_gauge.log`.

### Gauge frame times on the emulator

`FrameTimeTracker` (Choreographer) on the Gauge screen, exported by `GaugeAndSettingsTest`:

| Frames | Mean | p50 | p95 | p99 | Max | Jank (>16.67 ms) |
|---|---|---|---|---|---|---|
| 300 | 19.61 ms | 16.67 ms | 33.33 ms | 66.67 ms | 183.33 ms | 30 frames / 10.00 % |

Source: `results/frame_metrics.json`, `results/gauge_frame_metrics_device.json`. This is a software
GL (SwiftShader) automotive emulator at 60 Hz vsync; it is not representative of a production GPU.

### Offscreen EGL gauge benchmark (GPU-side)

1000 frames after 100 warmup frames, 1024×1024 pbuffer, serialised with `glFinish`, ES 3.0 path:

| Frames | ms / frame | FPS equivalent | GL stack |
|---|---|---|---|
| 1000 | 6.41 ms | 155.90 | OpenGL ES 3.1 (ANGLE/Vulkan, SwiftShader) |

Source: `results/gauge_egl_benchmark_device.json`, `results/frame_metrics.json` (`offscreenEgl`).

### Tessellation throughput

`GaugeGeometryBenchmark` (warmup 20,000 updates; timed 400 ms per size; no framework):

| Segments | Vertices/update | Iterations | ns/update | vertices/ms |
|---|---|---|---|---|
| 64 | 130 | 65,712 | 6,087.20 | 21,356.29 |
| 256 | 514 | 22,310 | 17,929.27 | 28,668.21 |
| 1024 | 2,050 | 5,320 | 75,194.20 | 27,262.74 |

Source: `results/geometry_benchmark.json`. All runs reported `allocationCount = 1` (the mesh buffer
is allocated once and rewritten in place).

### Handoff simulation (2,000 sessions: 500 per loss rate, seed 42)

| Chunk loss | Success | Retransmits mean / p95 | Reconnect sessions | Recovery latency mean / p95 |
|---|---|---|---|---|
| 0 % | 100.00 % | 1.36 / 4 | 136 / 500 | 501.63 ms / 711 ms |
| 1 % | 100.00 % | 2.98 / 9 | 110 / 500 | 496.08 ms / 709 ms |
| 5 % | 100.00 % | 8.78 / 20 | 129 / 500 | 486.04 ms / 707 ms |
| 10 % | 100.00 % | 17.35 / 38 | 138 / 500 | 508.05 ms / 737 ms |
| **All** | **100.00 %** | **7.62 / 29** (max 54) | **513** | **498.25 ms / 717 ms** |

Faults actually injected across those sessions: 3,435 property dropouts, 138,143 stale samples,
5,833 property errors, 513 BLE disconnects, 14,012 dropped chunks, 3,202 corrupted chunks, 15,705
out-of-order arrivals, 0 reconnect-budget exhaustions, 0 schema aborts. Recovery latency and
retransmits are measured over sessions that reconnected (n=513); retransmits are per session.
Source: `results/simulation.json`, stdout in `results/simulation_run.txt`.

### Payload size and bytes on the wire

| Item | Value |
|---|---|
| Canonical 500-track frame | 48,002 bytes |
| Largest frame handled in any simulated session | 57,497 bytes (500 tracks) |
| Chunks for the canonical 500-track frame at the 20-byte ATT payload | 3,001 |
| Bytes on the wire for that frame (frame + 4-byte chunk header + 3-byte ATT header per chunk) | 69,009 |

Source: `results/simulation.json` (`handoff`, `payload`).

### Degradation statistics

Over 1,200,000 simulated signal frames (5 signals × 120 frames × 2,000 sessions):

| Tier | Frames | Share |
|---|---|---|
| LIVE | 1,011,245 | 84.27 % |
| LAST_KNOWN | 140,685 | 11.72 % |
| UNAVAILABLE | 48,070 | 4.01 % |

Recovery from a property dropout back to LIVE: 115.28 ms mean / 200 ms p95 (n=3,435).
Source: `results/simulation.json` (`degradation`).

### Environment

Android Automotive OS emulator, `system-images;android-33;android-automotive;arm64-v8a`, AVD
`autocockpit_aaos`, `sdk_gcar_arm64-userdebug`, SDK 33, boot 15 s, host Darwin/arm64, SwiftShader
software GL. Source: `results/ENVIRONMENT.md`. The measured numbers are therefore **emulator**
numbers for the instrumented, frame-time and EGL results, and **headless JVM** numbers for the unit
tests, simulation and tessellation benchmark. Nothing in the results section is estimated.

---

## Known limitations / what I'd do next

* **No real car bus.** The emulator has no CarService properties, so the settings screen runs on the
  deterministic `FakeCarPropertyGateway` (with a visible banner). The `AndroidCarPropertyGateway`
  path compiles against the real `android.car` stub and is structured to run on hardware, but real
  `PERF_VEHICLE_SPEED` / `EV_BATTERY_LEVEL` values were not verified on a physical head unit.
* **Software GL.** All GPU numbers come from SwiftShader/ANGLE on the emulator. A Vulkan path and
  measurements on a real automotive SoC are the obvious next step; the renderer already isolates the
  GL program behind `GaugeRenderer`.
* **BLE without radios.** The emulator has no BLE peer, so the GATT peripheral/client classes are
  compile-verified but the end-to-end measurements use `LoopbackTransport`/`LossyTransport` in the
  same process. The protocol (chunking, CRC, retransmit, resume, idempotent commit) is exercised
  byte-for-byte by those tests, but RF-level timing (connection interval, MTU behaviour on real
  phones) is not measured.
* **Security.** The handoff channel relies on BLE pairing/OS-level trust; the frame has a CRC, not a
  MAC or signature. Adding an authenticated key exchange and an AEAD tag is the next protocol step.
* **AAOS media policy.** The service declares a media browser and a mediaPlayback foreground service
  type, but a production build must also post a `MediaStyle` notification and satisfy the car media
  centre's UX requirements; the emulator suite validates the browse/session contract, not Play Store
  automotive review.
* **One-way handoff.** Phone → head unit only; there is no write-back of playhead or profile edits.
* **Payload ceiling.** The u16 frame length caps a handoff at 65,535 bytes; 500 tracks fit (48–57 KB),
  but very long titles or larger queues would need a multi-frame transfer or a larger length field.
* **Audio** is a synthesised sine tone per track, not real media, to keep the repository free of
  copyrighted content.

---

## Documentation

* Protocol wire spec: [`docs/HANDOFF_PROTOCOL.md`](docs/HANDOFF_PROTOCOL.md)
* Test pyramid, seams, CI gates: [`docs/TESTING.md`](docs/TESTING.md)
* What each measured artifact contains: [`results/README.md`](results/README.md)
