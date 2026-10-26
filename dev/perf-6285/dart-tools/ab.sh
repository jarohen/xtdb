#!/bin/bash
# usage: ab.sh <round-tag> <variant>...
#   legacy | fit (pool 3g, O_DIRECT) | p15 (1.5g) | p1 (1g) | p1pc (1g, pread via page cache) | fitpc (3g, pread)
cd /home/user/xtdb
tag=$1; shift
for v in "$@"; do
  case $v in
    legacy) args="";;
    fit) args="-Dxtdb.pagePool.bytes=3g";;
    fitpc) args="-Dxtdb.pagePool.bytes=3g -Dxtdb.pagePool.direct=false";;
    p15) args="-Dxtdb.pagePool.bytes=1536m";;
    p15pc) args="-Dxtdb.pagePool.bytes=1536m -Dxtdb.pagePool.direct=false";;
    p1) args="-Dxtdb.pagePool.bytes=1g";;
    p1pc) args="-Dxtdb.pagePool.bytes=1g -Dxtdb.pagePool.direct=false";;
    # arrow mode: frames from a root allocator limited to the pool size; netty counts them against MaxDirectMemorySize, so raise it by the pool size
    fita) args="-Dxtdb.pagePool.bytes=3g -Dxtdb.pagePool.mode=arrow -XX:MaxDirectMemorySize=6g";;
    p15a) args="-Dxtdb.pagePool.bytes=1536m -Dxtdb.pagePool.mode=arrow -XX:MaxDirectMemorySize=4608m";;
    p1a) args="-Dxtdb.pagePool.bytes=1g -Dxtdb.pagePool.mode=arrow -XX:MaxDirectMemorySize=4g";;
    # region from an FFM arena (counts against MaxDirectMemorySize), sized as a ratio of it; MaxDirect = 3g + pool keeps Arrow's 3g headroom
    fitt) args="-XX:MaxDirectMemorySize=6g -Dxtdb.pagePool.ratio=0.5 -Dxtdb.pagePool.arena=true -Dxtdb.pagePool.trim=true";;
    p15t) args="-XX:MaxDirectMemorySize=4608m -Dxtdb.pagePool.ratio=0.3333334 -Dxtdb.pagePool.arena=true -Dxtdb.pagePool.trim=true";;
    p1t) args="-XX:MaxDirectMemorySize=4g -Dxtdb.pagePool.ratio=0.25 -Dxtdb.pagePool.arena=true -Dxtdb.pagePool.trim=true";;
    # slab mode: size classes + 64MiB slabs from Arenas (count against MaxDirectMemorySize), pool sized as a ratio of it
    fits) args="-XX:MaxDirectMemorySize=6g -Dxtdb.pagePool.ratio=0.5 -Dxtdb.pagePool.mode=slab";;
    p15s) args="-XX:MaxDirectMemorySize=4608m -Dxtdb.pagePool.ratio=0.3333334 -Dxtdb.pagePool.mode=slab";;
    p1s) args="-XX:MaxDirectMemorySize=4g -Dxtdb.pagePool.ratio=0.25 -Dxtdb.pagePool.mode=slab";;
    p15snr) args="-XX:MaxDirectMemorySize=4608m -Dxtdb.pagePool.ratio=0.3333334 -Dxtdb.pagePool.mode=slab -Dxtdb.pagePool.slab.ratio=1e9";;
    p15sr8) args="-XX:MaxDirectMemorySize=4608m -Dxtdb.pagePool.ratio=0.3333334 -Dxtdb.pagePool.mode=slab -Dxtdb.pagePool.slab.ratio=8";;
    p15inc) args="-Dxtdb.pagePool.bytes=1536m -Dxtdb.pagePool.admit=incumbent";;
    p1inc) args="-Dxtdb.pagePool.bytes=1g -Dxtdb.pagePool.admit=incumbent";;
    p1ainc) args="-Dxtdb.pagePool.bytes=1g -Dxtdb.pagePool.mode=arrow -Dxtdb.pagePool.admit=incumbent -XX:MaxDirectMemorySize=4g";;
  esac
  out=$HOME/bench/runs/$v-$tag
  mkdir -p $HOME/bench/runs
  sync; echo 1 > /proc/sys/vm/drop_caches
  cp /sys/fs/cgroup/memory/memory.stat $out.cg-before
  bash dev/perf-6285/dart-tools/sample.sh $out.proc & sp=$!
  ./gradlew tpch -PscaleFactor=1 -Pdir=$HOME/bench/sf1 -PnoLoad -PnarrowVarTypes ${HOTREPS:+-PhotReps=$HOTREPS} -PbenchLogFile=$out.log "-PextraJvmArgs=-Dxtdb.dart.variant=$v $args" > $out.gradle.log 2>&1
  echo "$v-$tag exit $?"
  cp /sys/fs/cgroup/memory/memory.stat $out.cg-after
  kill $sp
done
