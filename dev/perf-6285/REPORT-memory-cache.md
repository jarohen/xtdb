# Dart: MemoryCache page fetch (#4963 / #6285)

Branch `dart/memory-cache-fetch` = xtdb main 53dfd29 + harness (2 commits) + dart commits; code in `MemoryCache.getRetained`, toggles below.
Box: 4 vCPU / 15GB, JDK 21; SF1 RA, `-PnarrowVarTypes`, 50 hot reps, drop 20, one bench at a time; every run verified against pinned results.

## tl;dr
- Goal met: a hot page fetch is a locked map lookup + `retain` on the caller's thread — no arena create/close, no thread hop
  - Q1 8.94s → 4.20s (−53%), Q6 5.88s → 1.81s (−69%); main thread on-CPU 64%/56% → 91–94%, voluntary ctx switches 22.7K/21.2K per run → ~0
- **But only if the cache can hold the hot working set**: Q1+Q6's lineitem pages are 1.84GB (12.8K slices) > the default limit 1.5GiB (0.5 × MaxDirectMemory 3g)
  - with a truthful byte bound at 1.5GiB, LRU over a sequential scan misses 100%, and `retained` collapses to `noretain` (Q1 7.17s / Q6 4.28s)
  - so the real decision is the **bounding policy** for mmap'd bytes (Q1), not the fetch mechanics
- Splits: route 2 (caller-thread load, no fetch loop) ≈ −22%/−27%; route 1 (keep the mapping) the rest ≈ −31%/−42% further; route 3 (no `async` per merge task) 0–5%, within noise

## Variants (`-PextraJvmArgs`)
- `legacy` — `-Dxtdb.memoryCache.legacy=true`: main as-is
- `noretain` — `-Dxtdb.memoryCache.noRetain=true`: caller-thread load, per-key `Loading` dedup, no fetch loop/channel; slice still unmapped (shared-arena close) when last user releases
- `retained` — default: cache holds one ref per mapped slice; LRU-evicts entries only it holds (refCnt==1) when resident bytes would exceed `maxSizeBytes`
  - r1–r3 `retained`/`seq` ran with an accounting bug (bound checked against `cacheAl.allocatedMemory`, which excludes slices Arrow had moved to query allocators) — effectively unbounded, 0 evictions; same numbers as `retbig`
  - r4 `retained`/`seq` = honest bound at 1.5GiB (thrashes)
  - `retbig`/`seqbig` = `-Dxtdb.memoryCache.maxBytes=6000000000`
- `seq*` — + `-Dxtdb.scan.seqPageLoad=true`: `ScanCursor` loads a task's pages sequentially in `runBlocking`, no `async`/`awaitAll`

## Summary (median of per-JVM hot medians, ms)
| variant | JVMs | Q1 | Q6 | Δ Q1 | Δ Q6 |
|---|---|---|---|---|---|
| legacy | 4 | 8940 | 5879 | | |
| noretain | 3 | 6940 | 4272 | −22% | −27% |
| retained @1.5GiB honest (r4) | 1 | 7166 | 4279 | −20% | −27% |
| retained, fits (r1–3, retbig r5–6) | 5 | 4200 | 1812 | −53% | −69% |
| seq, fits (r2–3, seqbig r6) | 3 | 4213 | 1700 | −53% | −71% |

Cache stats at close, `retbig`: `peakResident=1838626727 entries=12829 misses=12829 evictions=0` (one miss per slice, over 50+ reps of Q1+Q6).

