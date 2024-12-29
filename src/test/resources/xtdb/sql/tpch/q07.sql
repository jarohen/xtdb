SELECT supp_nation, cust_nation, l_year, SUM(volume) AS revenue
FROM (
    SELECT
      n1.n_name AS supp_nation,
      n2.n_name AS cust_nation,
      EXTRACT(YEAR FROM l.l_shipdate) AS l_year,
      l.l_extendedprice * (1 - l.l_discount) AS volume
    FROM supplier AS s,
         lineitem AS l,
         orders AS o,
         customer AS c,
         nation AS n1,
         nation AS n2
    WHERE s.suppkey = l.suppkey
      AND o.orderkey = l.orderkey
      AND c.custkey = o.custkey
      AND s.nationkey = n1.nationkey
      AND c.nationkey = n2.nationkey
      AND (
        (n1.n_name = 'FRANCE' AND n2.n_name = 'GERMANY')
        OR (n1.n_name = 'GERMANY' AND n2.n_name = 'FRANCE')
      )
      AND l.l_shipdate BETWEEN DATE '1995-01-01' AND DATE '1996-12-31'
  ) AS shipping
GROUP BY supp_nation, cust_nation, l_year
ORDER BY supp_nation, cust_nation, l_year
