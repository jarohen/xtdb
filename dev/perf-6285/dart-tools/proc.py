# usage: proc.py <run-prefix>... — main-thread vol ctxsw per hot rep and on-CPU/wall over hot reps 20..end
import sys, re, datetime
for pre in sys.argv[1:]:
    starts = {}
    day = None
    for l in open(pre + '.gradle.log'):
        m = re.match(r'(\d\d):(\d\d):(\d\d)\.(\d+) \[main\] INFO  xtdb.bench - Starting stage: :hot-queries-(\d+)$', l.strip())
        if m:
            h, mi, s, ms, n = m.groups()
            starts[int(n)] = int(h)*3600+int(mi)*60+int(s)+int(ms)/1000
        m = re.match(r'(\d\d):(\d\d):(\d\d)\.(\d+) \[main\] INFO  xtdb.bench - Starting stage: :summary', l.strip())
        if m:
            h, mi, s, ms = m.groups(); starts['end'] = int(h)*3600+int(mi)*60+int(s)+int(ms)/1000
    rows = []
    for l in open(pre + '.proc'):
        ts, v, n, cpu = l.split()
        t = datetime.datetime.fromtimestamp(float(ts))
        rows.append((t.hour*3600+t.minute*60+t.second+t.microsecond/1e6, int(v), int(n), int(cpu)))
    if 20 not in starts or 'end' not in starts:
        print(pre, 'no window', sorted(k for k in starts if k != 'end')[-3:]); continue
    a, b = starts[20], starts['end']
    w = [r for r in rows if a <= r[0] <= b]
    if len(w) < 2: print(pre, 'few samples'); continue
    (t0, v0, n0, c0), (t1, v1, n1, c1) = w[0], w[-1]
    reps = sum(1 for k in starts if k != 'end' and k >= 20 and a <= starts[k] and starts[k] >= t0 and starts[k] < t1)
    print(f"{pre.rsplit('/',1)[-1]:20} window {t1-t0:6.1f}s main on-CPU {(c1-c0)/100/(t1-t0)*100:5.1f}% volCtxSw/s {(v1-v0)/(t1-t0):7.0f} ~volCtxSw/rep(q1+q6) {(v1-v0)/max(reps,1):7.0f}")