## runs.py --drop 20 (verbatim)
```
legacy-r1.log        q1   cold  12490 n  30 median    8692 mean    8755 min   8033 sd    393
legacy-r1.log        q6   cold   6917 n  30 median    5896 mean    5956 min   5541 sd    277
legacy-r2.log        q1   cold  12655 n  30 median    8876 mean    8926 min   8335 sd    343
legacy-r2.log        q6   cold   6667 n  30 median    5861 mean    5917 min   5573 sd    202
legacy-r3.log        q1   cold  12065 n  30 median    9004 mean    9059 min   8479 sd    427
legacy-r3.log        q6   cold   6470 n  30 median    5854 mean    5866 min   5273 sd    282
legacy-r4.log        q1   cold  12397 n  30 median    9298 mean    9268 min   8503 sd    392
legacy-r4.log        q6   cold   7790 n  30 median    6188 mean    6273 min   5622 sd    394
noretain-r1.log      q1   cold  12121 n  30 median    7188 mean    7160 min   6724 sd    223
noretain-r1.log      q6   cold   5004 n  30 median    4307 mean    4311 min   4000 sd    166
noretain-r2.log      q1   cold   9043 n  30 median    6940 mean    6961 min   6503 sd    305
noretain-r2.log      q6   cold   4720 n  30 median    4272 mean    4300 min   3897 sd    181
noretain-r3.log      q1   cold   9328 n  30 median    6697 mean    6776 min   6403 sd    311
noretain-r3.log      q6   cold   4921 n  30 median    4218 mean    4250 min   3926 sd    153
retained-r1.log      q1   cold   8654 n  30 median    4490 mean    4498 min   4270 sd    148
retained-r1.log      q6   cold   2321 n  30 median    1816 mean    1819 min   1619 sd     78
retained-r2.log      q1   cold   9238 n  30 median    4470 mean    4497 min   4100 sd    180
retained-r2.log      q6   cold   2329 n  30 median    1729 mean    1759 min   1577 sd    104
retained-r3.log      q1   cold   7289 n  30 median    4170 mean    4146 min   3825 sd    152
retained-r3.log      q6   cold   2322 n  30 median    1812 mean    1810 min   1680 sd     58
retained-r4.log      q1   cold   6878 n  30 median    7166 mean    7165 min   6511 sd    351
retained-r4.log      q6   cold   4852 n  30 median    4279 mean    4324 min   3945 sd    205
retbig-r5.log        q1   cold   7069 n  30 median    4087 mean    4079 min   3773 sd    147
retbig-r5.log        q6   cold   2340 n  30 median    1720 mean    1725 min   1565 sd     81
retbig-r6.log        q1   cold   6328 n  30 median    4200 mean    4212 min   3786 sd    173
retbig-r6.log        q6   cold   2620 n  30 median    1824 mean    1838 min   1645 sd    124
seq-r2.log           q1   cold  10174 n  30 median    4306 mean    4311 min   4063 sd    177
seq-r2.log           q6   cold   2236 n  30 median    1640 mean    1642 min   1493 sd     96
seq-r3.log           q1   cold   7078 n  30 median    4087 mean    4088 min   3811 sd    163
seq-r3.log           q6   cold   2368 n  30 median    1700 mean    1711 min   1556 sd     75
seq-r4.log           q1   cold   8214 n  30 median    7240 mean    7329 min   6891 sd    337
seq-r4.log           q6   cold   5088 n  30 median    4240 mean    4272 min   3921 sd    217
seqbig-r6.log        q1   cold   6075 n  30 median    4213 mean    4250 min   3813 sd    197
seqbig-r6.log        q6   cold   2413 n  30 median    1764 mean    1766 min   1580 sd     93
```

