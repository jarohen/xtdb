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
  esac
  out=$HOME/bench/runs/$v-$tag
  mkdir -p $HOME/bench/runs
  sync; echo 1 > /proc/sys/vm/drop_caches
  cp /sys/fs/cgroup/memory/memory.stat $out.cg-before
  bash dev/perf-6285/dart-tools/sample.sh $out.proc & sp=$!
  ./gradlew tpch -PscaleFactor=1 -Pdir=$HOME/bench/sf1 -PnoLoad -PnarrowVarTypes -PbenchLogFile=$out.log "-PextraJvmArgs=-Dxtdb.dart.variant=$v $args" > $out.gradle.log 2>&1
  echo "$v-$tag exit $?"
  cp /sys/fs/cgroup/memory/memory.stat $out.cg-after
  kill $sp
done
