SELECT p.p_brand, p.p_type, p.p_size,
       COUNT(DISTINCT ps.suppkey) AS supplier_cnt
FROM partsupp AS ps,
     part AS p
WHERE p.partkey = ps.partkey
  AND p.p_brand <> 'Brand#45'
  AND p.p_type NOT LIKE 'MEDIUM POLISHED%'
  AND p.p_size IN (49, 14, 23, 45, 19, 3, 36, 9)
  AND ps.suppkey NOT IN (
    SELECT s.suppkey
    FROM supplier AS s
    WHERE s.s_comment LIKE '%Customer%Complaints%'
  )
GROUP BY p.p_brand, p.p_type, p.p_size
ORDER BY supplier_cnt DESC, p.p_brand, p.p_type, p.p_size
