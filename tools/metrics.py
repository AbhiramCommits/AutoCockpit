#!/usr/bin/env python3
"""Aggregate AutoCockpit measurement artifacts into results/.

Inputs (whatever exists):
  results/gauge_frame_metrics_device.json      FrameTimeTracker export from the device
  results/gfxinfo_framestats.txt               adb shell dumpsys gfxinfo <pkg> framestats
  results/gauge_egl_benchmark_device.json      offscreen EGL benchmark
  */build/test-results/**/*.xml               JVM/Robolectric JUnit results
  */build/outputs/androidTest-results/**/*.xml instrumented JUnit results

Outputs:
  results/frame_metrics.json
  results/instrumented_tests.json / .txt
  results/unit_tests.json
"""

import glob
import json
import math
import os
import xml.etree.ElementTree as ET

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RESULTS = os.path.join(ROOT, "results")
JANK_MS = 1000.0 / 60.0


def round2(value):
    return round(float(value) * 100.0) / 100.0


def percentile(sorted_values, p):
    if not sorted_values:
        return 0.0
    rank = max(1, min(len(sorted_values), math.ceil(p / 100.0 * len(sorted_values))))
    return sorted_values[rank - 1]


def frame_summary(durations_ms):
    durations_ms = sorted(durations_ms)
    if not durations_ms:
        return None
    jank = sum(1 for value in durations_ms if value > JANK_MS)
    return {
        "count": len(durations_ms),
        "meanMs": round2(sum(durations_ms) / len(durations_ms)),
        "p50Ms": round2(percentile(durations_ms, 50)),
        "p95Ms": round2(percentile(durations_ms, 95)),
        "p99Ms": round2(percentile(durations_ms, 99)),
        "maxMs": round2(durations_ms[-1]),
        "jankFrames": jank,
        "jankPct": round2(jank * 100.0 / len(durations_ms)),
        "jankThresholdMs": round2(JANK_MS),
    }


def parse_framestats(path):
    """Parse the profiledata section of dumpsys gfxinfo framestats."""
    rows = []
    in_profile = False
    header = None
    with open(path, encoding="utf-8", errors="replace") as handle:
        for line in handle:
            line = line.strip()
            if line.startswith("---PROFILEDATA---"):
                in_profile = not in_profile
                header = None if in_profile is False else header
                continue
            if not in_profile or not line:
                continue
            parts = line.split(",")
            if header is None:
                header = parts
                continue
            if not parts[0].lstrip("-").isdigit():
                continue
            rows.append(dict(zip(header, parts)))
    durations = []
    for row in rows:
        try:
            intended = float(row["IntendedVsync"])
            completed = float(row["FrameCompleted"])
        except (KeyError, ValueError):
            continue
        duration = (completed - intended) / 1_000_000.0
        if duration > 0:
            durations.append(duration)
    return durations


def load_json(path):
    try:
        with open(path, encoding="utf-8") as handle:
            return json.load(handle)
    except (OSError, ValueError):
        return None


def build_frame_metrics():
    tracker_path = os.path.join(RESULTS, "gauge_frame_metrics_device.json")
    tracker = load_json(tracker_path)
    gfxinfo_path = os.path.join(RESULTS, "gfxinfo_framestats.txt")
    egl = load_json(os.path.join(RESULTS, "gauge_egl_benchmark_device.json"))

    result = {
        "source": "none",
        "jankThresholdMs": round2(JANK_MS),
        "reason": "no on-device frame data was produced in this build",
    }

    if tracker and tracker.get("count"):
        result = {
            "source": "FrameTimeTracker (device, Choreographer)",
            "jankThresholdMs": tracker.get("jankThresholdMs", round2(JANK_MS)),
            "count": tracker.get("count"),
            "meanMs": tracker.get("meanMs"),
            "p50Ms": tracker.get("p50Ms"),
            "p95Ms": tracker.get("p95Ms"),
            "p99Ms": tracker.get("p99Ms"),
            "maxMs": tracker.get("maxMs"),
            "jankFrames": tracker.get("jankFrames"),
            "jankPct": tracker.get("jankPct"),
            "windowCapacity": tracker.get("windowCapacity"),
        }
    elif os.path.exists(gfxinfo_path):
        durations = parse_framestats(gfxinfo_path)
        summary = frame_summary(durations)
        if summary:
            result = {"source": "dumpsys gfxinfo framestats (device)", "jankThresholdMs": round2(JANK_MS), **summary}
        else:
            result = {
                "source": "none",
                "reason": "gfxinfo framestats was captured but contained no completed frames",
                "gfxinfoBytes": os.path.getsize(gfxinfo_path),
            }

    if egl:
        result["offscreenEgl"] = {
            "source": "OffscreenGaugeBenchmark (EGL pbuffer)",
            "frames": egl.get("frames"),
            "totalMs": egl.get("totalMs"),
            "msPerFrame": egl.get("msPerFrame"),
            "fps": egl.get("fps"),
            "es3": egl.get("es3"),
            "glRenderer": egl.get("glRenderer"),
        }
    return result


