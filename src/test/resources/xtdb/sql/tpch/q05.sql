FROM customer AS c,
     orders AS o,
     lineitem AS l,
     supplier AS s,
     nation AS n,
     region AS r
WHERE
  c.custkey = o.custkey
  AND l.orderkey = o.orderkey
  AND l.suppkey = s.suppkey
  AND c.nationkey = s.nationkey
  AND s.nationkey = n.nationkey
  AND n.regionkey = r.regionkey
  AND r.r_name = 'ASIA'
  AND o.o_orderdate >= DATE '1994-01-01'
  AND o.o_orderdate < DATE '1994-01-01' + INTERVAL '1' YEAR
SELECT n.n_name, SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue
ORDER BY revenue DESC
