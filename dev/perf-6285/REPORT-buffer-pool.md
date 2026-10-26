# Dart: explicit buffer pool for data pages (#4963 / #6285)

Branch `dart/buffer-pool` = xtdb main 7348ce5 + harness (2 commits) + dart commits.
The code is `core/src/main/kotlin/xtdb/cache/PagePool.kt`, enabled by `-Dxtdb.pagePool.bytes=<size>`.
Box: 4 vCPU / 15GB, JDK 21, ext4 on virtio. SF1 RA, `-PnarrowVarTypes`, 50 hot reps, drop 20, one bench at a time, OS page cache dropped before every run (`drop_caches` works here).
Every run passed the bench's pinned-result check.
Run with `dev/perf-6285/dart-tools/ab.sh <tag> <variant>...`; the variants are defined in the script.

## tl;dr
- **Goal reached when the pool fits the hot set**: a hit is a map lookup plus a CAS pin on the caller's thread, and pages are pread with O_DIRECT, so each page is held once (in the pool)
  - Q1 4404 → 2316ms (−47%), Q6 2903 → 950ms (−67%)
  - main thread on-CPU 72%/66% → 99%; voluntary context switches 24K/21K per run → ~3
  - cgroup page cache 1.8–2.4G → 0.5–1.0G
- **Below the hot set, still faster than legacy despite low hit rates**, because the per-fetch arena create/close and handshake are gone
  - region 1G (≈40% of the hot set): −24%/−33%
  - arrow 1.5G: −33%/−44% at a 60% hit rate
- **Exact-size frames (`arrow` mode) beat power-of-two frames (`region` mode) under churn**
  - hot set is 1.98G as exact frames vs 2.51G as buddy frames
  - same 1.5G budget: 59.7% hits vs 26.7%
  - no buddy fragmentation
- **Today's main is much faster than the previous dart's baseline** (legacy Q1 4.4s vs 8.9s then), so absolute numbers don't compare across the two reports

- **Round 3 (trimmed buddy)**: 2.8% waste instead of 34% when the pool fits, but under churn it fragments (storms of up to ~10K evictions per allocation), and the hit rate falls below plain buddy. Address-aware eviction is the missing piece.
- **Arrow mode won't land** (orchestrator, after the user rejected the power-of-two waste): Netty's size classes hide roughly 10% inside it. Its numbers are kept for the record.

## Decisions from the user mid-run (relayed to the orchestrator)
- **D1: no double-caching**: pread through the OS page cache is out; O_DIRECT stays. `p1pc` is reference only.
- **D2: no bypass past the limit**: serving a miss from outside the pool when no frame can be freed breaks the memory bound. The direction is to fail loudly; waiting for an unpin is a follow-up (see Q1).
- **D3: try enforcing the limit through the Arrow allocator**: built as `arrow` mode.
  - allocating everything at startup is not required
  - memory that is never returned to the OS is acceptable
- **D4: fewer JVMs per variant**: the effects (25–70%) dwarf the JVM-to-JVM noise (±7%), so round 2 used 1 JVM per variant.

