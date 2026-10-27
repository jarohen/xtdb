# offline replay of page-access traces through cache policies; byte-weighted, trace = [(key, bytes)]
import random, sys
from collections import OrderedDict, deque

MAXF = 3

class S3Fifo:
    """mirrors MemoryCache.evictOne; loop=True adds the main-ghost loop detector"""
    def __init__(self, cap, loop=False, exit_misses=None, gf=1):
        self.cap = cap; self.loop = loop; self.gf = gf
        self.small = OrderedDict(); self.main = OrderedDict()   # key -> [bytes, freq]
        self.sb = self.mb = 0; self.tgt = cap // 10
        self.ghost = OrderedDict(); self.mghost = OrderedDict()
        self.frozen = False; self.since = 0; self.exit_misses = exit_misses
        self.hits = self.n = self.hb = self.nb = 0
    def used(self): return self.sb + self.mb
    def _gadd(self, g, k):
        g[k] = 1
        while len(g) > max(16, self.gf * (len(self.small) + len(self.main))): g.popitem(last=False)
    def evict1(self):
        while True:
            if self.small and (self.sb >= self.tgt or not self.main or self.frozen):
                k, (b, f) = self.small.popitem(last=False); self.sb -= b
                if f > 0 and not self.frozen:
                    self.main[k] = [b, 0]; self.mb += b
                    continue
                if f > 0 and self.frozen:      # frozen: referenced small pages are not promoted
                    pass
                self._gadd(self.ghost, k); return b
            k, (b, f) = self.main.popitem(last=False)
            if f > 0: self.main[k] = [b, f - 1]; continue
            self.mb -= b
            if self.loop: self._gadd(self.mghost, k)
            return b
    def access(self, k, b):
        self.n += 1; self.nb += b
        e = self.small.get(k) or self.main.get(k)
        if e:
            if e[1] < MAXF: e[1] += 1
            self.hits += 1; self.hb += b; return
        if self.frozen:
            self.since += 1
            if self.since >= len(self.main):      # a pass's worth of misses: did anything frozen get hit?
                if not any(v[1] > 0 for v in self.main.values()): self.frozen = False; self.mghost.clear()
                else:
                    for v in self.main.values(): v[1] = 0
                self.since = 0
        if self.loop and k in self.mghost and not self.frozen:
            self.frozen = True; self.since = 0
            for v in self.main.values(): v[1] = 0
        if self.loop and k in self.mghost: del self.mghost[k]
        while self.used() + b > self.cap:
            if self.frozen and not self.small: self.frozen = False
            self.evict1()
        if k in self.ghost and not self.frozen:
            del self.ghost[k]; self.main[k] = [b, 0]; self.mb += b
        else:
            self.ghost.pop(k, None); self.small[k] = [b, 0]; self.sb += b

