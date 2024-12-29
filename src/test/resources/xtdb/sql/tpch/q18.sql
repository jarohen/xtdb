SELECT c.c_name, c.custkey, o.orderkey, o.o_orderdate, o.o_totalprice, SUM(l.l_quantity) sum_qty
FROM customer AS c,
     orders AS o,
     lineitem AS l
WHERE o.orderkey IN (
        SELECT l.orderkey
        FROM
          lineitem AS l
        GROUP BY
          l.orderkey
        HAVING
          SUM(l.l_quantity) > 300
      )
  AND c.custkey = o.custkey
  AND o.orderkey = l.orderkey
GROUP BY c.c_name, c.custkey, o.orderkey, o.o_orderdate, o.o_totalprice
ORDER BY o.o_totalprice DESC, o.o_orderdate
FETCH FIRST 100 ROWS ONLY
