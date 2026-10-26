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
legacy-r2.log        q1   cold  12655 n  30 median    8876 mean    8926 min   8335 sd    343
legacy-r2.log        q6   cold   6667 n  30 median    5861 mean    5917 min   5573 sd    202
noretain-r1.log      q1   cold  12121 n  30 median    7188 mean    7160 min   6724 sd    223
noretain-r1.log      q6   cold   5004 n  30 median    4307 mean    4311 min   4000 sd    166
noretain-r2.log      q1   cold   9043 n  30 median    6940 mean    6961 min   6503 sd    305
noretain-r2.log      q6   cold   4720 n  30 median    4272 mean    4300 min   3897 sd    181
retained-r1.log      q1   cold   8654 n  30 median    4490 mean    4498 min   4270 sd    148
retained-r1.log      q6   cold   2321 n  30 median    1816 mean    1819 min   1619 sd     78
retained-r2.log      q1   cold   9238 n  30 median    4470 mean    4497 min   4100 sd    180
retained-r2.log      q6   cold   2329 n  30 median    1729 mean    1759 min   1577 sd    104
seq-r2.log           q1   cold  10174 n  30 median    4306 mean    4311 min   4063 sd    177
seq-r2.log           q6   cold   2236 n  30 median    1640 mean    1642 min   1493 sd     96
```

## Main thread on/off-CPU (/proc, hot reps ≥20; `dart-tools/proc.py`)
```
legacy-r1            q1   n  30 main on-CPU  64.3%  volCtxSw/run   22642
legacy-r1            q6   n  29 main on-CPU  56.6%  volCtxSw/run   21295
legacy-r2            q1   n  30 main on-CPU  65.4%  volCtxSw/run   22793
legacy-r2            q6   n  29 main on-CPU  57.4%  volCtxSw/run   21148
noretain-r1          q1   n  30 main on-CPU  78.3%  volCtxSw/run   13235
noretain-r1          q6   n  30 main on-CPU  73.9%  volCtxSw/run   11738
noretain-r2          q1   n  30 main on-CPU  76.6%  volCtxSw/run   13170
noretain-r2          q6   n  29 main on-CPU  72.2%  volCtxSw/run   11809
retained-r1          q1   n  30 main on-CPU  91.8%  volCtxSw/run       3
retained-r1          q6   n  30 main on-CPU  91.8%  volCtxSw/run     134
retained-r2          q1   n  30 main on-CPU  93.4%  volCtxSw/run       4
retained-r2          q6   n  30 main on-CPU  93.5%  volCtxSw/run       4
seq-r2               q1   n  30 main on-CPU  94.1%  volCtxSw/run       4
seq-r2               q6   n  30 main on-CPU  94.7%  volCtxSw/run       1
```
