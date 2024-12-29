SELECT l.orderkey, o.o_orderdate, o.o_shippriority,
       SUM(l.l_extendedprice * (1 - l.l_discount)) AS revenue
FROM customer AS c,
     orders AS o,
     lineitem AS l
WHERE
  c.c_mktsegment = 'BUILDING'
  AND c.custkey = o.custkey
  AND l.orderkey = o.orderkey
  AND o.o_orderdate < DATE '1995-03-15'
  AND l.l_shipdate > DATE '1995-03-15'
GROUP BY l.orderkey, o.o_orderdate, o.o_shippriority
ORDER BY revenue DESC, o.o_orderdate
FETCH FIRST 10 ROWS ONLY
