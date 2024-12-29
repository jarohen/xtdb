FROM part AS p,
     supplier AS s,
     lineitem AS l,
     orders AS o,
     customer AS c,
     nation AS n1,
     nation AS n2,
     region AS r
WHERE p.partkey = l.partkey
  AND s.suppkey = l.suppkey
  AND l.orderkey = o.orderkey
  AND o.custkey = c.custkey
  AND c.nationkey = n1.nationkey
  AND n1.regionkey = r.regionkey
  AND r.r_name = 'AMERICA'
  AND s.nationkey = n2.nationkey
  AND o.o_orderdate BETWEEN DATE '1995-01-01' AND DATE '1996-12-31'
  AND p.p_type = 'ECONOMY ANODIZED STEEL'
SELECT EXTRACT(YEAR FROM o.o_orderdate) AS o_year,
       l.l_extendedprice * (1 - l.l_discount) AS volume,
       n2.n_name AS nation
GROUP BY o_year
SELECT o_year,
       SUM(CASE WHEN nation = 'BRAZIL' THEN volume ELSE 0 END) / SUM(volume) AS mkt_share
ORDER BY o_year
