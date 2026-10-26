# Dart: MemoryCache page fetch (#4963 / #6285) — WIP

Branch `dart/memory-cache-fetch` = xtdb main 53dfd29 + harness (2 commits) + dart commits.
Box: 4 vCPU, 15GB; SF1 RA, `-PnarrowVarTypes`, 50 hot reps, drop 20; bench verifies results against pinned.

## Variants (system properties, `-PextraJvmArgs`)
- `legacy` — `-Dxtdb.memoryCache.legacy=true`: main as-is (fetch loop, `Arena.ofShared` per page, closed on release)
- `retained` — default: cache holds a ref on every mapped slice; LRU-evicts entries only the cache holds (refCnt==1) when `cacheAl` would exceed `maxSizeBytes`; misses load on the caller's coroutine; concurrent misses dedup on a per-key `Loading` deferred
- `noretain` — `-Dxtdb.memoryCache.noRetain=true`: caller-thread load, no fetch loop, but slice unmapped when the last user releases it (isolates route 2 from route 1)
- `seq` — `retained` + `-Dxtdb.scan.seqPageLoad=true`: `ScanCursor` loads a task's pages sequentially, no `async`/`awaitAll` (route 3)

## Numbers (runs.py --drop 20, verbatim)
```
legacy-r1.log        q1   cold  12490 n  30 median    8692 mean    8755 min   8033 sd    393
legacy-r1.log        q6   cold   6917 n  30 median    5896 mean    5956 min   5541 sd    277
retained-r1.log      q1   cold   8654 n  30 median    4490 mean    4498 min   4270 sd    148
retained-r1.log      q6   cold   2321 n  30 median    1816 mean    1819 min   1619 sd     78
```

## Main thread on/off-CPU (/proc, hot reps ≥20; `dart-tools/proc.py`)
```
legacy-r1            q1   n  30 main on-CPU  64.3%  volCtxSw/run   22642
legacy-r1            q6   n  29 main on-CPU  56.6%  volCtxSw/run   21295
retained-r1          q1   n  30 main on-CPU  91.8%  volCtxSw/run       3
retained-r1          q6   n  30 main on-CPU  91.8%  volCtxSw/run     134
```
