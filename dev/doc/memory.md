# Memory: what a JVM in a Kubernetes container is charged for

A container's memory limit is a cgroup limit, and the kernel charges it for every page the container's processes bring into memory — not only what the JVM reports.

- **Each page lands in one of four buckets, and only some of them can be given back.**
  Anonymous memory (heap, direct buffers, metaspace, stacks) can't be reclaimed without swap; clean page cache can; tmpfs can't.

- **A page is charged to the cgroup that brought it in, until the page is freed.**
  Closing or unmapping a file doesn't move its pages out of your charge.

- **At the limit, the kernel reclaims first and OOM-kills second.**
  What gets a container killed is memory that can't be reclaimed.

- **Kubernetes watches a different number — the working set — and acts on it separately.**
  Active page cache counts towards it, so a container can be evicted for memory the kernel would have reclaimed.

- **The JVM's flags bound some regions and not others.**
  A pod's limit has to cover the bounded ones added up, plus everything no flag bounds.

The kernel's cgroup v2 documentation and Kubernetes' resource-management and eviction pages are the primary sources; see [Sources](#sources).

## The limit is a cgroup limit

- **Kubernetes passes a container's memory limit to the kernel, which enforces it with cgroups.**
  "Both `cpu` and `memory` limits are applied by the kubelet (and container runtime), and are ultimately enforced by the kernel."
  "On Linux, the container runtime typically configures kernel cgroups that apply and enforce the limits you defined."
  assumption: on cgroup v2 the limit becomes the container cgroup's `memory.max`.

- **Enforcement is reactive.**
  "A container may use more memory than its `memory` limit, but if it does, it may get killed."

- **A limit with no request gets a request equal to the limit.**

## What the cgroup charges: four buckets

The memory controller tracks "Userland memory - page cache and anonymous memory.", "Kernel data structures such as dentries and inodes." and "TCP socket buffers."

- **Anonymous memory (`anon`) can't be reclaimed without swap.**
  Everything the JVM allocates itself lands here: heap, direct buffers, metaspace, code cache, thread stacks, GC and JIT structures, `malloc`.
  assumption: Kubernetes nodes run without swap unless swap support has been configured for them.

- **Page cache (`file`) is reclaimable once it's clean.**
  Every file read or written through the kernel lands here, including memory-mapped files (`file_mapped`) and files written to a disk-backed `emptyDir`.
  - **A dirty page has to be written back before it can be dropped** (`file_dirty`, `file_writeback`).

- **tmpfs (`shmem`) is charged like page cache and can't be reclaimed without swap.**
  A memory-backed `emptyDir` (`medium: Memory`) is tmpfs: "The kubelet tracks `tmpfs` emptyDir volumes as container memory use", and "memory used by those files can not be reclaimed when the system or the pod are under memory pressure".
  - **`memory.stat` counts `shmem` inside `file`** — "including tmpfs and shared memory" — so `file` overstates what's reclaimable by `shmem`.

- **Kernel memory is charged too.**
  `kernel` is "Amount of total kernel memory, including (kernel_stack, pagetables, percpu, vmalloc, slab)"; socket buffers are counted separately, as `sock`.
  Of the slab, `slab_reclaimable` can be given back and `slab_unreclaimable` can't.

- **The JVM's own mapped files are page cache too** — its jars and its class-data-sharing archive.

## Who a page is charged to

- **A page stays charged to the cgroup that instantiated it until the page is released.**
  The kernel's rule: "A memory area is charged to the cgroup which instantiated it and stays charged to the cgroup until the area is released."

- **So page cache outlives the file handle that brought it in.**
  Closing a file, or unmapping it, leaves its pages in the page cache and in your charge, until the kernel reclaims them.
  They don't move to a global, uncharged cache.

- **So a memory-mapped file costs the same as a file you read.**
  Its pages are page cache, counted as `file_mapped` while mapped and as plain `file` after, charged to the cgroup that faulted them in.

- **A page shared between cgroups is charged to one of them, not to each.**
  "To which cgroup the area will be charged is in-deterministic".

## At the limit: reclaim, then OOM-kill

1. **The kernel reclaims what it can** — clean page cache first, reclaimable slab.
2. **If usage can't be brought under the limit, the OOM killer runs inside the cgroup.**
   "If a cgroup's memory usage reaches this limit and can't be reduced, the OOM killer is invoked in the cgroup."
   The container shows as `OOMKilled`, and the kubelet restarts it according to its `restartPolicy`.

- **So what kills a container is memory that can't be reclaimed: anonymous memory and tmpfs.**
  Page cache, however large, is what gets reclaimed on the way.

- **assumption: reclaim can still lose.**
  Dirty pages waiting on writeback, or anonymous memory growing faster than the kernel reclaims, can reach the OOM killer while reclaimable cache still exists.

- **`memory.high` throttles instead of killing**: "the processes of the cgroup are throttled and put under heavy reclaim pressure", and "going over the high limit never invokes the OOM killer".
  assumption: Kubernetes sets it only under its `MemoryQoS` feature, which "adds memory throttling" on cgroup v2.

## What Kubernetes measures: the working set

- **The kubelet's number is usage minus `inactive_file`.**
  It "excludes inactive_file (the number of bytes of file-backed memory on the inactive LRU list) from its calculation, as it assumes that memory is reclaimable under pressure."

- **Active page cache counts as used.**
  "The kubelet treats `active_file` memory areas as not reclaimable."
  A workload that reads or writes a lot of local files keeps many pages on the active list, and "the kubelet is liable to observe this as high resource use and taint the node as experiencing memory pressure - triggering pod eviction."

- **`Evicted` and `OOMKilled` are two different mechanisms.**
  - **`OOMKilled` is the kernel**, at the container's own limit, or at the node's when the kubelet hasn't reclaimed in time.
  - **`Evicted` is the kubelet**, when the *node* runs short, ranking pods first by whether their usage exceeds their request.
    `BestEffort` and `Burstable` pods using more than they requested go first.

- **Setting the memory request equal to the limit (`Guaranteed`) keeps a pod out of eviction's first tier.**
  It's also Kubernetes' suggested workaround for the `active_file` issue: "setting the memory limit and memory request the same for containers likely to perform intensive I/O activity."

## The JVM's regions, and what bounds each

| region | bucket | bounded by | default |
|---|---|---|---|
| heap | `anon` | `-Xmx` | 25% of the container limit (`MaxRAMPercentage`), with container support on |
| `java.nio` direct buffers | `anon` | `-XX:MaxDirectMemorySize` | `Runtime.maxMemory()`, roughly the heap maximum |
| Netty direct memory, no-`Cleaner` path | `anon` | Netty's own counter: `io.netty.maxDirectMemory`, else the JVM's direct limit | off on JDK 9+ unless `io.netty.tryReflectionSetAccessible=true`; otherwise Netty uses `java.nio` direct buffers |
| metaspace | `anon` | `-XX:MaxMetaspaceSize` | unlimited |
| code cache | `anon` | `-XX:ReservedCodeCacheSize` | 240 MB |
| thread stacks | `anon` | `-Xss` per thread; nothing bounds the number of threads | 1 MB per thread (Linux/x64) |
| GC and JIT structures, symbol tables, `malloc` arenas | `anon` | nothing | — |
| memory-mapped files | `file` | nothing in the JVM | — |
| page cache from file I/O | `file` | nothing in the JVM | — |

- **The heap is charged as it's touched, unless `-XX:+AlwaysPreTouch` touches it all up front.**
  "By default, this option is disabled and all pages are committed as the application uses the heap space."
  - **assumption: touched heap stays charged until the collector uncommits it**, which G1 does only after a full or concurrent cycle, or periodically where `G1PeriodicGCInterval` is set (JEP 346) — so the heap's charge ratchets towards `-Xmx`, not towards live data.

- **`MaxDirectMemorySize` covers `java.nio` direct buffers, not off-heap memory in general.**
  "Sets the maximum total size (in bytes) of the `java.nio` package, direct-buffer allocations."

- **Netty can keep a second, separate allowance of the same size.**
  With `io.netty.maxDirectMemory` unset, Netty's no-`Cleaner` path counts its allocations in its own counter, capped at the JVM's direct limit, while the JDK counts `java.nio` direct buffers separately — so the two together can reach twice `MaxDirectMemorySize`.
  - **On JDK 9+ that path is off unless `-Dio.netty.tryReflectionSetAccessible=true` is set**: the flag defaults to `javaVersion() < 9`, and without it Netty allocates `java.nio` direct buffers, under the JDK's one limit.

- **A library's allocator limit is a ledger over memory charged somewhere else.**
  Arrow's allocators count the buffers they hand out against their own limits; Netty's counter moves only when Netty itself allocates, so a foreign allocation wrapped into an Arrow allocator counts against that allocator and not against Netty.

- **Native Memory Tracking accounts the JVM's own allocations, by subsystem.**
  `-XX:NativeMemoryTracking=summary` (off by default), then `jcmd <pid> VM.native_memory summary`.
  assumption: `Unsafe.allocateMemory` — behind `java.nio` direct buffers and Netty's no-`Cleaner` buffers — shows under "Other"; what it misses is `malloc` from native libraries.

## Sizing a pod

**The limit has to cover what can't be reclaimed, added up.**

- `-Xmx`
- `+` direct memory, once per counter in use — the JDK's, and Netty's where its no-`Cleaner` path is on
- `+` `MaxMetaspaceSize` — set it, or metaspace has no bound
- `+` `ReservedCodeCacheSize`
- `+` threads × `-Xss`
- `+` native overhead no flag bounds — measured, with Native Memory Tracking
- `+` memory-backed `emptyDir` volumes

**Page cache doesn't have to fit, but it decides how much of the remainder is useful.**
- **What's left under the limit is where the page cache lives**, and the kernel reclaims it to make room for anything above.
- **Its active part counts towards the working set**, so a pod whose request is below its limit can be evicted for it.

**The JVM's defaults alone can commit most of the limit before anything else.**
- **Unflagged, the heap gets 25% of the limit, and `java.nio` direct memory up to the same again** — 50%.
- **With Netty's no-`Cleaner` path on, Netty's own allowance adds another 25%** — 75%, before metaspace (unbounded), code cache, stacks and native overhead.

**Hand-written flags replace the container-derived heap size, and nothing checks that they fit.**
- **The JVM detects the container**: "The VM now provides automatic container detection support, which allows the VM to determine the amount of memory and number of processors that are available to a Java process running in docker containers."
  `-Xmx` replaces only the heap size it derives from that; detection still sizes the processor count.
- **With `-Xmx` and `MaxDirectMemorySize` set by hand, their sum is never compared with the limit** — a mismatch shows up as an `OOMKilled`.
- **DuckDB is the prior art for a database deriving its own budget from the container.**
  On Linux its default `memory_limit` is 80% of the smaller of the cgroup's limit (`memory.max`, or v1's `memory.limit_in_bytes`) and physical memory.
  DuckDB's docs say the limit applies only to its buffer manager, and that some operations bypass it.

