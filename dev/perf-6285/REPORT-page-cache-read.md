# Dart: serve memory-cache misses from the OS page cache when resident (#6303 stack)

Branch `dart/page-cache-read` from `claude/6300-prefetch` @ 9daa01cf1.
Container: 4 vCPU, 16 GB, no swap, ext4 on virtio (`/dev/vda`), Linux 6.18, runtime JDK 25 (jlink custom-jre; `java.lang.foreign` is final there, `--enable-native-access=ALL-UNNAMED` is already in the default JVM args, no preview flag needed).
TPC-H SF1 on disk is 11–14 GB (lineitem 7.6 GB). Bench was run with plain `java` and the dev classpath, not through `./gradlew tpch`: the gradle daemons + JVM got OOM-killed (exit 137) during the first load.

## TL;DR

- **Q3 – the cold regression is not reproducible here.** `main` (7348ce5f6) is *slower* than the base on cold *and* hot Q1/Q6 (cold Q1 18–30 s vs 13–15 s at 3g; hot Q1 14–15 s vs 8–9 s; hot Q6 10–11 s vs 5–5.5 s). Cold Q1 is **CPU-bound, not I/O-bound** on this box (iowait ≈ 1.4 of 56 core-s), so there is little for a page-cache read to win.
- **The mechanism works, but buys nothing measurable on this container.** With the page cache warmed the way `main`'s mmap reads leave it, 99% of misses are served from it, and cold Q1 is 12.7–14.6 s (3 runs) vs base 13.8/15.9/15.0 – within the noise (~±1.5 s). Hot is unchanged. Memory isn't a problem for the invariants (see below), but the evidence doesn't justify the native call yet; it needs a slower device to show a benefit (see Open questions).
- **The probe matters more than the read.** `mincore` over a per-miss `mmap`/`munmap` *hurts* (cold Q1 17–18.6 s vs ~14): the mmap_lock traffic makes a 4.6 µs micro-benchmark probe cost ~1.1 ms in the JVM. **`cachestat(2)` (Linux ≥ 6.5) has none of that**: no mapping, no readahead, ~50 µs per probe incl. `open`/`close`.
- **`preadv2(RWF_NOWAIT)` violates the "cold read doesn't add to the page cache" invariant**: a cold NOWAIT read returns EAGAIN *and* starts readahead (1 MB resident after one probe). FADV_RANDOM doesn't stop it.

## What I found about the primitives (probe programs, ext4 on /dev/vda)

| Probe | Result |
| --- | --- |
| `preadv2(RWF_NOWAIT)` cold | `-1`, `errno=EAGAIN` – **and ~256 pages (the request size) become resident** (readahead kicked off). Repeating on the same range then returns data. `posix_fadvise(RANDOM)` doesn't change it. |
| `preadv2(RWF_NOWAIT)` warm | full read. |
| `preadv2(RWF_NOWAIT)`, 256 KB resident of a 1 MB request | **short read** (`n=262144`, `errno` not set) – so a caller must treat `n < len` as a miss (the invariant "short NOWAIT read never yields a wrong page" is satisfiable, but only by discarding it). |
| `mincore` over `mmap(PROT_READ, MAP_PRIVATE)` | no side effects (0 pages resident after 10 000 probes of a cold range); 4.6 µs per 1 MB probe in isolation; but see mmap_lock contention below. |
| `cachestat(451)` via `syscall()` downcall | `nr_cache >= pages in range` ⇒ resident. Works on this 6.18 kernel; no side effects. Returns ENOSYS before 6.5 → treated as "not resident" ⇒ today's O_DIRECT path. |
| FFM `Linker` downcalls | `open`, `close`, `syscall` (variadic: `Linker.Option.firstVariadicArg(1)`), `mmap`/`munmap`/`mincore` all resolve through `defaultLookup()`. Kotlin: use `MethodHandle.invoke`, **not** `invokeExact` (Kotlin gives the polymorphic call an `Object` return type, so `invokeExact` fails at runtime). `Arena.allocateUtf8String` doesn't exist on JDK 25 and `allocateFrom` doesn't exist on 21, so I copy the path bytes into a NUL-terminated segment by hand. |

The block device here is fast (dd, O_DIRECT 1.7 GB/s; buffered cold 0.9 GB/s; from the page cache 5.8 GB/s). `blkio.throttle.read_bps_device` works for both O_DIRECT and buffered reads, so I also ran everything with the device throttled to 250 MB/s (the JVM in its own blkio cgroup).

## What the dart does (route taken, not a design)

