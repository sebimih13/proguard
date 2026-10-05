#!/usr/bin/env python3
"""Measure on-demand loading against an optional older JAR with synthetic class inputs.

Requires Python 3.9+, a JDK, and Linux /usr/bin/time. This generates actual class
entries, rather than padding a tiny class set with resources that both loaders skip.
"""
import argparse
import base64
import json
import pathlib
import random
import statistics
import subprocess
import tempfile
import time
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--size-mb", type=int, default=300)
parser.add_argument("--runs", type=int, default=3)
parser.add_argument("--baseline", type=pathlib.Path)
parser.add_argument("--report", type=pathlib.Path)
args = parser.parse_args()
assert args.size_mb > 0 and args.runs > 0


def run(*command):
    return subprocess.run([str(x) for x in command], capture_output=True, text=True, check=True)


with tempfile.TemporaryDirectory(prefix="retrace-benchmark-") as temporary:
    work = pathlib.Path(temporary)
    sources = work / "src/bench"
    sources.mkdir(parents=True)
    classes = work / "classes"
    classes.mkdir()
    # Randomized ASCII constants keep ZIP compression from turning the fixture
    # into a much smaller input. The method bodies also exercise eager scanning.
    (sources / "Noise00000.java").write_text(
        'package bench; public class Noise00000 { public static final String DATA="' + "A" * 20000 +
        '"; public int work(int x) { int n=x; ' + "n=n*31+x;" * 256 + " return n; } }"
    )
    (sources / "Target.java").write_text("package bench; public class Target { public Object f; }")
    run("javac", "--release", "8", "-g:none", "-d", classes, sources / "Noise00000.java", sources / "Target.java")
    template = (classes / "bench/Noise00000.class").read_bytes()
    archive = work / "large.jar"
    rng = random.Random(358)
    count = 0
    started = time.monotonic()
    with zipfile.ZipFile(archive, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=1) as output:
        output.write(classes / "bench/Target.class", "bench/Target.class")
        while output.fp.tell() < args.size_mb * 1000000:
            name = f"bench/Noise{count:05d}"
            assert count < 100000, "Use a smaller fixture"
            payload = base64.b64encode(rng.randbytes(15000))
            data = template.replace(b"bench/Noise00000", name.encode()).replace(b"A" * 20000, payload)
            output.writestr(name + ".class", data)
            count += 1
    print(f"Generated {archive.stat().st_size / 1000000:.1f} MB with {count + 1} classes in {time.monotonic()-started:.1f}s", flush=True)

    # Compile against the baseline's shared package API; reflection measures the
    # cached class count in both versions without requiring a public metrics API.
    harness = work / "LoadingBenchmark.java"
    harness.write_text('''package proguard.retrace;
import java.io.*;
import java.lang.reflect.*;
import java.util.*;
public class LoadingBenchmark {
  public static void main(String[] args) throws Exception {
    long start = System.nanoTime();
    Object instance = new DiagnosticClassPool(Collections.singletonList(new File(args[0])));
    DiagnosticClassPool pool = (DiagnosticClassPool) instance;
    if (pool.fields("bench.Target", "f", false).size() != 1) throw new AssertionError();
    Field cache = DiagnosticClassPool.class.getDeclaredField("classes");
    cache.setAccessible(true);
    int count = ((Map<?,?>)cache.get(pool)).size();
    for (int i=0;i<1000;i++) pool.fields("bench.Target", "f", false);
    if (((Map<?,?>)cache.get(pool)).size() != count) throw new AssertionError("Cache grew");
    System.out.println("{\\"loaderMs\\":"+(System.nanoTime()-start)/1000000.0+",\\"parsedClasses\\":"+count+"}");
    if (instance instanceof AutoCloseable) ((AutoCloseable)instance).close();
  }
}''')
    current = ROOT / "lib/retrace.jar"
    run("javac", "--release", "8", "-cp", args.baseline or current, "-d", classes, harness)
    report = {"fixture": "synthetic class entries with randomized constants and bytecode",
              "jarBytes": archive.stat().st_size, "classCount": count + 1,
              "java": run("java", "-version").stderr.splitlines()[0],
              "heap": "-Xms32m -Xmx512m", "runs": args.runs, "results": {}}
    versions = [("onDemand", current)]
    if args.baseline:
        versions.insert(0, ("eager", args.baseline.resolve()))
    for label, jar in versions:
        samples = []
        for sample in range(args.runs):
            timing = work / "time.txt"
            result = run("/usr/bin/time", "-f", "%e,%M", "-o", timing, "java", "-Xms32m", "-Xmx512m",
                         "-cp", str(classes) + ":" + str(jar), "proguard.retrace.LoadingBenchmark", archive)
            elapsed, peak = timing.read_text().strip().split(",")
            metrics = json.loads(result.stdout)
            metrics.update(elapsedSeconds=float(elapsed), peakRssKB=int(peak))
            samples.append(metrics)
        if label == "onDemand":
            assert all(sample["parsedClasses"] == 1 for sample in samples), samples
        report["results"][label] = {
            "medianElapsedSeconds": statistics.median(sample["elapsedSeconds"] for sample in samples),
            "medianLoaderMs": statistics.median(sample["loaderMs"] for sample in samples),
            "medianPeakRssKB": statistics.median(sample["peakRssKB"] for sample in samples),
            "samples": samples,
        }
    rendered = json.dumps(report, indent=2)
    print(rendered)
    if args.report:
        args.report.write_text(rendered + "\n")
