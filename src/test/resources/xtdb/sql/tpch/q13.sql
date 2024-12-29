FROM customer AS c
  LEFT JOIN orders AS o
    ON c.custkey = o.custkey AND o.o_comment NOT LIKE '%special%requests%'
SELECT c.custkey, COUNT(o.orderkey) AS c_count
SELECT c_count, COUNT(*) AS custdist
ORDER BY custdist DESC, c_count DESC
