FROM part AS p,
     supplier AS s,
     partsupp AS ps,
     nation AS n,
     region AS r
WHERE
  p.partkey = ps.partkey
  AND s.suppkey = ps.suppkey
  AND p.p_size = 15
  AND p.p_type LIKE '%BRASS'
  AND s.nationkey = n.nationkey
  AND n.regionkey = r.regionkey
  AND r.r_name = 'EUROPE'
  AND ps.ps_supplycost = (
    SELECT MIN(ps.ps_supplycost)
    FROM partsupp AS ps, supplier AS s, nation AS n, region AS r
    WHERE
      p.partkey = ps.partkey
      AND s.suppkey = ps.suppkey
      AND s.nationkey = n.nationkey
      AND n.regionkey = r.regionkey
      AND r.r_name = 'EUROPE'
  )
SELECT s.s_acctbal, s.s_name, s.s_address, s.s_phone, s.s_comment,
       p.partkey, p.p_mfgr, n.n_name
ORDER BY s.s_acctbal DESC, n.n_name, s.s_name, p.partkey
FETCH FIRST 100 ROWS ONLY