class Lirs:
    def __init__(self, cap, hir=0.1):
        self.cap = cap; self.lircap = int(cap * (1 - hir))
        self.S = OrderedDict()   # key -> [bytes, state] state: 'L','H','N'
        self.Q = OrderedDict()   # resident HIR, key -> bytes
        self.lirb = 0; self.resb = 0
        self.hits = self.n = self.hb = self.nb = 0
    def prune(self):
        while self.S:
            k, (b, s) = next(iter(self.S.items()))
            if s == 'L': break
            self.S.popitem(last=False)
    def demote_bottom(self):
        k, (b, s) = next(iter(self.S.items()))
        self.S.popitem(last=False); self.lirb -= b
        self.Q[k] = b                    # becomes resident HIR (not in S now)
        self.prune()
    def evict_q(self):
        k, b = self.Q.popitem(last=False); self.resb -= b
        if k in self.S: self.S[k][1] = 'N'
    def trim_meta(self):
        while len(self.S) > 3 * (len(self.Q) + 1000 + self.lirb // 1) and False: pass
        nres = sum(1 for _ in ())  # (kept cheap below)
    def access(self, k, b):
        self.n += 1; self.nb += b
        e = self.S.get(k)
        if e and e[1] == 'L':
            self.hits += 1; self.hb += b
            self.S.move_to_end(k); self.prune(); return
        if e and e[1] == 'H':
            self.hits += 1; self.hb += b
            del self.Q[k]; e[1] = 'L'; self.lirb += b
            self.S.move_to_end(k)
            while self.lirb > self.lircap: self.demote_bottom()
            self.prune(); return
        if k in self.Q:                  # resident HIR, not in S
            self.hits += 1; self.hb += b
            self.S[k] = [b, 'H']; self.Q.move_to_end(k); return
        # miss
        while self.resb + b > self.cap and self.Q: self.evict_q()
        if self.resb + b > self.cap:     # all LIR; demote
            while self.resb + b > self.cap and self.S:
                self.demote_bottom()
                while self.resb + b > self.cap and self.Q: self.evict_q()
        self.resb += b
        if e and e[1] == 'N':
            e[1] = 'L'; e[0] = b; self.lirb += b; self.S.move_to_end(k)
            while self.lirb > self.lircap: self.demote_bottom()
            self.prune()
        elif self.lirb + b <= self.lircap and not self.Q and len(self.S) < 1e12 and self.resb <= self.lircap:
            self.S[k] = [b, 'L']; self.lirb += b        # warm-up
        else:
            self.S[k] = [b, 'H']; self.Q[k] = b
        # bound non-resident metadata
        if len(self.S) > 4 * (len(self.Q) + 2000) + 4 * self.lirb // max(1, b):
            for kk in [kk for kk, v in self.S.items() if v[1] == 'N'][: len(self.S) // 4]: del self.S[kk]

class Lru:
    def __init__(self, cap):
        self.cap = cap; self.d = OrderedDict(); self.u = 0
        self.hits = self.n = self.hb = self.nb = 0
    def access(self, k, b):
        self.n += 1; self.nb += b
        if k in self.d: self.d.move_to_end(k); self.hits += 1; self.hb += b; return
        while self.u + b > self.cap: _, bb = self.d.popitem(last=False); self.u -= bb
        self.d[k] = b; self.u += b

def run(pol, trace, skip=0):
    for i, (k, b) in enumerate(trace):
        if i == skip: pol.hits = pol.n = pol.hb = pol.nb = 0
        pol.access(k, b)
    return pol.hits / pol.n, pol.hb / pol.nb

# ---- synthetic workloads
def loop_trace(npages, reps, size=1, jitter=False, seed=1):
    r = random.Random(seed)
    sizes = [size if not jitter else r.choice([1, 1, 2, 4, 8]) for _ in range(npages)]
    return [(i, sizes[i]) for _ in range(reps) for i in range(npages)]

POLICIES = {
    'lru': lambda c: Lru(c),
    's3fifo': lambda c: S3Fifo(c),
    's3fifo+loop': lambda c: S3Fifo(c, loop=True),
    's3fifo+loop gf2': lambda c: S3Fifo(c, loop=True, gf=2),
    's3fifo+loop gf3': lambda c: S3Fifo(c, loop=True, gf=3),
    'lirs': lambda c: Lirs(c),
}

def table(name, trace, total, fracs, skip, pols=POLICIES):
    print(f"\n## {name}  (loop bytes {total})")
    print(f"{'policy':20}" + ''.join(f"{'cap/loop='+str(f):>16}" for f in fracs))
    for pn, mk in pols.items():
        row = []
        for f in fracs:
            h, hb = run(mk(int(total * f)), trace, skip)
            row.append(f"{h:6.3f}/{hb:6.3f}")
        print(f"{pn:20}" + ''.join(f"{x:>16}" for x in row))

if __name__ == '__main__':
    t = loop_trace(2000, 30)
    table('pure loop 2000 uniform pages x30', t, 2000, [0.5, 0.81, 1.0, 1.2], 2000)
    t = loop_trace(2000, 30, jitter=True); tot = sum(b for _, b in t[:2000])
    table('pure loop, jittered sizes', t, tot, [0.5, 0.81, 1.0, 1.2], 2000)

def zipf_trace(n, accesses, s=1.0, seed=2):
    r = random.Random(seed)
    w = [1 / (i + 1) ** s for i in range(n)]
    return [(k, 1) for k in r.choices(range(n), w, k=accesses)]

def hot_plus_loop(hot, loop, reps, seed=3):
    """a hot set re-read between each pass of a larger loop"""
    r = random.Random(seed); t = []
    for _ in range(reps):
        for i in range(loop):
            t.append((('l', i), 1))
            if r.random() < 0.5: t.append((('h', r.randrange(hot)), 1))
    return t

def one_shot_scans(hot, scan, reps, seed=4):
    """hot set + scans that never repeat"""
    r = random.Random(seed); t = []; nxt = 0
    for _ in range(reps):
        for _ in range(scan):
            t.append((('s', nxt), 1)); nxt += 1
            t.append((('h', int(r.random() ** 3 * hot)), 1))
    return t

def shifting_loop(npages, reps, shift_every, seed=5):
    """loop whose page set changes (a new working set) - does a frozen main thaw?"""
    t = []
    for rep in range(reps):
        base = (rep // shift_every) * npages
        t += [(base + i, 1) for i in range(npages)]
    return t

def main2():
    N = 2000
    table('zipf s=1.0, 5000 pages', zipf_trace(5000, 300000), 5000, [0.1, 0.3, 0.6], 50000)
    table('hot(200)+loop(2000)', hot_plus_loop(200, 2000, 40), 2200, [0.3, 0.6, 0.81, 1.0], 20000)
    table('hot(300)+one-shot scans', one_shot_scans(300, 2000, 30), 300, [1, 2, 4], 20000)
    table('loop 2000, working set shifts every 10 reps', shifting_loop(2000, 60, 10), 2000, [0.5, 0.81], 2000)
    table('loop 2000 x2 back-to-back (Q1,Q6 shape) x30', [(i, 1) for _ in range(60) for i in range(2000)], 2000, [0.5, 0.81, 1.0], 4000)

if __name__ == '__main__' and len(sys.argv) > 1: main2()