- `core/src/main/kotlin/xtdb/cache/PageCacheProbe.kt` – `isResident(path, offset, length)`: `open` → `syscall(cachestat)` → `close`, all through FFM. (First version: `mmap` + `mincore` + `munmap`; measured worse – see below.)
- `MemoryCache.readPage`: on the I/O dispatcher, `if (isResident) buffered FileChannel.readFully else (existing O_DIRECT bounce read | buffered fallback)`. Everything else in `MemoryCache` (budget, eviction, holding, the OOM path, the allocation of the frame) is untouched. The buffered branch is exactly what the existing no-O_DIRECT fallback does, so "filesystems without O_DIRECT keep the buffered fallback" holds trivially.
- Experiment-only: system property `xtdb.dart.pageCacheRead` (default true), counters printed on `close()` (`memory-cache reads: pageCache=… direct=… probeMs… pcReadMs… pcMB… directReadMs… directMB…`), `xtdb.dart.pcBounce` (read via the bounce buffer instead of straight into the frame; no clear effect: cold Q1 13.2, 16.5 s vs 12.7–14.6 s), and in `modules/bench/.../tpch.clj` `-Dxtdb.dart.qs=1,6` / `-Dxtdb.dart.hot-reps=N` so a run does just Q1+Q6 and N hot repeats.
- A page that is mostly resident but has a hole falls back to O_DIRECT for the whole page (all-or-nothing). Between probe and read an eviction can turn the buffered read into a blocking one – still correct, just adds to the page cache; there is no wrong-page risk because the read is an ordinary `pread` loop that throws on a short read.
- Not done: tests, `Fetch`-side handling (remote pool's disk-cache download files are ordinary local files here, so they probe the same way), anything about writes.

## Numbers (TPC-H SF1, Q1+Q6 only, `--no-load`, one JVM per cell, seconds)

`evict` = `echo 1 > /proc/sys/vm/drop_caches` before the run. `warmed` = drop, then run `main` once first (what an earlier `main` bench run leaves in the page cache: ~1.7–1.9 GB for Q1/Q6; `main` reads via mmap, base/dart via O_DIRECT, so *they* leave nothing). "3g" = default JVM (Xmx2g, 3g max direct, ~1.5 GB cache); "6g" = `-PtwelveGBJvm` (Xmx5g, 6g direct, 3 GB cache).
`dart` rows are the final cachestat version unless marked `(mincore)`. Hot = median of 5 repeats.

### Fast disk, 3g

| variant / condition | cold Q1 | cold Q6 | hot Q1 | hot Q6 |
| --- | --- | --- | --- | --- |
| main, evict | 19.5 | 10.3 | 14.2 | 10.1 |
| main, warm (2nd run) | 18.2 | 10.7 | 15.4 | 10.9 |
| base, evict (3 runs) | 13.8 / 13.2 / 14.5 | 6.0 / 5.6 / 6.0 | 8.8 / 8.5 / 8.9 | 5.6 / 5.3 / 5.5 |
| base, warmed (3 runs) | 13.8 / 15.9 / 15.0 | 6.0 / 6.6 / 6.6 | 7.9 / 9.4 / 9.1 | 4.5 / 5.7 / 5.5 |
| dart, evict (3 runs; cachestat) | 13.9 / 12.9* / 14.1 / 14.4 | 5.8–6.4 | 8.5–8.9 | 5.4–5.7 |
| dart, warmed (3 runs) | 14.6 / 13.4 / 12.7 | 6.5 / 6.2 / 6.2 | 9.3 / 8.9 / 7.8 | 5.7 / 5.3 / 5.0 |
| dart (mincore), warmed (4 runs) | 17.2 / 17.2 / 18.6 / 17.7 | 6.1–6.6 | 8.5–9.2 | 5.3–5.4 |

\*instrumented build. With `warmed`, dart served 13 810 of 13 986 misses from the page cache (1 317 MB; 343 MB went to O_DIRECT); with `evict`, 2 of 13 986.

### 6g

| variant / condition | cold Q1 | cold Q6 | hot Q1 | hot Q6 |
| --- | --- | --- | --- | --- |
| main, evict / warm | 27.5 / 18.6 | 10.1 / 10.9 | 14.2 / 15.3 | 9.8 / 11.8 |
| base, evict / warmed | 21.4 / 29.0 | 5.7 / 5.7 | 9.3 / 8.8 | 5.5 / 5.1 |
| dart, evict / warmed (cachestat) | 18.1 / 27.5 | 5.8 / 5.4 | 8.3 / 8.2 | 5.4 / 5.2 |

At 6g **everyone's** cold Q1 is ~1.5–2× the 3g number (base 21–29 vs 13–16), independent of page-cache state. The CPU sampler shows why: base cold Q1 JVM **sys** time 16.1 s at 6g vs 5.0 s at 3g (user 25.8 vs 28.5) – kernel time in the larger allocation/first-touch of the cache, not I/O. This looks more like the card's "cache that fits" regression than anything about the page cache; worth its own look.

### Device throttled to 250 MB/s (blkio cgroup), cold Q1 / Q6

| | 3g | 6g |
| --- | --- | --- |
| main evict / warm | 18.6/10.1, 19.4/11.7 | 30.3/10.6, 19.2/11.8 |
| base evict / warmed | 14.2/6.1, 16.6/6.7 | 20.1/5.7, 24.8/7.2 |
| dart evict / warmed (mincore build) | 12.5/6.0, 18.4/6.1 | 17.0/7.9, 28.9/9.1 |

Cold Q1 needs only ~1.7 GB of reads, so 250 MB/s adds ≈ 0–1 s (iowait total ~1.4 core-s). I did not push the throttle lower.

### Is cold I/O-bound? (CPU sampler, `/proc/<pid>/stat` + `/proc/stat` between stage boundaries)

| run | wall | JVM user / sys (core-s) | host idle / iowait (core-s) |
| --- | --- | --- | --- |
| base 3g evict, Q1 | 14.1 | 28.5 / 5.0 | 20.2 / 1.4 |
| base 3g evict, Q6 | 5.9 | 7.7 / 0.7 | 13.3 / 0.0 |
| base 6g evict, Q1 | 18.5 | 25.8 / 16.1 | 27.2 / 1.4 |
| dart 3g warmed, Q1 | 16.0 | 28.6 / 10.8 | 21.1 / 0.2 |

No: cold Q1 uses ~2.3 of 4 cores and is dominated by user CPU (first-run JIT/codegen + decode); iowait is a few % of the wall. The page-cache-served variant moves ~6 core-s *into kernel time* (copying 1.3 GB out of the page cache into unfaulted frames costs ~100–150 MB/s per I/O thread cumulatively, vs ~240 MB/s for O_DIRECT) – it is cheaper in bytes-from-device, not in CPU.

### Live node (load → compact → cold queries in the same JVM; full 21-query bench, 3g)

| variant | ingest | compact | cold Q1 | cold Q6 | hot Q1 | hot Q6 |
| --- | --- | --- | --- | --- | --- | --- |
| base | 261 s | 120 s | 8.6 | 3.1 | 7.1 | 3.4 |
| main | 291 s | 135 s | 14.6 | 8.7 | 12.7 | 8.7 |
| dart (cachestat) | 253 s | 107 s | 9.1 | 3.1 | 6.4 | 3.0 |
| dart (mincore, first load) | 233 s | 107 s | 7.9 | 2.8 | 5.7 | 2.9 |

Across the dart load+compaction+21 cold+21 hot queries, **122 144 misses (22.3 GB) were page-cache resident and 85 832 (15.0 GB) were not** – the node's own writes are in the page cache (7–10 GB buff/cache after load) – so the mechanism has a lot to serve in the live case, but at 8–9 s cold Q1 on a fast device it doesn't show up in latency. Probing cost ≈ 4.9 s summed over I/O threads (≈ 23 µs/miss) out of ~50 s total read time.

### How much is held twice

Cgroup `memory.stat` inside this container only exposes the whole-VM numbers (`cache`/`rss` here are the container root, no per-process split); `free` after runs:

- `evict`, base/dart: page cache ≈ 0.4–0.5 GB at the end of a run (just jars, and the log), JVM resident 3.5 GB – O_DIRECT reads add nothing to it, as intended.
- `warmed`, dart: page cache 1.7–1.9 GB before *and* after (the same pages), and the memory cache fills with the 1.3 GB read from it ⇒ everything the memory cache held from that path is duplicated while it stays in both; the page cache copy is reclaimable under pressure, the memory cache's isn't.
- Live node: 7.2–9.7 GB page cache after load, of which a 1.5 GB memory cache would overlap only what it ends up holding.

I did not measure per-file overlap directly (a `cachestat` sweep over the held pages at the end of a run would give it exactly – cheap to add).

### Noise

Each cell is one JVM; repeating shows cold Q1 at 3g moves ±1–1.5 s (base evict 13.2–14.5, dart warmed 12.7–14.6, base warmed 13.8–15.9), and at 6g by much more (base warmed 29.0, dart evict 18–19, dart warmed 27.5–31.1). Hot medians move ±0.5 s. Read cold deltas < 2 s at 3g as noise.

## Invariants

- Cold read doesn't add to the page cache: held for the cachestat/mincore probes (verified: 2 page-cache hits out of 13 986 on `evict`, and `buff/cache` doesn't grow). **Broken by `preadv2(RWF_NOWAIT)`** (readahead on EAGAIN) – the user's "I3" as stated.
- Budget/eviction/holding/OOM: untouched (the change is inside the I/O lambda of `readPage`).
- Short/failed reads: probe failure (any syscall error, ENOSYS) ⇒ "not resident" ⇒ today's path. The buffered read is the existing `readFully` loop (throws on short read). A race between probe and read can make the buffered read block on the device; it can't return a wrong page.
- No-O_DIRECT filesystems: unchanged (the existing fallback is already the buffered read; a probe that says "resident" just goes to the same read).

