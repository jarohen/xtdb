FROM partsupp AS ps,
     supplier AS s,
     nation AS n
WHERE ps.suppkey = s.suppkey
  AND s.nationkey = n.nationkey
  AND n.n_name = 'GERMANY'
GROUP BY ps.partkey
HAVING
  SUM(ps.ps_supplycost * ps.ps_availqty) > (
    SELECT SUM(ps.ps_supplycost * ps.ps_availqty) * 0.0001
    FROM partsupp AS ps,
         supplier AS s,
         nation AS n
    WHERE ps.suppkey = s.suppkey
      AND s.nationkey = n.nationkey
      AND n.n_name = 'GERMANY'
  )
SELECT ps.partkey,
       SUM(ps.ps_supplycost * ps.ps_availqty) AS value
ORDER BY value DESC
