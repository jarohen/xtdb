SELECT s.s_name, s.s_address
FROM supplier AS s, nation AS n
WHERE s.suppkey IN (
        SELECT ps.suppkey
        FROM partsupp AS ps
        WHERE ps.partkey IN (
                SELECT p.partkey
                FROM part AS p
                WHERE p.p_name LIKE 'forest%'
              )
          AND ps.ps_availqty > (
                SELECT 0.5 * SUM(l.l_quantity)
                FROM lineitem AS l
                WHERE l.partkey = ps.partkey
                  AND l.suppkey = ps.suppkey
                  AND l.l_shipdate >= DATE '1994-01-01'
                  AND l.l_shipdate < DATE '1994-01-01' + INTERVAL '1' YEAR
              )
      )
  AND s.nationkey = n.nationkey
  AND n.n_name = 'CANADA'
ORDER BY s.s_name