## Design as built
- **One pool per JVM**: a lazy singleton `PagePool.INSTANCE` configured by system properties.
  - departure from the brief: not owned by the node, never closed (the dart's shortcut)
- **Two frame modes**
  - `region` (default)
    - one `Unsafe.allocateMemory` region carved by a buddy allocator into power-of-two frames, 4KiB–64MiB
    - not `Arena.allocate`: **FFM native segments do count against `-XX:MaxDirectMemorySize` on JDK 21** (verified: `Bits.reserveMemory` from `NativeMemorySegmentImpl.makeNativeSegment`); Unsafe keeps the pool outside the legacy direct budget
  - `arrow` (`-Dxtdb.pagePool.mode=arrow`, D3)
    - each frame is `frameAl.buffer(n)` from a `RootAllocator(limit = pool size, identity RoundingPolicy)`; Arrow's default policy would round sub-16MiB requests up to a power of two
    - a full pool surfaces as `OutOfMemoryException` → evict one frame → retry
    - netty counts frames against MaxDirectMemorySize, so the arrow variants run with MaxDirectMemorySize = 3g + pool size
- **Page lookup**: `ConcurrentHashMap<(path, offset), Frame>`
  - **hit**: lookup → CAS pin (`pins >= 0`, `-1` = evicted) → bump a saturating 0–3 frequency
  - **miss**: `putIfAbsent` a loading placeholder (concurrent misses await the same `CompletableDeferred`) → allocate a frame (evicting under the pool lock) → `withContext(IO.limitedParallelism(8))` O_DIRECT pread into the frame
  - O_DIRECT reads cover the enclosing 4KiB-aligned range; the page sits at `frame + (offset − alignedOffset)`
- **Arrow hand-off**
  - each pin wraps `wrapForeignAllocation` over the frame address on a separate unlimited root allocator; `release0` unpins
  - `ExtensibleBuffer.loadBuffer`/`openSlice` and the `BitBuffer` equivalents call `adoptInto`: for pool buffers they `retain` rather than `transferOwnership`, so no accounting reaches query allocators (the previous dart's leak)
  - departure: the brief wanted a hit to allocate nothing; a hit allocates one small ledger and ArrowBuf
- **Eviction**: S3-FIFO, byte-weighted
  - small FIFO (10%), main FIFO, ghost of ~4× capacity/64KiB keys
  - unpinned frames only, by CAS `0 → -1`
  - `incumbent` admission variant: once main is full, a ghost hit only displaces a cold head, and main's frequencies halve periodically
- **Gauges**: stderr only — hits, misses, evictions, bypasses, alloc failures, resident/small/main bytes, frames, pinned, readBytes/readMs, `frameAl` bytes
  - not Micrometer: a dart shortcut
- **Remote storage**: not wired; `RemoteBufferPool` still goes through `MemoryCache`
  - with pread into the pool, the disk-cache pin only needs to last for the read, not for as long as the page is resident

## Numbers — runs.py --drop 20 (verbatim)
Round 1: 3 JVMs per variant, interleaved.
```
legacy-r1.log        q1   cold   9125 n  30 median    4492 mean    4512 min   4393 sd     75
legacy-r1.log        q6   cold   3285 n  30 median    2986 mean    2993 min   2894 sd     52
legacy-r2.log        q1   cold   5761 n  30 median    3930 mean    3940 min   3848 sd     60
legacy-r2.log        q6   cold   3029 n  30 median    2851 mean    2868 min   2772 sd     64
legacy-r3.log        q1   cold   7236 n  30 median    4418 mean    4436 min   4257 sd    113
legacy-r3.log        q6   cold   3335 n  30 median    2910 mean    2919 min   2807 sd     75
fit-r1.log           q1   cold   7634 n  30 median    2316 mean    2327 min   2263 sd     46
fit-r1.log           q6   cold   1122 n  30 median     906 mean     911 min    884 sd     21
fit-r2.log           q1   cold   5620 n  30 median    2081 mean    2096 min   2042 sd     45
fit-r2.log           q6   cold   1186 n  30 median     971 mean     974 min    941 sd     19
fit-r3.log           q1   cold   5062 n  30 median    2348 mean    2352 min   2277 sd     32
fit-r3.log           q6   cold   1182 n  30 median     950 mean     951 min    910 sd     24
p15-r1.log           q1   cold   7344 n  30 median    3299 mean    3296 min   2860 sd    158
p15-r1.log           q6   cold   2429 n  30 median    1822 mean    1841 min   1509 sd    175
p15-r2.log           q1   cold   5765 n  30 median    3626 mean    3600 min   3153 sd    210
p15-r2.log           q6   cold   2318 n  30 median    1875 mean    1862 min   1501 sd    142
p15-r3.log           q1   cold   4748 n  30 median    2882 mean    2883 min   2457 sd    222
p15-r3.log           q6   cold   2035 n  30 median    1756 mean    1742 min   1323 sd    180
p1-r1.log            q1   cold   5461 n  30 median    3422 mean    3429 min   3225 sd    148
p1-r1.log            q6   cold   2298 n  30 median    1978 mean    1977 min   1776 sd    112
p1-r2.log            q1   cold   5252 n  30 median    3474 mean    3426 min   3122 sd    138
p1-r2.log            q6   cold   2156 n  30 median    1976 mean    1959 min   1744 sd     84
p1-r3.log            q1   cold   4352 n  30 median    2980 mean    2987 min   2674 sd    137
p1-r3.log            q6   cold   2167 n  30 median    1906 mean    1897 min   1578 sd    126
p1pc-r1.log          q1   cold   7467 n  30 median    3110 mean    3180 min   2896 sd    228
p1pc-r1.log          q6   cold   2074 n  30 median    1660 mean    1739 min   1497 sd    205
p1pc-r2.log          q1   cold   7397 n  30 median    3150 mean    3250 min   2996 sd    261
p1pc-r2.log          q6   cold   2026 n  30 median    1726 mean    1808 min   1551 sd    224
p1pc-r3.log          q1   cold   5764 n  30 median    2904 mean    2997 min   2715 sd    198
p1pc-r3.log          q6   cold   1919 n  30 median    1634 mean    1707 min   1404 sd    256
```
Round 2: 1 JVM per variant (D4).
Region `p1`/`p15` here include the eviction-stall fix (see Dead-ends); `*inc` is the incumbent admission variant.
```
legacy-r4.log        q1   cold   7176 n  30 median    4390 mean    4416 min   4286 sd     87
legacy-r4.log        q6   cold   2962 n  30 median    2896 mean    2904 min   2812 sd     61
p1-r4.log            q1   cold   5002 n  30 median    3236 mean    3240 min   3038 sd     80
p1-r4.log            q6   cold   2155 n  30 median    1933 mean    1954 min   1847 sd     75
p15-r4.log           q1   cold   5410 n  30 median    3158 mean    3168 min   2948 sd    142
p15-r4.log           q6   cold   2221 n  30 median    1679 mean    1706 min   1529 sd    133
fita-r4.log          q1   cold   5644 n  30 median    2336 mean    2344 min   2285 sd     39
fita-r4.log          q6   cold   1203 n  30 median     914 mean     921 min    899 sd     20
p1a-r4.log           q1   cold   5051 n  30 median    3156 mean    3194 min   2867 sd    245
p1a-r4.log           q6   cold   2521 n  30 median    1948 mean    2004 min   1774 sd    202
p15a-r4.log          q1   cold   5380 n  30 median    2956 mean    2987 min   2772 sd    161
p15a-r4.log          q6   cold   2764 n  30 median    1615 mean    1622 min   1478 sd     79
p1inc-r4.log         q1   cold   5039 n  30 median    3100 mean    3131 min   3056 sd     72
p1inc-r4.log         q6   cold   2181 n  30 median    1972 mean    1990 min   1924 sd     49
p15inc-r4.log        q1   cold   5411 n  30 median    3458 mean    3468 min   3337 sd     93
p15inc-r4.log        q6   cold   2152 n  30 median    1936 mean    1936 min   1808 sd     60
```

### Summary (median of per-JVM medians, ms; hit rate over hot reps ≥ 20)
| variant | JVMs | pool | Q1 | Q6 | ΔQ1 | ΔQ6 | hit% |
|---|---|---|---|---|---|---|---|
| legacy | 4 | – | 4404 | 2903 | | | – |
| fit (region) | 3 | 3G | 2316 | 950 | −47% | −67% | 100 |
| fita (arrow) | 1 | 3G | 2336 | 914 | −47% | −69% | 100 |
| p15 (region) | 3+1 | 1.5G | 3229 | 1789 | −27% | −38% | 25–28 |
| p15a (arrow) | 1 | 1.5G | 2956 | 1615 | −33% | −44% | 59.7 |
| p1 (region) | 3+1 | 1G | 3329 | 1955 | −24% | −33% | 7–17 |
| p1a (arrow) | 1 | 1G | 3156 | 1948 | −28% | −33% | 26.1 |
| p1pc (region, page cache; D1: reference only) | 3 | 1G | 3110 | 1660 | −29% | −43% | 16 |
| p15inc / p1inc | 1 / 1 | | 3458 / 3100 | 1936 / 1972 | | | 8.1 / 0.3 |

## Round 3 — buddy with tail trimming (orchestrator request, after the user rejected power-of-two waste)
- **Variant** (`-Dxtdb.pagePool.trim=true -Dxtdb.pagePool.arena=true -Dxtdb.pagePool.ratio=<r>`), 1 JVM each
  - allocate the order-k block as before, keep only `readLen` rounded up to 4KiB, and return the tail to the free lists as aligned power-of-two pieces (`alloc_pages_exact`-style)
  - on evict, free the used range as aligned power-of-two pieces, largest-first from the start, and let buddy coalescing merge
- **Region from `Arena.ofShared().allocate(...)`**: counts against MaxDirectMemorySize; the pool is sized as a ratio of max direct memory
  - bench JVM: MaxDirectMemorySize = 3g + pool, so Arrow keeps the same 3g headroom as legacy
  - `fitt` 6g × 0.5; `p15t` 4608m × ⅓; `p1t` 4g × 0.25
  - `Arena.allocate` zero-fills, so the whole region is resident from startup: `fitt` RssAnon 4.86G vs 3.64G for the Unsafe region, where only touched bytes are resident
```
fitt-r6.log          q1   cold   5087 n  30 median    2347 mean    2346 min   2273 sd     49
fitt-r6.log          q6   cold   1130 n  30 median     966 mean     969 min    931 sd     22
p15t-r6.log          q1   cold   3894 n  30 median    2938 mean    2905 min   2363 sd    192
p15t-r6.log          q6   cold   2031 n  30 median    1838 mean    1827 min   1198 sd    202
p1t-r6.log           q1   cold   4550 n  30 median    3076 mean    3079 min   2990 sd     49
p1t-r6.log           q6   cold   2182 n  30 median    1924 mean    1925 min   1860 sd     40
fitt-r6              q1   n  30 main on-CPU  98.8%  volCtxSw/run       4
fitt-r6              q6   n  29 main on-CPU  98.8%  volCtxSw/run       1
p15t-r6              q1   n  30 main on-CPU  76.3%  volCtxSw/run    4349
p15t-r6              q6   n  29 main on-CPU  70.5%  volCtxSw/run    3291
p1t-r6               q1   n  30 main on-CPU  74.5%  volCtxSw/run    5021
p1t-r6               q6   n  29 main on-CPU  69.5%  volCtxSw/run    3664
run             RssAnon  RssFile  cgUsage   cgRss  cgCache cgMapped  actFile inactFile  workSet |   hit%     hits   misses    evict readMB/s
fitt-r6           4.86G    0.03G   10.93G  10.05G    0.87G    0.22G    0.25G     0.61G   10.32G |  100.0   686320        0        0        0
p15t-r6           3.31G    0.03G    9.32G   8.49G    0.82G    0.22G    0.41G     0.40G    8.92G |   15.7   108816   586434   581042      652
p1t-r6            2.85G    0.03G    8.80G   8.03G    0.77G    0.22G    0.31G     0.45G    8.35G |    3.8    27750   707576   702186      713
```

| | plain buddy (region, r4) | trimmed buddy (r6) | arrow (r4) |
|---|---|---|---|
| frame bytes / page bytes, hot set | 2.51G / 1.87G (+34%) | 1.92G / 1.87G (+2.8%) | 1.98G (+5.6%)¹ |
| fit Q1 / Q6 | 2316 / 950 (r1–3) | 2347 / 966 | 2336 / 914 |
| 1.5G: Q1 / Q6 / hit% | 3158 / 1679 / 26.7 | 2938 / 1838 / 15.7 | 2956 / 1615 / 59.7 |
| 1G: Q1 / Q6 / hit% | 3236 / 1933 / 7.2 | 3076 / 1924 / 3.8 | 3156 / 1948 / 26.1 |
| alloc failures (bypass) per run, 1.5G / 1G | 0 / 2 | 127 / 63 | 0 / 0 |

¹ the `frameAl` accounted bytes; netty's own size classes may hide more underneath (orchestrator's estimate ~10%; not measured here)

- **Fragmentation gauges** (trimmed buddy, whole run)

| | p15t | p1t |
|---|---|---|
| evictions / miss | 0.99 | 0.99 |
| allocs needing eviction / miss | 0.20 | 0.37 |
| max evictions in one alloc | 9,738 | 6,064 |
| mean free bytes when an alloc had to evict | 155MB (10% of pool) | 127MB (12%) |
| mean largest free block then | 193KB | 142KB |
| at close: free / largest free | 193MB / 64KB | 129MB / 64KB |
| alloc failures, all for the one 36MB page (order 26) | 127 | 63 |

- **Fixes the waste when the pool fits**: 2.8% over page bytes, and fit performance unchanged
- **Under churn it fragments badly**
  - 10–12% of the pool sits free in ≤ 200KB pieces at the moment an allocation needs to evict
  - eviction is in queue order, not address order, so freeing one frame rarely completes a block of the needed order: the allocator evicts in storms of up to ~10K frames, flushing warm main-queue frames with them
  - the hit rate ends up *below* plain buddy (15.7% vs 26.7% at 1.5G, 3.8% vs 7.2% at 1G), and the 36MB page fails at 63–127 per run, with 0.7–1.3GB free but no 64MiB block
- **Q1/Q6 under churn are similar to plain buddy** (Q1 a little faster, Q6 mixed): O_DIRECT miss cost dominates either way
- **What would make a buddy-family allocator work under churn**: address-aware eviction (choose a victim *block* of the needed order and evict the frames inside it, like the kernel's compaction or a segregated-fit allocator), not a FIFO of frames. Alternatively size-class slabs with per-class eviction (memcached-style), at the cost of cross-class rebalancing.

## Main thread on-CPU and context switches (`proc.py`, hot reps ≥ 20)
```
legacy-r1            q1   n  30 main on-CPU  73.6%  volCtxSw/run   24251
legacy-r1            q6   n  29 main on-CPU  66.4%  volCtxSw/run   20952
legacy-r2            q1   n  30 main on-CPU  71.4%  volCtxSw/run   23914
legacy-r2            q6   n  29 main on-CPU  65.6%  volCtxSw/run   21130
legacy-r3            q1   n  30 main on-CPU  73.7%  volCtxSw/run   24298
legacy-r3            q6   n  29 main on-CPU  66.7%  volCtxSw/run   20728
fit-r1               q1   n  30 main on-CPU  98.8%  volCtxSw/run       4
fit-r1               q6   n  30 main on-CPU  98.5%  volCtxSw/run       2
fit-r2               q1   n  30 main on-CPU  98.9%  volCtxSw/run       3
fit-r2               q6   n  29 main on-CPU  98.9%  volCtxSw/run       1
fit-r3               q1   n  30 main on-CPU  98.7%  volCtxSw/run       3
fit-r3               q6   n  29 main on-CPU  98.5%  volCtxSw/run       1
fita-r4              q1   n  30 main on-CPU  99.1%  volCtxSw/run       4
fita-r4              q6   n  29 main on-CPU  99.2%  volCtxSw/run       1
p15-r1               q1   n  30 main on-CPU  78.1%  volCtxSw/run    3889
p15-r1               q6   n  29 main on-CPU  71.2%  volCtxSw/run    2610
p15-r4               q1   n  30 main on-CPU  80.4%  volCtxSw/run    3880
p15-r4               q6   n  29 main on-CPU  73.9%  volCtxSw/run    2718
p15a-r4              q1   n  30 main on-CPU  85.1%  volCtxSw/run    2183
p15a-r4              q6   n  29 main on-CPU  79.2%  volCtxSw/run    1506
p1-r1                q1   n  30 main on-CPU  76.2%  volCtxSw/run    4389
p1-r1                q6   n  30 main on-CPU  66.5%  volCtxSw/run    3236
p1-r4                q1   n  30 main on-CPU  76.2%  volCtxSw/run    4775
p1-r4                q6   n  30 main on-CPU  69.8%  volCtxSw/run    3614
p1a-r4               q1   n  30 main on-CPU  79.1%  volCtxSw/run    3754
p1a-r4               q6   n  29 main on-CPU  73.1%  volCtxSw/run    2847
p1pc-r1              q1   n  30 main on-CPU  83.6%  volCtxSw/run    3838
p1pc-r1              q6   n  29 main on-CPU  76.2%  volCtxSw/run    2818
```
(full set: `~/bench/round{1,2}-proc.txt` on the dart box; the rows above are representative)
- **When the pool fits**: no off-CPU time left on the fetch path
- **Under churn**: about 1 switch per 3–4 misses; the main thread parks while O_DIRECT reads run on the IO dispatcher
  - legacy pays ~1 switch per page fetch even when everything is resident

## Memory — process RSS and container cgroup (v1, root `total_*`; median of 2s samples over hot reps ≥ 20)
`cgRss` includes the Gradle and Kotlin daemons (~5G); read the deltas, not the absolutes.
`workSet` = usage − inactive_file.
```
run             RssAnon  RssFile  cgUsage   cgRss  cgCache cgMapped  actFile inactFile  workSet |   hit%     hits   misses    evict readMB/s
legacy-r1         1.84G    0.03G    9.33G   6.93G    2.40G    0.22G    1.54G     0.85G    8.48G |
legacy-r2         1.80G    0.03G    8.81G   6.99G    1.82G    0.22G    1.32G     0.48G    8.33G |
legacy-r3         1.82G    0.03G    8.87G   7.06G    1.81G    0.22G    1.32G     0.48G    8.39G |
fit-r1            3.65G    0.03G    9.77G   8.78G    0.99G    0.22G    0.29G     0.68G    9.09G |  100.0   703168        0        0        0
fit-r2            3.62G    0.03G    9.36G   8.82G    0.53G    0.22G    0.08G     0.44G    8.91G |  100.0   734440        0        0        0
fit-r3            3.64G    0.03G    9.43G   8.88G    0.54G    0.22G    0.08G     0.44G    8.98G |  100.0   686440        0        0        0
fita-r4           3.64G    0.03G    9.72G   9.12G    0.60G    0.22G    0.09G     0.50G    9.22G |  100.0   705948        0        0        0
p15-r1            3.34G    0.03G    9.75G   8.51G    1.24G    0.22G    0.56G     0.67G    9.09G |   28.5   209096   524432   521458      508
p15-r2            3.34G    0.03G    9.37G   8.53G    0.83G    0.22G    0.38G     0.44G    8.93G |   25.3   179146   527714   528585      492
p15-r3            3.34G    0.03G    9.36G   8.58G    0.78G    0.22G    0.38G     0.38G    8.98G |   26.4   182515   507587   507433      570
p15-r4            3.35G    0.03G    9.32G   8.79G    0.53G    0.22G    0.08G     0.44G    8.88G |   26.7   191451   524979   523804      560
p15a-r4           3.78G    0.03G    9.72G   9.25G    0.47G    0.22G    0.08G     0.38G    9.34G |   59.7   414981   280533   279037      362
p1-r1             2.84G    0.03G    8.88G   8.04G    0.85G    0.22G    0.39G     0.44G    8.44G |   15.9   113722   601060   603561      583
p1-r2             2.85G    0.03G    8.88G   8.05G    0.83G    0.22G    0.38G     0.44G    8.44G |   15.2   108761   605143   605435      591
p1-r3             2.78G    0.03G    8.88G   8.03G    0.86G    0.22G    0.39G     0.45G    8.43G |   17.3   122762   588322   588525      639
p1-r4             2.85G    0.03G    8.80G   8.26G    0.54G    0.22G    0.08G     0.45G    8.35G |    7.2    50204   648172   643541      661
p1a-r4            3.30G    0.03G    9.27G   8.74G    0.54G    0.22G    0.08G     0.45G    8.83G |   26.1   183197   519837   519241      537
p1pc-r1           2.85G    0.03G   10.40G   8.05G    2.35G    0.22G    1.76G     0.57G    9.82G |   16.3   119999   617035   612958      622
p1pc-r2           2.84G    0.03G   10.43G   8.08G    2.35G    0.22G    1.76G     0.57G    9.86G |   15.7   108343   582170   583144      610
p1pc-r3           2.81G    0.03G   10.23G   8.04G    2.19G    0.22G    1.75G     0.42G    9.81G |   16.3   113765   583056   581735      657
p1inc-r4          2.85G    0.03G    8.51G   7.74G    0.77G    0.22G    0.21G     0.54G    7.97G |    0.3     2183   734499   727873      716
p15inc-r4         3.35G    0.03G    8.84G   8.24G    0.60G    0.22G    0.14G     0.44G    8.40G |    8.1    57083   646597   645310      610
```
- **Legacy pages live in the page cache** (`file`/`active_file`, ~1.3–1.5G): not anonymous memory, and not counted against the JVM
- **Pool pages are anonymous** (`RssAnon` +1.8G at fit): the cost is now visible and bounded, and the page cache drops to ~0.5G (not ours)
- **Region mode only makes touched bytes resident**: the power-of-two tail of a frame is never written
  - so `fit` RSS = `fita` RSS (3.64G) even though the buddy holds 2.51G of frames vs arrow's 1.98G
  - rounding costs capacity (hit rate), not RSS
- **Arrow mode is fully resident**: allocations are touched/committed, so `p15a` RssAnon 3.78G vs `p15` 3.35G

## Miss cost (D1)
- **Per miss**: ~180µs per ~150KB page with O_DIRECT (`readMs`/`misses`: p1 189s / 1.06M) vs ~66µs through the page cache (`p1pc`: 70s / 1.05M)
  - this virtio disk may be host-cached; real EBS/NVMe under load could be slower
- **IO throughput**: 500–700 MB/s sustained during churn, 8-way IO parallelism

## Data structures that matched / didn't
- **Matched**
  - `ConcurrentHashMap` + CAS pin per frame: the hit path does no locking, and the loading placeholder dedups concurrent misses for free
  - a pin as a refcount on the frame with `-1 = evicted`: CAS `0 → -1` under the pool lock is the only eviction gate, so a hit racing an eviction just retries
  - `retain` instead of `transferOwnership` for pool buffers: no accounting moves across allocators, which removes the previous dart's bounding problem
  - Arrow `RootAllocator` with a limit as the pool's allocator (`arrow` mode): the limit is enforced by Arrow, there's no fragmentation, and frames are exact-size; at a given budget the best hit rates of all variants
- **Didn't**
  - **buddy allocator**
    - power-of-two frames make the hot set 30% larger (capacity, not RSS)
    - large pages (one 36MB page in the SF1 data) need an aligned 64MiB block; 10 scattered pins block coalescing even after evicting 526 frames, the remaining 2 alloc failures in `p1-r4`
  - **S3-FIFO on a loop**: Q1+Q6 scan the same 12.8K pages twice per rep; any FIFO/CLOCK keeps roughly capacity × 0.5 of a loop
    - 26–28% hits at 60% capacity (region), 60% at 81% (arrow)
  - **incumbent admission**: simulated +25% relative hits, measured far worse (0.3% / 8%). Not chased; the likely culprit is the rotate-on-reject reordering main so the eviction hand hits recently admitted frames. Dead end as built.
  - **4KiB O_DIRECT alignment slack in `arrow` mode** (`readLen + 4KiB` per frame, since Arrow can't allocate aligned)
    - negligible for ~150KB pages
    - dominant for tiny pages: the test suite's 54MB of pages took 90MB in arrow mode vs 63MB in region mode

## Tests
- `:xtdb-core:test --tests 'xtdb.cache.*'`: pass (MemoryCacheTest 3, DiskCacheTest 1); the pool is off by default, so this covers `MemoryCache` only
- `property-test --tests '*CacheSimulationTest*'` (core): 700/700 pass
  - Gradle reports FAILED only because other modules have no matching tests
- with the pool forced on (`JAVA_TOOL_OPTIONS=-Dxtdb.pagePool.bytes=256m`)
  - `:xtdb-core:test`: 613/613 pass
  - Clojure `xtdb.operator.scan_test*`, `xtdb.compactor_test*`, `xtdb.compactor.*`, `xtdb.indexer_test*`, `xtdb.tpch_test*`: 85/85 pass in both region and arrow modes
    - the pool genuinely served them: 8.2K misses, 5.7K hits
    - needed the local `authn_test` Keycloak try/catch patch, not committed
- not run: the full `./gradlew test`, integration tests (remote storage isn't wired), a property test that exercises `PagePool` itself

## Suggested sub-goals (each one tidy or one advance; dependencies in brackets)
- **T1** tidy: `adoptInto` — buffers that may be shared (pool-owned) are retained, not ownership-transferred, at the four `loadBuffer`/`openSlice` sites; behaviour-preserving while nothing is pool-owned
- **T2** tidy: give `BufferPool` a page-read seam (`readPage(path, offset, len, al): ArrowBuf`) that `LocalStorage.getRecordBatch` and `RemoteBufferPool.getRecordBatch` call; `MemoryCache` stays behind it
- **A1** advance: `PagePool` (region from an FFM arena sized as a ratio of max direct memory, exact-ish frames, S3-FIFO, CAS pins, loading placeholder, O_DIRECT pread on an IO dispatcher) behind config, `LocalStorage` only; fails with an `xtdb.error` anomaly when nothing can be evicted (Q1) [T1, T2, Q4]
  - includes Micrometer gauges: pool bytes, resident, pinned frames, hits, misses, evictions, alloc failures, read-latency timer
- **A2** advance: `RemoteBufferPool` reads through the pool; the disk-cache pin is held only for the pread [A1]
- **A3** advance: make the pool the default and delete `MemoryCache` (fetch loop, `openSlices`, per-slice arenas) [A1, A2, Q2]
- **T3** tidy: `CacheSimulationTest`-style deterministic simulation for `PagePool` (interleave hits, misses, pin/unpin, eviction; invariants: resident ≤ limit, no evicted frame pinned, no leaked ledgers at close) [with A1]
- **A4** advance (optional): prefetch the merge plan's next tasks' pages to overlap O_DIRECT latency under churn [A1]
- **A5** advance (optional): a scan-resistant policy that keeps a stable fraction of a loop (LIRS / CLOCK-Pro), measured against the S3-FIFO numbers above [A1]
- **Incremental route from today's `MemoryCache`**: T1 → T2 (both no-ops behaviourally) → A1 behind a flag → A2 → A3
  - the previous dart's A2 (caller-thread load, no fetch loop) becomes unnecessary if A1 lands first

## Invariants touched
- **Memory accounting**: pool bytes are owned by the pool's allocator, and query allocators are never charged for page bytes
  - pages are now anonymous RSS rather than page cache, so what k8s counts as the working set changes from `file` to `anon`
- **Page buffers are read-only**: pool buffers are shared across readers via `retain`, so a write into a loaded `Relation` would corrupt other readers (today it would fault on the read-only mmap). Nothing writes into loaded pages today; a `PagePool` landing should assert it.
- **Files are immutable per key**: `(path, offset)` keys assume a file is never rewritten in place (true today, as far as I know)
  - FileChannels are cached per path and never closed: deleted (GC'd) files keep their disk space until shutdown. The landed version needs to close the channel on last-frame eviction, or open per read.
- **Concurrency**: one pool lock covers allocation, eviction and queue moves (misses only); hits never take it

## Risks
- **Every pin allocates a ledger** (`wrapForeignAllocation`): small, but it's on the hit path; a per-frame cached ArrowBuf with Arrow's own refcount as the pin would avoid it, at the cost of the "retain on a released ledger" race that `MemoryCache.getOpenSlice` already tolerates
- **Miss latency is real IO now**: on slow or remote-backed volumes the undersized pool will look worse than here; A4 is the mitigation
- **Arrow mode is fully committed memory**, sized to the pool; not returning memory to the OS was accepted (D3)
- **The pool's own lock under concurrent queries** is unmeasured (one query at a time here)

## Questions
- **Q1: when every frame is genuinely pinned** (never observed here after the eviction fix; pinned peaked at ~10–12 frames)
  - recommendation (orchestrator agrees): fail loudly with an anomaly by default
  - wait-for-unpin as a follow-up, behind a per-query pin quota sized to the scan's parallelism so a query can never pin more than its share
- **Q2: how to size the pool**: a fraction of container memory, or explicit? Today `MemoryCache`'s default is 0.5 × MaxDirectMemory, and arrow-mode frames live under the same netty limit — share it, or carve it out?
- **Q4: which frame allocator inside the region**: trimmed buddy needs address-aware eviction to survive churn; size-class slabs need rebalancing. Worth a dart of its own: measure fragmentation gauges (above) against the trimmed-buddy baseline.
- **Q3: is a loop-resistant policy worth it**, given today's workload is repeated full scans? Prefetch (A4) may matter more than the policy.

## Dead-ends / notes
- **O_DIRECT short read at EOF**: the second `read` arrives at an unaligned position → `IOException` in compaction; a short read is now treated as EOF
- **S3-FIFO stall**: when small's frames were all pinned and small was over its 10% share, the loop rotated small forever and never fell through to main (4 pinned 36MB frames vs 7.8K evictable in main)
  - caused all ~280/550 bypasses per run in region p15/p1 round 1, and an OOM in arrow mode
  - fixed: after a full pass of pinned skips in small, use main
  - this changed region p1's behaviour: hits 15–17% → 7%, yet Q1 3422 (r1–3 median) → 3236ms; not chased
- **Bypass** (uncached read from the storage allocator when no frame is available): removed from the design (D2); still in region mode for >64MiB pages and fragmentation failures
- the previous dart's `noretain` variant was not re-run on today's main; legacy on today's main is the baseline
- **first SF1 load**: 3m43s on this box (was ~10 min in the brief)
