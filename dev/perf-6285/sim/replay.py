# usage: replay.py trace.txt  -- replays a captured (path|offset|length|bytes) trace at several cache sizes
import sys
from sim import *
tr = []
for line in open(sys.argv[1]):
    p, o, l, b = line.rstrip('\n').split('|'); tr.append(((p, o, l), int(b)))
uniq = {}
for k, b in tr: uniq[k] = b
tot = sum(uniq.values())
print(f"accesses {len(tr)}, distinct pages {len(uniq)}, distinct bytes {tot/2**30:.2f} GiB")
# trace is miss+hit stream of the 1 GiB baseline run, so it is the full access stream
skip = len(tr) // 4
for cap in [2**30, int(1.5 * 2**30), int(tot * 1.05)]:
    print(f"\ncache {cap/2**30:.2f} GiB ({cap/tot:.2f} of distinct)")
    for pn in ['lru', 's3fifo', 's3fifo+loop gf2', 'lirs']:
        h, hb = run(POLICIES[pn](cap), tr, skip)
        print(f"  {pn:18} hit {h:.3f}  byte-hit {hb:.3f}")