## Sub-goals that would complete the card (each small)

1. **tidy** – extract `readPage`'s I/O into a `PageReader` seam (`read(path, slice, dest)`) so the direct/buffered choice isn't inlined in `withContext`. Equivalence change; no behaviour.
2. **advance** – `PageCacheProbe.isResident` over `cachestat`, with ENOSYS ⇒ false; one test on a real file (evict with `posix_fadvise(DONTNEED)`, read some pages, assert resident/not at page granularity) and a Linux-only guard.
3. **advance** – `MemoryCache` page-cache branch: resident ⇒ buffered read, else today's path; test with a file warmed/evicted via the probe's own fadvise, asserting the cold read leaves `cachestat` at zero (the invariant) and the warm read returns identical bytes.
4. **advance (optional)** – count hits/misses as meters (`memory-cache.pageCacheReads`/`directReads`) so a deployment can see whether it helps.

Sub-goals 2–3 only make sense if the open question below is answered "yes".

## Open questions

- **Q-benefit (blocks 2–4):** is there a device/host on which cold Q1 is I/O-bound enough for this to matter? On this container (fast virtio, iowait ≈ 3% of cold Q1) it's within noise. The real target is a remote-pool node whose disk cache is on network storage, or a bench box with a slow disk – measure there (and with `blkio` throttle below 250 MB/s) before building.
- **Q-regression:** the card's 11.2 s → 17.8 s cold Q1 doesn't reproduce here (main 18–30 s, base 13–15 s at 3g). Where do the 11.2/17.8 come from – a different disk, or `main` at a different data layout? The part I *can* say: at 6g all base/dart variants are 1.5–2× slower on cold Q1 than at 3g and the time is JVM sys CPU (16 s vs 5 s), not I/O. That deserves its own dart (first-touch/zeroing of the larger cache? THP? `-Xms5g` pretouch?), because it matches "cache that fits" slow cold Q1 better than the page cache does.
- **Q-kernel-floor:** `cachestat` needs Linux ≥ 6.5. Below that the dart silently degrades to today's behaviour. Is that acceptable, or do we want `mincore` as a fallback (it needs a long-lived per-file mapping to avoid the mmap_lock cost I measured – more state, a Monitor/lifecycle object per file)?
- **Q-granularity:** all-or-nothing per page. A page that is, say, 90% resident goes entirely to O_DIRECT. Fine for 1–4 MB pages; not decided for the large ones.

