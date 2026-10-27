# #6301 dart: loop-resistant policy for the memory cache

## Found

- **The card's assumption holds, and is worse in the real bench than in the model.**
  S3-FIFO keeps ~0.5 of a loop in the simulator, and on hot Q1+Q6 at 1 GiB and 1.25 GiB it hit on only **7% / 9%** of reads.
- **S3-FIFO + loop detection (the card's second idea) fixes it in ~40 lines**, hit path untouched:
  1 GiB: 7% → **61%**, Q1 6264 → 4240 ms, Q6 3989 → 2648 ms.
  1.25 GiB: 9% → **77%**, Q1 6579 → 4353, Q6 4178 → 2317.
  At 1.5 GiB the whole loop fits in the real bench (99% hits both ways), so there is no loop to resist and no difference (Q1 3116 vs 3250, Q6 1322 vs 1343 — noise, sd ~100).
- **Q1 (is it worth its bookkeeping): yes.**
  Admission cost is ~1.3–1.9 µs per miss for both variants (the miss is a ~100 KB–36 MB disk read); the freeze check is amortised O(1) per miss.
- LIRS, in the simulator, matches the loop policy on uniform loops but does **not** beat plain S3-FIFO on the real trace at 1 GiB (0.368 vs 0.370) and is a far bigger change (new stack/queue structure, hit path must touch the stack).
  I did not build it for real; I'd drop it.

## Design as built (`MemoryCache.kt`, see `admit` / `evictOne`)

- `ghost` now records *where* a page was evicted from (value `Boolean`: from `main`); capacity `GHOST_FACTOR (2) × resident pages`.
  Factor 1 cannot detect a loop longer than ~2× the cache (simulated at cap/loop 0.5: 0% vs 43%).
- `frozen: Frozen?` (null | `Frozen(misses)`), guarded by `lock`.
  - **Freeze**: a miss whose key is in the ghost as "evicted from main" while not frozen → `main` freqs zeroed, `frozen = Frozen()`.
  - **While frozen**: no promotion small→main, no admission to main from ghost hits; `evictOne` takes from `small` only. `main` is therefore a stable subset; `small` (10%) cycles the rest of the loop.
  - **Thaw**: (a) every `main.size` misses, if no `main` page has `freq > 0` (nothing frozen was hit in that pass) → thaw, else zero freqs and go again; (b) `evictOne` is forced to evict from `main` (small empty or all held) → thaw. Hits remain a lookup + pin + `freq++`; held pages are still skipped.
- Dart-only extras (not to land): `xtdb.cache.maxSizeBytes` / `.loopPolicy` / `.trace` system properties, hit/miss counters printed on close, `build.gradle.kts` pass-through.

## Numbers

Real bench, SF1, hot Q1/Q6, 40 hot reps, drop 20 (medians ms; hit rate over the whole JVM run incl. cold):

| cache | policy | hit rate | Q1 | Q6 |
| --- | --- | --- | --- | --- |
| 1 GiB | S3-FIFO | 7.2% (72.6K/1.01M) | 6264 | 3989 |
| 1 GiB | + loop freeze | 60.8% (613K/1.01M) | 4240 | 2648 |
| 1.25 GiB | S3-FIFO | 9.0% | 6579 | 4178 |
| 1.25 GiB | + loop freeze | 76.8% | 4353 | 2317 |
| 1.5 GiB | S3-FIFO | 99% | 3116 | 1322 |
| 1.5 GiB | + loop freeze | 99% | 3250 | 1343 |

Freezes: 1 per run, never thawed spuriously.

Offline simulation (byte-weighted; `dev/perf-6285/sim/`; hit rate, i.e. count/byte-weighted where it matters):

| workload (cap/loop) | LRU | S3-FIFO | S3-FIFO+loop (gf2) | LIRS |
| --- | --- | --- | --- | --- |
| uniform loop (0.5) | 0 | 0 | 0.43 | 0.45 |
| uniform loop (0.81) | 0 | 0.62 | 0.71 | 0.73 |
| jittered-size loop (0.81) | 0 | 0.61 | 0.71 | 0.74 |
| loop + hot set (0.6) | 0.31 | 0.58 | 0.66 | 0.66 |
| zipf, 5000 pages (0.3) | 0.81 | 0.84 | 0.84 | 0.84 |
| hot set + one-shot scans | 0.31–0.46 | 0.48–0.50 | = S3-FIFO | = S3-FIFO |
| loop whose page set shifts every 10 reps (0.81) | 0 | 0.09 | 0.60 | 0.61 |
| **real Q1/Q6 trace, 1 GiB** | 0 | 0.37 | **0.66** | 0.37 |
| real trace, 1.5 GiB (0.85 of distinct) | 0 | 0.56 | **0.89** | 0.70 |

The "hot or fits does no worse than S3-FIFO" property holds in every simulated case (zipf: −0.1 pt at the smallest size).
The shifting-loop row needed the thaw rule; without it the frozen `main` held the stale set forever (0.10).

## Surprises / risks

- **Simulator vs real disagree on level** (S3-FIFO 37% sim vs 7% real at 1 GiB), and the real 1.5 GiB run fits while the trace says distinct bytes are 1.77 GiB.
  So the real cache's effective capacity or access order differs from the model: candidates are Arrow size-class rounding, pinned pages (parallel scan readers hold many), `ReclaimingAllocationListener` evicting for other allocators, and concurrent scans interleaving.
  Not chased. The policy's *direction* held in both; magnitudes shouldn't be quoted from the simulator.
- Freeze needs a ghost hit on a page evicted from `main`, which needs ≥ one full pass of history: the first passes behave as S3-FIFO.
- Ghost memory: keys only, 2× resident pages (~25K entries here); each `PathSlice` holds a `Path`, so worth counting.
- Thaw check scans `main` (O(main) per `main.size` misses), under `lock` — amortised O(1), but a long pause for very large caches; could be incremental.
- Frozen `main` is chosen by luck of what was in `main` at freeze time (not by usefulness), which is fine for a loop but means the stable subset is arbitrary.
- Not run: `MemoryCacheTest`, `CacheSimulationTest`, property tests; none of the tests I'd want exist yet (loop keeps stable subset; thaw on a shifted set; held pages never evicted while frozen).
- Gradle first-run configure + SF1 load took ~18 min on this 4-core box; a bench pair is ~5–8 min.

## Suggested sub-goals

1. **Advance** — loop freeze in `MemoryCache` (this dart's `admit`/`evictOne` change), with tests: a loop of 1.2× capacity keeps ≈ main-share resident; a shifted loop thaws; zipf/hot-set hit rate within noise of S3-FIFO; `isHeld` pages untouched while frozen. Depends on nothing.
2. **Tidy** (before 1, optional) — extract the S3-FIFO queues (`small`/`main`/`ghost` + sizes) from `MemoryCache` into a type owning its own state, so `Frozen` is a field on it and the policy is simulable without Arrow; this also lets the sim replay the real code rather than a Python mirror.
3. **Advance** — expose `hits`/`misses`/`frozen` as meter gauges (replace my stderr line); needed to tell in production whether a loop is being resisted.
4. **Tidy/advance (investigation)** — explain the sim-vs-real gap above before trusting any capacity model; likely a separate card.

## Open questions

- Should `GHOST_FACTOR` be tied to bytes rather than page count (pages vary 256 B–36 MB)?
- Is "one pass of misses" the right thaw window when pass length ≠ `main.size` (e.g. queries touching different columns alternately)? The two-query Q1/Q6 pattern worked.
- Multi-tenant/multi-database caches: one cache, one freeze — a loop in one database freezes `main` for all. Is that acceptable?
- `small` target stays 10%: at cap/loop 0.5 the stable subset is only ~0.43; should `small` shrink when frozen?

## Dead ends

- `GHOST_FACTOR = 1` (original ghost sizing): loop detection never fires when the loop is > ~2× cache.
- Freeze without thaw: frozen set goes stale on a shifting working set (0.10 vs 0.60).
- LIRS built only in the simulator: no gain on the real trace, larger change, hit path would need the stack — not pursued.
