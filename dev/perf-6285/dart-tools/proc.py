# usage: proc.py <run-prefix>... — main-thread on-CPU % of wall and voluntary ctxsw per query, hot reps >= 20 (linear interp of 2s /proc samples)
import sys, re, datetime, bisect, collections
def secs(h, mi, s, ms): return int(h)*3600+int(mi)*60+int(s)+int(ms)/1000
for pre in sys.argv[1:]:
    st, dn = {}, {}
    for l in open(pre + '.gradle.log'):
        m = re.match(r'(\d\d):(\d\d):(\d\d)\.(\d+) \[main\] INFO  xtdb.bench - (Starting|Done) stage: :hot-queries-(\d+)-(q\d+)', l)
        if m:
            *t, kind, n, q = m.groups()
            (st if kind == 'Starting' else dn)[(int(n), q)] = secs(*t)
    rows = []
    for l in open(pre + '.proc'):
        ts, v, n, cpu = l.split()
        t = datetime.datetime.fromtimestamp(float(ts))
        rows.append((t.hour*3600+t.minute*60+t.second+t.microsecond/1e6, int(v), int(cpu)))
    xs = [r[0] for r in rows]
    def at(t, i):
        k = bisect.bisect_left(xs, t)
        if k == 0 or k >= len(xs): return None
        (t0, *a), (t1, *b) = rows[k-1], rows[k]
        return a[i] + (b[i]-a[i])*(t-t0)/(t1-t0)
    agg = collections.defaultdict(lambda: [0, 0, 0, 0])
    for (n, q), s in st.items():
        if n < 20 or (n, q) not in dn: continue
        e = dn[(n, q)]
        vals = [at(s, 0), at(e, 0), at(s, 1), at(e, 1)]
        if None in vals: continue
        a = agg[q]; a[0] += e - s; a[1] += vals[1]-vals[0]; a[2] += (vals[3]-vals[2])/100; a[3] += 1
    for q, (wall, v, cpu, k) in sorted(agg.items()):
        print(f"{pre.rsplit('/',1)[-1]:20} {q:4} n {k:3} main on-CPU {cpu/wall*100:5.1f}%  volCtxSw/run {v/k:7.0f}")