## Main thread on/off-CPU (`/proc/<tid>/stat`+`status`, 2s samples interpolated per query stage, hot reps ≥20; `dart-tools/proc.py`)
```
legacy-r1            q1   n  30 main on-CPU  64.3%  volCtxSw/run   22642
legacy-r1            q6   n  29 main on-CPU  56.6%  volCtxSw/run   21295
legacy-r2            q1   n  30 main on-CPU  65.4%  volCtxSw/run   22793
legacy-r2            q6   n  29 main on-CPU  57.4%  volCtxSw/run   21148
legacy-r3            q1   n  30 main on-CPU  64.4%  volCtxSw/run   22711
legacy-r3            q6   n  29 main on-CPU  55.8%  volCtxSw/run   21272
legacy-r4            q1   n  30 main on-CPU  63.6%  volCtxSw/run   22684
legacy-r4            q6   n  30 main on-CPU  55.8%  volCtxSw/run   21158
noretain-r1          q1   n  30 main on-CPU  78.3%  volCtxSw/run   13235
noretain-r1          q6   n  30 main on-CPU  73.9%  volCtxSw/run   11738
noretain-r2          q1   n  30 main on-CPU  76.6%  volCtxSw/run   13170
noretain-r2          q6   n  29 main on-CPU  72.2%  volCtxSw/run   11809
noretain-r3          q1   n  30 main on-CPU  77.1%  volCtxSw/run   13215
noretain-r3          q6   n  29 main on-CPU  72.1%  volCtxSw/run   11791
retained-r1          q1   n  30 main on-CPU  91.8%  volCtxSw/run       3
retained-r1          q6   n  30 main on-CPU  91.8%  volCtxSw/run     134
retained-r2          q1   n  30 main on-CPU  93.4%  volCtxSw/run       4
retained-r2          q6   n  30 main on-CPU  93.5%  volCtxSw/run       4
retained-r3          q1   n  30 main on-CPU  94.2%  volCtxSw/run       3
retained-r3          q6   n  30 main on-CPU  93.6%  volCtxSw/run       2
retained-r4          q1   n  30 main on-CPU  77.4%  volCtxSw/run   13153
retained-r4          q6   n  30 main on-CPU  73.6%  volCtxSw/run   11939
retbig-r5            q1   n  30 main on-CPU  93.1%  volCtxSw/run       3
retbig-r5            q6   n  30 main on-CPU  93.3%  volCtxSw/run     134
retbig-r6            q1   n  30 main on-CPU  91.2%  volCtxSw/run       2
retbig-r6            q6   n  30 main on-CPU  90.7%  volCtxSw/run     111
seq-r2               q1   n  30 main on-CPU  94.1%  volCtxSw/run       4
seq-r2               q6   n  30 main on-CPU  94.7%  volCtxSw/run       1
seq-r3               q1   n  30 main on-CPU  92.6%  volCtxSw/run       3
seq-r3               q6   n  30 main on-CPU  92.7%  volCtxSw/run     127
seq-r4               q1   n  30 main on-CPU  75.7%  volCtxSw/run   13173
seq-r4               q6   n  30 main on-CPU  70.8%  volCtxSw/run   11839
seqbig-r6            q1   n  30 main on-CPU  91.8%  volCtxSw/run       3
seqbig-r6            q6   n  30 main on-CPU  92.1%  volCtxSw/run      34
```
- legacy ≈ orchestrator's profile (64%/57% on-CPU, ~21K switches) — confirms the box matches
- noretain still ~1 switch per page fetch: the remaining park is the `HandshakeAllThreads` VM op from `arena.close()` on release
- retained/fits: ~0 switches; the ~6–9% remaining off-CPU wall is not on the fetch path (GC/JIT/stage boundaries — not chased)
- the gain exceeds the off-CPU share: re-mmapping per page also cost on-CPU (page-table repopulation / minor faults on every touch, munmap TLB shootdown) — *inferred*: the orchestrator's 'unattributed ~13%' is gone with persistent mappings (not profiled directly)

## Compaction (load, n=1 each)
- legacy load: compact stage 126.2s; retained (fixed accounting, 1.5GiB): 104.3s (−17%)

## Data structure that matched
- `LinkedHashMap<PathSlice, Loading | Ready>` (access-ordered) under one lock; `residentBytes` counted by the cache itself
  - hit: lock → `Ready` → `retain` → return (no suspension)
  - miss: install `Loading(deferred)`; the installer fetches + maps on its own coroutine; others `await` then re-loop (a failed/cancelled load leaves the next waiter to retry)
  - eviction: under the lock, LRU-first, only `refCnt()==1` (callers can only retain under the lock, so the check is race-free); release → `release0` → `arena.close()` + `onEvict`
- `Arena.ofShared` per slice is kept: its close is now paid per eviction, not per use

## Finding: `cacheAl.allocatedMemory` can't bound a retained cache
- once a page is loaded into a `Relation`, `ExtensibleBuffer.loadBuffer` `transferOwnership`s it — accounting moves to the query/compaction allocator
- when that allocator's ledger releases, Arrow force-transfers ownership back to `cacheAl` (`AllocationManager.release` → `transferBalance`, `forceAllocate`) — outside the cache's lock
- consequences seen
  - compaction OOM during the first SF1 load: all 13,282 entries idle (refCnt 1, 1.61GB), but a concurrent ownership return pushed `cacheAl` over between `evictIdle` and `wrapForeignAllocation` (diag line: `entries=13283 ready=13282 idleBytes=1610943468 pinnedBytes=0 ... need=96904`)
  - eviction against `allocatedMemory` over-evicts (evicting a non-owning entry frees no `cacheAl` bytes) and under-bounds (slices owned elsewhere are uncounted)
