# Diagnostic loading benchmark

Measured on 2026-10-05 on an AMD Ryzen 7 9800X3D, Linux, OpenJDK 17.0.20.1.
Both builds ran with `-Xms32m -Xmx512m`. Figures are medians of three fresh JVM
processes. Inputs were generated under `/tmp` on tmpfs, so this measures in-memory
file I/O, ZIP processing, class parsing, and indexing rather than cold disk latency.

The input was 301,333,460 bytes (301.3 MB), containing
19,409 distinct class entries. Noise classes contain randomized
ASCII string constants and repeated arithmetic bytecode; the lookup requests
only `bench.Target.f`. There are no large resource entries used as padding.
This is a reproducible synthetic workload, not the application's real dependency set.

| Metric | Previous eager loader | On-demand loader |
| --- | ---: | ---: |
| Loader + lookup + 1,000 repeated lookups | 1320.9 ms | 35.8 ms |
| Whole JVM process elapsed | 1.34 s | 0.05 s |
| Peak process RSS | 205.0 MiB | 61.4 MiB |
| Classes parsed | 19,409 | 1 |

The new implementation indexes archive entry names and parses requested classes
once. It also caches missing classes. Full metadata and bytecode scanning costs
are incurred only for requested classes. There is no persistent cache between
separate CLI invocations. Indexing costs still depend on archive count and entry
count; many relevant classes, larger methods, actual disks, and different mapping
sizes can change the result substantially. These figures do not predict the
elapsed time for a particular 300 MB application.

Reproduce with:

```sh
python3 examples/retrace-hotfix/benchmark-loading.py --size-mb 300 --runs 3 \
  --baseline /path/to/eager/retrace.jar --report /tmp/loading.json
```

The raw measurements are in [loading-benchmark.json](loading-benchmark.json).
