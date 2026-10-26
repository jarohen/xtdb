# usage: mem.py <run-prefix>... — memory (median over 2s samples during hot reps >= 20) + PagePool hit rate over the same window
import sys, re, datetime, statistics
def secs(h, mi, s, ms): return int(h)*3600+int(mi)*60+int(s)+int(ms)/1000
def tod(ts):
    t = datetime.datetime.fromtimestamp(ts); return t.hour*3600+t.minute*60+t.second+t.microsecond/1e6
G = 1 << 30
print(f"{'run':14} {'RssAnon':>8} {'RssFile':>8} {'cgUsage':>8} {'cgRss':>7} {'cgCache':>8} {'cgMapped':>8} {'actFile':>8} {'inactFile':>9} {'workSet':>8} | {'hit%':>6} {'hits':>8} {'misses':>8} {'evict':>8} {'readMB/s':>8}")
for pre in sys.argv[1:]:
    st = en = None
    for l in open(pre + '.gradle.log'):
        m = re.match(r'(\d\d):(\d\d):(\d\d)\.(\d+) \[main\] INFO  xtdb.bench - (Starting|Done) stage: :hot-queries-(\d+)-', l)
        if m:
            *t, kind, n = m.groups()
            if kind == 'Starting' and int(n) == 20 and st is None: st = secs(*t)
            if kind == 'Done': en = secs(*t)
    rows = []
    for l in open(pre + '.proc.mem'):
        f = l.split()
        if len(f) < 10: continue
        t = tod(float(f[0]))
        if st <= t <= en: rows.append([int(f[1])*1024, int(f[2])*1024] + [int(x) for x in f[3:9]])
    med = [statistics.median(c) for c in zip(*rows)] if rows else [0]*8
    ra, rf, use, crss, ccache, cmap, cact, cinact = med
    stats = []
    for l in open(pre + '.gradle.log'):
        m = re.match(r'(\S+)Z (?:FINAL )?PagePool stats: hits=(\d+) misses=(\d+) evictions=(\d+) .*readBytes=(\d+) readMs=(\d+)', l)
        if m:
            t = datetime.datetime.fromisoformat(m.group(1)[:26]).replace(tzinfo=datetime.timezone.utc).timestamp()
            stats.append((tod(t), *map(int, m.groups()[1:])))
    pool = ''
    w = [s for s in stats if s[0] >= st]
    if len(w) >= 2:
        a, b = w[0], w[-1]
        h, mi, ev, rb = b[1]-a[1], b[2]-a[2], b[3]-a[3], b[4]-a[4]
        pool = f"{100*h/max(1,h+mi):6.1f} {h:8} {mi:8} {ev:8} {rb/1e6/max(1e-9,b[0]-a[0]):8.0f}"
    g = lambda x: f"{x/G:.2f}G"
    print(f"{pre.rsplit('/',1)[-1]:14} {g(ra):>8} {g(rf):>8} {g(use):>8} {g(crss):>7} {g(ccache):>8} {g(cmap):>8} {g(cact):>8} {g(cinact):>9} {g(use-cinact):>8} | {pool}")