- fix in this branch: `cacheAl` unlimited in retained mode; bound on the cache's own `residentBytes`

## Suggested sub-goals
- [T1] tidy: `MemoryCache` bounds its own resident bytes rather than via `cacheAl`'s limit; gauges report resident/pinned/evictable — needs Q1 answered first if the bound changes meaning
- [A1] advance: retained LRU cache (cache holds a ref, evicts idle-only) — after T1; the bulk of the gain, *conditional on Q1*
- [A2] advance: load misses on the caller's coroutine with per-key `Loading` dedup; delete the fetch loop/`FetchReq`/`FetchDone`/`openSlices` — independent of A1, −22%/−27% alone
- [T2] tidy: `CacheSimulationTest`/`MemoryCacheTest` re-expressed against the new invariants (resident ≤ max when idle; nothing mapped after close; no leaks) — with A1/A2
- [A3] advance (optional, weak): `ScanCursor` without per-task `async` when pages are local — 0–5%, not worth it alone
- order: A2 → T1 → A1 (+T2 alongside each)

## Invariants touched
- memory accounting: old `cacheAl` limit bounded mapped-and-in-use bytes; new bound is resident (cache-held) bytes; evicted-but-in-use slices stay mapped, accounted to the user's allocator (as before)
- `Stats`/gauges: `usedBytes` stays non-zero after release (retained); `freeBytes` meaningless (unlimited `cacheAl`) — **every** `MemoryCacheTest`/`CacheSimulationTest` failure is a `Stats` assertion
  - `:xtdb-core:test --tests 'xtdb.cache.*'`: 2/4 MemoryCacheTest fail (`usedBytes` 100 ≠ 0 after release; `freeBytes`)
  - `property-test --tests '*CacheSimulationTest*'`: 600/700 fail, all `Stats`; `deterministic oom handling` passes; no leak/exception failures
  - the sim's `yieldIfSimulation` interleaving points don't exist in the new path — interleaving coverage of it is weaker than it looks
- remote storage: `onEvict` (disk-cache pin) now closes on memory-cache eviction, not after each use — a slice in memory pins its disk-cache file for as long as it is resident; not exercised here (no remote bench)
- concurrency: dedup of concurrent misses kept; eviction-while-in-use safe via refCnt==1 under lock; ownership returns from other allocators are outside our lock (hence T1)

## Risks
- mapped bytes now stay resident up to `maxSizeBytes` (default 1.5GiB of a 3g direct budget) — they are file-backed page cache, not direct memory, so the "fraction of MaxDirectMemory" default no longer means anything
- `Arena.ofShared` per slice → ~12.8K live mappings for SF1 hot set; `vm.max_map_count` 65530 default → caps resident slices ~60K; larger SFs/limits would hit it (route: map per file, slice the segment)
- whole-file entries (`getByteArray`, `slice == null`) are now retained too; a file rewritten in place under the same key would be served stale (keys are immutable today, AFAIK)
- one global lock on every page fetch — uncontended at 1 query; not measured with concurrent queries

## Questions
- Q1: what should bound mmap'd, file-backed, read-only bytes? Options: (a) keep a direct-memory-derived limit, raise the ratio; (b) a separate config sized to the hot set; (c) no bound beyond map-count, rely on the OS page cache, evict on count/age. 1.84GB already overshoots 1.5GiB at SF1
- Q2: per-file mapping (one arena per file, slices as `asSlice`) would cut mapping count ~100× and make eviction per-file — worth it, given LRU granularity changes?
- Q3: should LRU be scan-resistant (e.g. 2Q/ARC/CLOCK-Pro)? With a bound below the hot set, plain LRU + sequential scans = 0% hits; a scan-resistant policy would keep a stable fraction

## Dead-ends / notes
- first retained cut looked unbounded-safe because of the `cacheAl` accounting quirk; the honest bound exposed the working-set problem (r4)
- SF0.1/50MB and SF0.5/750MB loads didn't reproduce the compaction OOM; it needed SF1 at the default limit (concurrency of ownership returns near-full)
- harness quirks: the node's trie GC logs `NoSuchFileException …/blocks/b1xx.binpb` during loads on legacy too (not this branch's)
- container disk allowance (~40GB) fits one SF1 node dir (14GB incl. un-GC'd files) + one SF0.5, not two SF1s
