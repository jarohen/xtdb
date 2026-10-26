# Dart: size-class slabs for the page pool (#4963)

Branch `dart/pool-slabs` = `dart/buffer-pool` (054287e) + slab mode (`-Dxtdb.pagePool.mode=slab`) in `PagePool.kt`.
Box 4 vCPU / 15GB, JDK 21. SF1 RA, `-PnarrowVarTypes`, 50 hot reps drop 20, 1 JVM per variant, OS page cache dropped before each run, pinned-result check passed on every run.
Variants: `dev/perf-6285/dart-tools/ab.sh r1 fits p15s p1s p15a`; tuning runs (`p15snr`, `p15sr8`) are 30 reps drop 10.

## tl;dr
- **Slabs work and are visible**: no failures, no storms (max 1 eviction per ordinary allocation), waste is internal only and gauged (+7.5% frame/page bytes at fit).
- **At fit it costs more than buddy**: Q1 2530 / Q6 1136 vs trimmed-buddy-era 2316 / 950 (different day, not re-run here).
- **Under churn it does not beat arrow**: 1.5G → Q1 4755 / Q6 2732 vs arrow 4528 / 2480 (hit 34.7% vs 58.4%; bytes read 94G vs 85G).
- **Two structural findings the route didn't anticipate** (below): per-class slab minimum, and big pages dominating.

## Design as built
- Classes: 4KiB steps to 64KiB, then 4 per doubling to 8MiB (≤ 25% internal waste worst case)
- Slab = 64MiB, **each its own `Arena.ofShared()`**, allocated lazily when a class needs a frame and budget allows; one class per slab, equal frames, free-slot stack per slab
- Pages > 8MiB: **dedicated exact allocation** (4KiB-rounded) from its own shared Arena, closed on eviction, counted against the same budget
  - the brief's single never-closed arena cannot do this: an `Arena` can't free part of itself, so a dedicated frame or a released slab could never return budget
