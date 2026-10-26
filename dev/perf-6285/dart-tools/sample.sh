#!/bin/bash
# usage: sample.sh <out> — every 2s: ts, java main-thread voluntary/nonvoluntary ctxsw, utime+stime ticks  (-> <out>)
#   and memory: ts, RssAnon kB, RssFile kB, cgroup(v1 root) usage, total_rss, total_cache, total_mapped_file, total_active_file, total_inactive_file (-> <out>.mem)
out=$1; : > $out; : > $out.mem
cg=/sys/fs/cgroup/memory
while true; do
  pid=$(pgrep -f "clojure.main -m xtdb.bench" | head -1)
  if [ -n "$pid" ]; then
    for t in /proc/$pid/task/*; do
      if [ "$(cat $t/comm 2>/dev/null)" = "java" ] && [ "$(basename $t)" != "$pid" ]; then
        v=$(awk '/^voluntary_ctxt/{print $2}' $t/status); n=$(awk '/^nonvoluntary_ctxt/{print $2}' $t/status)
        cpu=$(awk '{print $14+$15}' $t/stat)
        echo "$(date +%s.%N) $v $n $cpu" >> $out; break
      fi
    done
    ra=$(awk '/^RssAnon/{print $2}' /proc/$pid/status); rf=$(awk '/^RssFile/{print $2}' /proc/$pid/status)
    s=$(awk '/^total_(rss|cache|mapped_file|active_file|inactive_file) /{printf "%s ", $2}' $cg/memory.stat)
    echo "$(date +%s.%N) $ra $rf $(cat $cg/memory.usage_in_bytes) $s" >> $out.mem
  fi
  sleep 2
done
