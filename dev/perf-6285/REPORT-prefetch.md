# Prefetch dart (#6300) — report

## Found

- Readahead works mechanically: with a depth of 1+ merge tasks, the scan's own `get` misses fall from ~283k to ~2k per run on the churning (default 3 GB direct / 1.5 GB cache) JVM.
  Every miss became a prefetch hit (`pfHits == pfIssued`, `pfWasted == 0`, `pfRefused == 0`), so no readahead page was evicted before the scan reached it.
- Wall-clock gain is modest on this box: churning JVM, whole 21-query cold stage 179.6 s → 160.5 s (depth 8), hot stage 173.5 s → 150.4 s (-11% / -13%); cold Q1 19.2 s → 11.9 s.
  Cache-fits JVM (`-PtwelveGBJvm`): no measurable gain (misses are only ~20k, i.e. first touch), as expected.
- Depth curve is flat after ~2–4 tasks.
  Depth 1 already removes almost all misses but has many `waits` (scan arrives while the prefetch is still loading: 11k at d1, 6k d2, 1.9k d4, 1.0k d8), so deeper = less stalling, with diminishing returns.
- Caveats on the numbers: one JVM per variant, 4-core/15 GB container, noisy (twelve-GB cold Q1 ranges 13–18 s across identical-config runs); the load run was itself prefetch-on. Whether the data dir's filesystem honours O_DIRECT was not checked.
- The bench-harness branch `6285-q1-q6-perf-harness` had no commits beyond `main`, so `:refresh-snapshot` / `runs.py` weren't available; I used the stock `tpch` bench with `-PnoLoad` (cold stage then hot stage in one JVM, each query once) and `dev/perf-6285/summ-prefetch.py`. Cache counters come from a stderr stats thread enabled by `-Dxtdb.cache.statsLog=1`.

## Design as built (throwaway)

- `MemoryCache.prefetch(key, slice, fetch): Boolean` — if no entry, `putIfAbsent(Loading)` then launches `load(..., prefetch = true)` on a cache-owned scope over the IO dispatcher; same miss path as `get` minus the pin. A concurrent `get` awaits the same `Loading`. The scope is cancelled on `close()`.
- Budget: prefetched-but-not-yet-read bytes are reserved against `maxSize * 0.25` (`-Dxtdb.cache.prefetchRatio`). Reservation is released on the first `get` hit of that page (`Page.unreached` CAS) or on eviction/failed load. Over budget → refuse (counted).
- No double-promotion: prefetched page enters `small` with freq 0; the scan's `get` is the first `freq++`.
- `BufferPool.prefetchRecordBatch(key, idx): Boolean` (default false; Local/Remote compute the block slice from the footer and call `prefetch`; `ReadOnlyBufferPool` delegates). `Segment.Page.prefetchDataPage()` / `Segment.prefetchDataPage(leaf)` (default no-op; `BufferPoolSegment` calls the pool).
- `ScanCursor` keeps a lookahead deque of `MergeTask`s (`-Dxtdb.scan.prefetchTasks`, default 4; 0 disables), prefetching a task's pages as it enters the window.
- Hits are untouched in cost: lookup + pin + two counter increments on the caller's thread.

## Numbers (ms; SF1 TPC-H RA; hits/misses are cumulative cache counters for the whole run)

| tag | cold Q1 | cold Q6 | hot Q1 | hot Q6 | cold all | hot all | hits | misses | waits | pf issued/hit/wasted |
|---|---|---|---|---|---|---|---|---|---|---|
| default d0 | 19203 | 6866 | 10729 | 7085 | 179582 | 173518 | 305287 | 283119 | 0 | 0 |
| default d1 | 14559 | 5902 | 10112 | 6295 | 170460 | 165001 | 205288 | 2255 | 11193 | 380863/380863/0 |
| default d2 | 13247 | 6211 | 9907 | 6582 | 166124 | 157771 | 192820 | 2230 | 5982 | 393356/393356/0 |
| default d4 | 12424 | 5747 | 10197 | 5750 | 161749 | 151936 | 192846 | 2221 | 1885 | 393322/393322/0 |
| default d8 | 11939 | 5862 | 10200 | 6135 | 160469 | 150394 | 192865 | 1882 | 996 | 393126/393126/0 |
| twelve d0 | 15215 | 4889 | 8493 | 5106 | 152422 | 132963 | 568266 | 19607 | 0 | 0 |
| twelve d1 | 16057 | 5805 | 9105 | 5431 | 160322 | 149323 | 568780 | 601 | 2803 | 19025/19025/0 |
| twelve d2 | 14932 | 5614 | 9516 | 5488 | 158960 | 147372 | 568780 | 582 | 1040 | 19025/19025/0 |
| twelve d4 | 18146 | 5417 | 8907 | 5552 | 158542 | 144498 | 568780 | 601 | 104 | 19025/19025/0 |
| twelve d8 | 13401 | 5472 | 9283 | 5390 | 157750 | 146380 | 568780 | 601 | 57 | 19025/19025/0 |