## Assumptions and risks

- One JVM per cell; JIT/first-run noise dominates cold. 3g results have ±1.5 s noise; 6g far more.
- The probe→read window: an eviction in between yields a blocking buffered read (still correct; rare; adds to the page cache by that page only).
- `evict`/`warmed` use `drop_caches` (everything, incl. jars) rather than per-file fadvise.
- The bench's "warmed" state was made by running `main` once (its mmap reads populate only what Q1/Q6 touch). It is the card's "JVM restart, host warm" scenario only for `main`; a base/dart run leaves the page cache as it found it.
- `sync`ed before eviction; `posix_fadvise(DONTNEED)` doesn't drop dirty pages (a fresh `dd` file looks 100% resident until synced – caught me in the first probe).
- Each `isResident` does an `open`/`close` plus a syscall per miss (~25–60 µs). A per-file fd cache would halve it; not worth it at this scale.
- `Linker`/`syscall` is Linux-x86_64-ish by convention (syscall number 451 is the same on x86_64 and arm64 for cachestat); non-Linux needs a guard.

## Where things are

Branch `dart/page-cache-read` on the fork. Run scripts and raw logs are outside the repo (`/home/user/{run,go,cpu,matrix}.sh`, `/home/user/logs/*`) and will not survive the container; the tables above are the record.
