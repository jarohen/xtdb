FROM supplier AS s,
     lineitem AS l1,
     orders AS o,
     nation AS n
WHERE s.suppkey = l1.suppkey
  AND o.orderkey = l1.orderkey
  AND o.o_orderstatus = 'F'
  AND l1.l_receiptdate > l1.l_commitdate
  AND EXISTS (
        FROM lineitem l2
        WHERE l2.orderkey = l1.orderkey
          AND l2.suppkey <> l1.suppkey
      )
  AND NOT EXISTS (
        FROM lineitem l3
        WHERE
          l3.orderkey = l1.orderkey
          AND l3.suppkey <> l1.suppkey
          AND l3.l_receiptdate > l3.l_commitdate
      )
  AND s.nationkey = n.nationkey
  AND n.n_name = 'SAUDI ARABIA'
SELECT s.s_name, COUNT(*) AS numwait
ORDER BY numwait DESC, s.s_name
FETCH FIRST 100 ROWS ONLY