Q1 hot (~10 s) barely moves in the churning regime: it's CPU/decode-bound once pages arrive; prefetch only removes stalls. Baseline here is slower than the card's (Q1 hot 10.7 s vs 4.8 s) — different hardware; compare within this table only.

## What the route revealed

- Pages are small and numerous (~590k page reads per 21-query run); per-page overhead (Loading placeholder, coroutine launch, IO-dispatcher hop) is non-trivial and is paid for every prefetch.
- Prefetched reads and the scan's demand reads share the 8-thread IO dispatcher FIFO: a demand miss can queue behind prefetches (priority inversion). Not observed to hurt here (misses are ~0), but it is a risk when several scans prefetch concurrently.
- `getFooter` on `RemoteBufferPool` is blocking (disk-cache `.get()`); `prefetchRecordBatch` calls it on the scan thread. Fine when the footer is cached (segment opening already read it) but it is a latent blocking call.
- Cancel semantics are weak: closing the scan stops issuing more prefetches and drops the window, but already-launched loads run to completion (they're just resident pages then). Cancelling them needs per-scan `Job`s, and a cancelled `Loading` makes waiters retry via the existing path.
- Budget was never hit by a single scan (`pfRefused` 0, `pfWasted` 0); the 25% fraction is untested under concurrent scans.
- Misses counter: `memory-cache-misses` in LocalStorage/Remote is incremented inside the fetch lambda, so it now counts prefetch loads too, not scan stalls. A "scan stalled on a read" metric would need `waits` + `misses`.
- Invariants touched: `Page.unreached` reservation must be released on every exit (first hit, eviction, failed load) — `close()` does not reconcile it; the `Loading`→`Page` publish order (set `unreached` before `small.addLast`) matters.
- Build gotcha: in `ScanCursor` the `java.util.*` import makes `ArrayDeque` the Java one.

## Suggested sub-goals

1. (tidy) Split `MemoryCache.load` so "read a page and publish it" is separate from "pin it", with `get` as `load` + `retainSlice`. Prerequisite for 2.
2. (advance) `MemoryCache.prefetch` with the `Loading` placeholder, reservation budget and first-hit accounting; tests with a controlled `Fetch` (concurrent `get` waits on same load; budget refusal; failed load releases reservation; prefetched page not promoted by its first hit).
3. (tidy) `BufferPool.prefetchRecordBatch` / `Segment.Page.prefetchDataPage` seam (default no-ops) — depends on nothing, can land before 2.
4. (advance) Scan lookahead window in `ScanCursor` keyed by a byte budget rather than task count (page lengths are known from footers) — depends on 2, 3.
5. (advance) Per-scan cancellation of in-flight prefetches; separate IO priority (or a smaller dedicated dispatcher) for prefetch vs demand reads.
6. (advance) A cache-level stall metric (`waits`, demand `misses`) separate from the fetch-lambda counter.

## Open questions

- Q1: depth: 4 tasks captures nearly all of the benefit here (waits drop ~10x vs d1; wall-clock is flat from d2); a byte budget (e.g. a few tens of MB per scan) is likely the better unit given variable page sizes. Needs concurrency measurements to settle.
- Is 0.25 of the cache the right readahead fraction with several concurrent scans? Untested.
- Does O_DIRECT actually apply on the bench dir's filesystem, and does prefetch matter more when it does (no OS readahead)?
- Remote object store → disk cache is out of scope but `prefetch` through `RemoteBufferPool` blocks the IO thread on the disk-cache fetch for a cold disk cache — not measured.

## Dead ends

- Harness branch `6285-q1-q6-perf-harness` is empty relative to `main` (no `:refresh-snapshot`, no `runs.py`).
- Prefetching the scan's *current* task window start is pointless (the scan loads it in parallel immediately; it only adds a `Loading` wait).