## Reading `memory.stat`

From inside the container, the cgroup's files are under `/sys/fs/cgroup/`.
assumption: the container runtime mounts the container's own cgroup there, as cgroup namespaces do by default on cgroup v2.

| file / field | what it is |
|---|---|
| `memory.current` | "The total amount of memory currently being used by the cgroup and its descendants." |
| `memory.max` | the hard limit |
| `anon` | "Amount of memory used in anonymous mappings such as …" — the JVM's own allocations |
| `file` | "Amount of memory used to cache filesystem data, including tmpfs and shared memory." |
| `file_mapped` | "Amount of cached filesystem data mapped with mmap()." |
| `file_dirty`, `file_writeback` | page cache waiting for, or undergoing, writeback |
| `shmem` | "Amount of cached filesystem data that is swap-backed, such as tmpfs, shm segments, …" |
| `active_file`, `inactive_file` | page cache on the reclaim algorithm's two lists; the kubelet subtracts only `inactive_file` |
| `kernel` | "Amount of total kernel memory, including (kernel_stack, pagetables, percpu, vmalloc, slab)" |
| `sock` | "Amount of memory used in network transmission buffers" |

- **Unreclaimable usage is roughly `anon + shmem + kernel + sock − slab_reclaimable`.**
  That's the part the limit has to hold.
