SELECT nation, o_year, SUM(amount) AS sum_profit
FROM (
    SELECT n.n_name AS nation,
           EXTRACT(YEAR FROM o.o_orderdate) AS o_year,
           l.l_extendedprice * (1 - l.l_discount) - ps.ps_supplycost * l.l_quantity AS amount
    FROM part AS p,
         supplier AS s,
         lineitem AS l,
         partsupp AS ps,
         orders AS o,
         nation AS n
    WHERE s.suppkey = l.suppkey
      AND ps.suppkey = l.suppkey
      AND ps.partkey = l.partkey
      AND p.partkey = l.partkey
      AND o.orderkey = l.orderkey
      AND s.nationkey = n.nationkey
      AND p.p_name LIKE '%green%'
  ) AS profit
GROUP BY nation, o_year
ORDER BY nation, o_year DESC