- Eviction: per-class S3-FIFO (small 10% of class bytes / main), ghost keys global; dedicated frames are their own queue pair
- Allocation order: free slot → empty slab from another class → new slab if under budget → rebalance (forced if class has nothing evictable; else only if the coldest other class's queue head is ≥ ratio× older, ratio 2) → evict within class → forced rebalance → free budget (empty slabs, dedicated frames, whole slabs closed)
- Rebalance victim: coldest other class (age of queue head by last touch, clock = bytes loaded), its unpinned slab with the lowest sum(1+freq); all its frames evicted, slab reattached
- Counters: waste (frameBytes vs pageBytes), slab free-slot and tail bytes, evictions per alloc, rebalances / their evictions / ms, dedicated close ms

## Numbers (1 JVM each; Q in ms hot median)
| variant | Q1 | Q6 | hit % | frame/page bytes | evict/miss | max evict/alloc | rebalances | alloc fail | RssAnon | cg anon-ish (cgRss) | cg cache | act/inact file |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| slab fit (3G) | 2530 | 1136 | 99.0 | 2013M/1873M = +7.5% | 0 | 0 | 0 | 0 | 4.51G | 9.63G | 0.55G | 0.17/0.37G |
| slab 1.5G | 4755 | 2732 | 34.7 | 1353M/1269M = +6.6% | 0.99 | 1 | 898 (334K evictions, 305ms total) | 0 | 3.33G | 8.48G | 0.68G | 0.17/0.50G |
| slab 1G | 5031 | 3136 | 2.3 | 805M/773M = +4.1% | 1.00 | 1 | 1540 (578K evictions, 543ms) | 0 | 2.81G | 7.93G | 0.67G | 0.16/0.49G |
| arrow 1.5G (re-run) | 4528 | 2480 | 58.4 | 1611M/1522M = +5.8% (netty hides more) | 0.98 | n/a | n/a | 0 | 3.80G | 8.93G | 0.54G | 0.15/0.37G |
| reference (prev dart) trimmed buddy 1.5G | | | 15.7 | +2.8% | | ~10K | | 127 | | | | |
| reference plain buddy 1.5G | | | 26.7 | +34% | | | | | | | | |

(cgroup v1 `memory.stat` values are as `mem.py` prints them; the machine's RSS includes Arrow/heap as well as the pool.)

Tuning at 1.5G (30 reps drop 10): never rebalance unless forced → Q1 4978 / Q6 2798, hit 38%, 3 rebalances; ratio 8 → 4475 / 2818, hit 29%, 307 rebalances.
Rebalancing is not what limits the hit rate: with ratio 2 it caused 41% of all evictions, yet turning it off barely moves anything.

## What the route revealed
- **A slab per class is a fixed cost**: at fit, 15 classes hold ≥ 1 slab each; 832MB of the 2.85GB committed is empty slots (`slabFreeSlotBytes`).
  - `Arena.allocate` zero-fills, so those slabs are committed RSS, not just reserved budget
  - at 1.5G it is 218MB (14%), at 1G 230MB (22%)
  - total committed waste = internal (+4–7%) + empty slots; the second term is the one the brief's "waste bounded" claim has to include
- **Big pages dominate**: 13 pages > 8MiB hold 431MB, 29% of a 1.5G pool and 43% of a 1G pool
  - they are never evicted at these sizes, so the small-page classes get 1.0G / 0.6G and thrash
  - arrow's single global S3-FIFO gets to evict those big frames as well; a per-class design has no way to trade them against small-page hit rate except by the rebalance heuristic
- **Hit rate under a loop is the policy's, not the allocator's**: per-class S3-FIFO retains ~half of what the capacity allows (34.7% vs 68% ideal for the slab bytes); same shape as the previous dart's S3-FIFO result
- **Rebalancing moved the right data but never paid off**: 898 rebalances cost 305ms total (cheap), but evicting a whole slab discards ~370 frames at once; with a repeating scan the next pass re-reads them
- **Eviction never exceeds one frame per ordinary allocation** (goal met): `maxEvictionsPerAlloc=1` at 1.5G/1G; no fragmentation by construction
- **Dedicated Arena close** was never exercised at these sizes (dedicated frames were never evicted), so its handshake cost is unmeasured

## Data structures: matched / didn't
- Matched: size classes + free-slot stacks (O(1) alloc/free, no address-order eviction problem); per-class queues (O(1) eviction, no filtering scan); frame lifetime tied to pins unchanged
- Didn't: one-class-per-slab at 64MiB granularity (fixed cost above); no cross-class policy, so big-vs-small trade-offs rely on a heuristic; arenas can't be shrunk, which forced per-slab / per-dedicated arenas

## Landing it
- Replace the arrow-mode frame allocator behind a seam `FrameAllocator { alloc(readLen, frame): addr; free }` with the policy (queues, ghost, pins) outside it
- Policy likely wants to stay global (one S3-FIFO over all frames, as arrow mode) with the allocator only able to say "evict one of these class-compatible frames": that gives arrow's hit rate with slabs' bounded waste — not built here
- Needs: sizing of slab to avoid the empty-slot floor (smaller slabs for small classes, or fewer classes), a decision on > 8MiB pages, `MaxDirectMemorySize` raised by the pool size (arena memory counts against it)

## Risks / questions
- **Q-a**: global policy + class-aware victim choice (the global queue filtered by class was not measured; per-class queues were)
- **Q-b**: slab size per class family (e.g. 16MiB up to 2MiB frames) so the floor is ~15 × 16MiB not 15 × 64MiB
- **Q-c**: should > 8MiB pages be evictable for small-page benefit under a tight pool?
- Risk: zero-fill of 64MiB per slab on the miss path (inside the pool lock) — not isolated in these numbers
- Risk: whole-slab rebalance races with hits that pin during the CAS pass; 0 aborts observed

## Dead-ends
- Single shared never-closed Arena: can't host exact-size big frames or release budget
- Age-ratio rebalance triggers (2 and 8) and none: all within ~10% of each other; not the lever

## Not done
- Global-queue-filtered-by-class variant; smaller slabs; arrow vs slab at 1G; Clojure tests
- `./gradlew :xtdb-core:test --tests "xtdb.cache.*"` passes (these tests don't exercise `PagePool`; no slab-specific tests, per the dart brief)