- **The working set is `memory.current − inactive_file`.**
  That's the part Kubernetes compares with the request.

## Sources

- [Linux kernel: Control Group v2 — Memory](https://docs.kernel.org/admin-guide/cgroup-v2.html)
- [Kubernetes: Resource Management for Pods and Containers](https://kubernetes.io/docs/concepts/configuration/manage-resources-containers/)
- [Kubernetes: Node-pressure Eviction](https://kubernetes.io/docs/concepts/scheduling-eviction/node-pressure-eviction/)
- [JDK 21: the `java` command](https://docs.oracle.com/en/java/javase/21/docs/specs/man/java.html)
- [JDK 21: `jdk.internal.misc.VM` — the direct-memory default](https://github.com/openjdk/jdk21u/blob/master/src/java.base/share/classes/jdk/internal/misc/VM.java)
- [Netty 4.1: `PlatformDependent` — Netty's direct-memory counter](https://github.com/netty/netty/blob/4.1/common/src/main/java/io/netty/util/internal/PlatformDependent.java)
- [DuckDB: `config.cpp`](https://github.com/duckdb/duckdb/blob/main/src/main/config.cpp) and [`cgroups.cpp`](https://github.com/duckdb/duckdb/blob/main/src/common/cgroups.cpp) — the default `memory_limit`
- [DuckDB: limits](https://duckdb.org/docs/current/operations_manual/limits.html) and [OOM errors](https://duckdb.org/docs/lts/guides/troubleshooting/oom_errors)
- [Netty 4.1: `PlatformDependent0` — `io.netty.tryReflectionSetAccessible`](https://github.com/netty/netty/blob/4.1/common/src/main/java/io/netty/util/internal/PlatformDependent0.java)
