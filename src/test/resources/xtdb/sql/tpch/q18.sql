SELECT
  c.c_name,
  c._id AS c_custkey,
  o._id AS o_orderkey,
  o.o_orderdate,
  o.o_totalprice,
  SUM(l.l_quantity) sum_qty
FROM
  customer AS c,
  orders AS o,
  lineitem AS l
WHERE
  o._id IN (
    SELECT l.l_orderkey
    FROM
      lineitem AS l
    GROUP BY
      l.l_orderkey
    HAVING
      SUM(l.l_quantity) > 300
  )
  AND c._id = o.o_custkey
  AND o._id = l.l_orderkey
GROUP BY
  c.c_name,
  c._id,
  o._id,
  o.o_orderdate,
  o.o_totalprice
ORDER BY
  o.o_totalprice DESC,
  o.o_orderdate
FETCH FIRST 100 ROWS ONLY
