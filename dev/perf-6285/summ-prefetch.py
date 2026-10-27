import re,sys,glob
def q(f):
    r={}
    for m in re.finditer(r'"stage":"(cold|hot)-queries-q(\d+)-[^"]*","time-taken-ms":(\d+)',open(f).read()):
        r[(m[1],int(m[2]))]=int(m[3])
    t={}
    for m in re.finditer(r'"stage":"(cold|hot)-queries","time-taken-ms":(\d+)',open(f).read()): t[m[1]]=int(m[2])
    return r,t
def stats(f):
    ls=[l for l in open(f) if 'MEMCACHE-STATS' in l]
    d=dict(re.findall(r'(\w+)=(\d+)',ls[-1])) if ls else {}
    return d
print("tag  coldQ1 coldQ6 hotQ1 hotQ6 coldAll hotAll | hits misses waits pfIssued pfHits pfWasted pfRefused")
for reg in ('default','twelve'):
  for d in (0,1,2,4,8):
    tag=f'{reg}-d{d}'
    try: r,t=q(f'{tag}.log'); s=stats(f'{tag}.out')
    except Exception as e: print(tag,'ERR',e); continue
    print(tag,r.get(('cold',1)),r.get(('cold',6)),r.get(('hot',1)),r.get(('hot',6)),t.get('cold'),t.get('hot'),'|',*[s.get(k) for k in ('hits','misses','waits','pfIssued','pfHits','pfWasted','pfRefused')])