def parse_junit(pattern):
    # XML files from earlier runs linger in build/test-results; keep only the newest per suite.
    newest = {}
    for path in glob.glob(pattern, recursive=True):
        try:
            root = ET.parse(path).getroot()
            mtime = os.path.getmtime(path)
        except (ET.ParseError, OSError):
            continue
        name = root.get("name") or path
        if name in newest and newest[name][0] >= mtime:
            continue
        newest[name] = (mtime, root)

    suites = []
    total = failures = errors = 0
    time_sum = 0.0
    for name, (_, root) in sorted(newest.items()):
        tests = int(root.get("tests", 0))
        failed = int(root.get("failures", 0))
        errored = int(root.get("errors", 0))
        duration = float(root.get("time", 0.0))
        total += tests
        failures += failed
        errors += errored
        time_sum += duration
        suites.append({
            "suite": name,
            "tests": tests,
            "failures": failed,
            "errors": errored,
            "timeSeconds": round(duration, 2),
        })
    return {
        "tests": total,
        "failures": failures,
        "errors": errors,
        "passRate": round2((total - failures - errors) * 100.0 / total) if total else 0.0,
        "wallTimeSeconds": round(time_sum, 2),
        "suites": sorted(suites, key=lambda suite: suite["suite"] or ""),
    }


def build_unit_results():
    modules = {}
    for module in ("core", "gauge", "app"):
        pattern = os.path.join(ROOT, module, "build", "test-results", "**", "*.xml")
        summary = parse_junit(pattern)
        if summary["tests"]:
            modules[module] = summary
    total = sum(module["tests"] for module in modules.values())
    failures = sum(module["failures"] + module["errors"] for module in modules.values())
    return {
        "tests": total,
        "failures": failures,
        "passRate": round2((total - failures) * 100.0 / total) if total else 0.0,
        "wallTimeSeconds": round(sum(module["wallTimeSeconds"] for module in modules.values()), 2),
        "modules": modules,
    }


def build_instrumented_results():
    app = parse_junit(os.path.join(ROOT, "app", "build", "outputs", "androidTest-results", "**", "*.xml"))
    gauge = parse_junit(os.path.join(ROOT, "gauge", "build", "outputs", "androidTest-results", "**", "*.xml"))
    suites = []
    for name, summary in (("app", app), ("gauge", gauge)):
        for suite in summary["suites"]:
            suites.append({"module": name, **suite})
    total = app["tests"] + gauge["tests"]
    failures = app["failures"] + app["errors"] + gauge["failures"] + gauge["errors"]
    ran = total > 0
    return {
        "ran": ran,
        "tests": total,
        "failures": failures,
        "passRate": round2((total - failures) * 100.0 / total) if total else 0.0,
        "wallTimeSeconds": round(app["wallTimeSeconds"] + gauge["wallTimeSeconds"], 2),
        "suites": suites,
        "note": None if ran else "no instrumented test results were produced on this machine",
    }


def main():
    os.makedirs(RESULTS, exist_ok=True)

    frame_metrics = build_frame_metrics()
    with open(os.path.join(RESULTS, "frame_metrics.json"), "w", encoding="utf-8") as handle:
        json.dump(frame_metrics, handle, indent=2)
        handle.write("\n")

    unit = build_unit_results()
    with open(os.path.join(RESULTS, "unit_tests.json"), "w", encoding="utf-8") as handle:
        json.dump(unit, handle, indent=2)
        handle.write("\n")

    instrumented = build_instrumented_results()
    with open(os.path.join(RESULTS, "instrumented_tests.json"), "w", encoding="utf-8") as handle:
        json.dump(instrumented, handle, indent=2)
        handle.write("\n")

    lines = ["AutoCockpit instrumented test summary", ""]
    if instrumented["ran"]:
        lines.append(
            "tests: %d  failures: %d  pass rate: %.2f%%  wall time: %.2fs"
            % (
                instrumented["tests"],
                instrumented["failures"],
                instrumented["passRate"],
                instrumented["wallTimeSeconds"],
            )
        )
        lines.append("")
        for suite in instrumented["suites"]:
            lines.append(
                "  %-6s %-60s tests=%d failures=%d time=%.2fs"
                % (
                    suite["module"],
                    suite["suite"],
                    suite["tests"],
                    suite["failures"] + suite["errors"],
                    suite["timeSeconds"],
                )
            )
    else:
        lines.append("not measured in this build: no instrumented results were produced.")
    with open(os.path.join(RESULTS, "instrumented_tests.txt"), "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines) + "\n")

    print("frame_metrics.json: %s" % frame_metrics.get("source"))
    if frame_metrics.get("count"):
        print(
            "  frames %d, mean %.2f ms, p95 %.2f ms, jank %.1f%%"
            % (
                frame_metrics["count"],
                frame_metrics["meanMs"],
                frame_metrics["p95Ms"],
                frame_metrics["jankPct"],
            )
        )
    print(
        "unit_tests.json: %d tests, pass rate %.2f%%, %.2fs"
        % (unit["tests"], unit["passRate"], unit["wallTimeSeconds"])
    )
    print(
        "instrumented_tests.json: ran=%s, %d tests, pass rate %.2f%%"
        % (instrumented["ran"], instrumented["tests"], instrumented["passRate"])
    )


if __name__ == "__main__":
    main()
