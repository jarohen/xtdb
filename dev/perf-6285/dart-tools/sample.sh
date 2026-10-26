#!/bin/bash
# usage: sample.sh <out>  — every 5s: ts, java main-thread voluntary/nonvoluntary ctxsw, utime+stime ticks
out=$1; : > $out
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
  fi
  sleep 2
done
