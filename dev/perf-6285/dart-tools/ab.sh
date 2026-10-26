#!/bin/bash
# usage: ab.sh <round-tag> <variant>...   variant names: legacy noretain retained seq (retained+seqPageLoad) legacyseq
cd /home/user/xtdb
tag=$1; shift
for v in "$@"; do
  case $v in
    legacy) args="-Dxtdb.memoryCache.legacy=true";;
    legacyseq) args="-Dxtdb.memoryCache.legacy=true -Dxtdb.scan.seqPageLoad=true";;
    noretain) args="-Dxtdb.memoryCache.noRetain=true";;
    noretainseq) args="-Dxtdb.memoryCache.noRetain=true -Dxtdb.scan.seqPageLoad=true";;
    retained) args="";;
    seq) args="-Dxtdb.scan.seqPageLoad=true";;
    retbig) args="-Dxtdb.memoryCache.maxBytes=6000000000";;
    seqbig) args="-Dxtdb.memoryCache.maxBytes=6000000000 -Dxtdb.scan.seqPageLoad=true";;
  esac
  out=$HOME/bench/runs/$v-$tag
  mkdir -p $HOME/bench/runs
  $HOME/bench/tools/sample.sh $out.proc & sp=$!
  ./gradlew tpch -PscaleFactor=1 -Pdir=$HOME/bench/sf1 -PnoLoad -PnarrowVarTypes -PbenchLogFile=$out.log "-PextraJvmArgs=-Dxtdb.dart.variant=$v $args" > $out.gradle.log 2>&1
  echo "$v-$tag exit $?"
  kill $sp
done
