(ns xtdb.datasets.tpch.xtql)

(defn- with-args [q args]
  (vary-meta q assoc ::args (vec args)))

(def q1
  '(-> (from :lineitem [l-shipdate l-quantity
                        l-extendedprice l-discount l-tax
                        l-returnflag l-linestatus])

       (where (<= l-shipdate #time/date "1998-09-02"))

       (aggregate l-returnflag
                  l-linestatus
                  {:sum-qty (sum l-quantity)
                   :sum-base-price (sum l-extendedprice)
                   :sum-disc-price (sum (* l-extendedprice (- 1 l-discount)))
                   :sum-charge (sum (* (* l-extendedprice (- 1 l-discount)) (+ 1 l-tax)))
                   :avg-qty (avg l-quantity)
                   :avg-price (avg l-extendedprice)
                   :avg-disc (avg l-discount)
                   :count-order (row-count)})

       (order-by l-returnflag l-linestatus)))

(def q1-str "XTQL
  FROM lineitem {l_shipdate, l_quantity, l_extendedprice, l_discount, l_tax, l_returnflag, l_linestatus}
  WHERE l_shipdate <= DATE '1998-09-02'
  AGGREGATE {
    l_returnflag, l_linestatus,
    sum_qty: SUM(l_quantity),
    sum_base_price: SUM(l_extendedprice),
    sum_disc_price: SUM(l_extendedprice * (1 - l_discount)),
    sum_charge: SUM(l_extendedprice * (1 - l_discount) * (1 + l_tax)),
    avg_qty: AVG(l_quantity),
    avg_price: AVG(l_extendedprice),
    avg_disc: AVG(l_discount),
    count_order: COUNT(*)
  }

  ORDER BY l_returnflag, l_linestatus")

(def q2
  '(-> (unify (from :part [{:xt/id p} p-mfgr {:p-size 15} p-type])
              (where (like p-type "%BRASS"))

              (from :partsupp [{:ps-partkey p, :ps-suppkey s} ps-supplycost])
              (from :supplier [{:xt/id s, :s-nationkey n}
                               s-acctbal s-address s-name s-phone s-comment])
              (from :nation [{:xt/id n, :n-regionkey r} n-name])
              (from :region [{:xt/id r, :r-name "EUROPE"}])

              (where (= ps-supplycost
                        (q (-> (unify (from :partsupp [{:ps-partkey $p, :ps-suppkey s} ps-supplycost])
                                      (from :supplier [{:xt/id s, :s-nationkey n}])
                                      (from :nation [{:xt/id n, :n-regionkey r}])
                                      (from :region [{:xt/id r, :r-name "EUROPE"}]))
                               (aggregate {:min-supplycost (min ps-supplycost)}))
                           {:args [p]}))))

       (order-by {:val s-acctbal, :dir :desc} n-name s-name p)
       (limit 100)))

(def q2-str "XTQL
  UNIFY(
    FROM part {xt$id: p, p_mfgr, p_size: 15, p_type},
    WHERE p_type LIKE '%BRASS',

    FROM partsupp {ps_partkey: p, ps_suppkey: s, ps_supplycost},
    FROM supplier {
      xt$id: s, s_nationkey: n,
      s_acctbal, s_address, s_name, s_phone, s_comment
    },
    FROM nation {xt$id: n, n_regionkey: r, n_name},
    FROM region {xt$id: r, r_name: 'EUROPE'}

    WHERE ps_supplycost = ({p} ->
      UNIFY(
        FROM partsupp {ps_partkey: p, ps_suppkey: s, ps_supplycost},
        FROM supplier {xt$id: s, s_nationkey: n},
        FROM nation {xt$id: n, n_regionkey: r, n_name},
        FROM region {xt$id: r, r_name: 'EUROPE'}
      )
      AGGREGATE { min_supplycost: MIN(ps_supplycost) }
    )
  )

  ORDER BY s_acctbal DESC, n_name, s_name, p
  LIMIT 100")

(def q3
  (-> '(-> (unify (from :customer [{:xt/id c, :c-mktsegment segment}])

                  (from :orders [{:xt/id o, :o-custkey c} o-shippriority o-orderdate])
                  (where (< o-orderdate #time/date "1995-03-15"))

                  (from :lineitem [{:l-orderkey o} l-discount l-extendedprice l-shipdate])
                  (where (> l-shipdate #time/date "1995-03-15")))

           (aggregate {:l-orderkey o}
                      {:revenue (sum (* l-extendedprice (- 1 l-discount)))}
                      o-orderdate o-shippriority)
           (order-by {:val revenue, :dir :desc} o-orderdate)
           (limit 10))

      (with-args [{:segment "BUILDING"}])))

(def q3-str "XTQL {segment: ?} ->
  UNIFY(
    FROM customer {xt$id: c, c_mktsegment: segment},

    FROM orders {xt$id: o, o_custkey: c, o_shippriority, o_orderdate},
    WHERE o_orderdate < DATE '1995-03-15',

    FROM lineitem {l_orderkey: o, l_discount, l_extendedprice, l_shipdate},
    WHERE l_shipdate > DATE '1995-03-15'
  )

  AGGREGATE {
    l_orderkey: o,
    revenue: SUM(l_extendedprice * (1 - l_discount)),
    o_orderdate, o_shippriority
  }

  ORDER BY revenue DESC, o_orderdate
  LIMIT 10")

(def q4
  '(-> (from :orders [{:xt/id o} o-orderdate o-orderpriority])
       (where (>= o-orderdate #time/date "1993-07-01")
              (< o-orderdate #time/date "1993-10-01")

              (exists? (-> (from :lineitem [{:l-orderkey $o} l-commitdate l-receiptdate])
                           (where (< l-commitdate l-receiptdate)))
                       {:args [o]}))

       (aggregate o-orderpriority {:order-count (count o)})
       (order-by o-orderpriority)))

(def q4-str "XTQL
  FROM orders {xt$id: o, o_orderdate, o_orderpriority}
  WHERE o_orderdate >= DATE '1993-07-01'
  WHERE o_orderdate < DATE '1993-10-01'
  WHERE EXISTS ({o} ->
          FROM lineitem {l_orderkey: o, l_commitdate, l_receiptdate}
          WHERE l_commitdate < l_receiptdate
        )

  AGGREGATE {o_orderpriority, order_count: COUNT(o)}
  ORDER BY o_orderpriority")

(def q5
  (-> '(-> (unify (from :orders [{:xt/id o, :o-custkey c} o-orderdate])
                  (where (>= o-orderdate #time/date "1994-01-01")
                         (< o-orderdate #time/date "1995-01-01"))

                  (from :lineitem [{:l-orderkey o, :l-suppkey s}
                                   l-extendedprice l-discount])

                  (from :supplier [{:xt/id s, :s-nationkey n}])
                  (from :customer [{:xt/id c, :c-nationkey n}])
                  (from :nation [{:xt/id n, :n-regionkey r} n-name])
                  (from :region [{:xt/id r, :r-name region}]))
           (aggregate n-name {:revenue (sum (* l-extendedprice (- 1 l-discount)))})
           (order-by {:val revenue, :dir :desc}))

      (with-args [{:region "ASIA"}])))

(def q5-str "XTQL {region: ?} ->
  UNIFY(
    FROM orders {xt$id: o, o_custkey: c, o_orderdate},
    WHERE o_orderdate >= DATE '1994-01-01',
    WHERE o_orderdate < DATE '1995-01-01',

    FROM lineitem {l_orderkey: o, l_suppkey: s, l_extendedprice, l_discount},
    FROM supplier {xt$id: s, s_nationkey: n},
    FROM customer {xt$id: c, c_nationkey: n},
    FROM nation {xt$id: n, n_regionkey: r, n_name},
    FROM region {xt$id: r, r_name: region}
  )

  AGGREGATE {n_name, revenue: SUM(l_extendedprice * (1 - l_discount)}
  ORDER BY revenue DESC")

(def q6
  '(-> (unify (from :lineitem [l-shipdate l-quantity l-extendedprice l-discount])
              (where (>= l-shipdate #time/date "1994-01-01")
                     (< l-shipdate #time/date "1995-01-01")
                     (>= l-discount 0.05)
                     (<= l-discount 0.07)
                     (< l-quantity 24.0)))

       (aggregate {:revenue (sum (* l-extendedprice l-discount))})))

(def q6-str "XTQL
  FROM lineitem {l_shipdate, l_quantity, l_extendedprice, l_discount}

  WHERE l_shipdate FROM DATE '1994-01-01' UNTIL DATE '1995-01-01'
  WHERE l_discount BETWEEN 0.05 AND 0.07
  WHERE l_quantity < 24.0

  AGGREGATE {revenue: SUM(l_extendedprice * l_discount)}
")

(def q7
  '(-> (unify (from :orders [{:o-custkey c}])
              (from :lineitem [{:l-orderkey o, :l-suppkey s}
                               l-shipdate l-discount l-extendedprice])

              (where (>= l-shipdate #time/date "1995-01-01")
                     (<= l-shipdate #time/date "1996-12-31"))

              (from :supplier [{:xt/id s, :s-nationkey n1}])
              (from :nation [{:xt/id n1, :n-name supp-nation}])
              (from :customer [{:xt/id c, :c-nationkey n2}])
              (from :nation [{:xt/id n2, :n-name cust-nation}])

              (where (or (and (= "FRANCE" supp-nation)
                              (= "GERMANY" cust-nation))
                         (and (= "GERMANY" supp-nation)
                              (= "FRANCE" cust-nation)))))

       (with {:l-year (extract "YEAR" l-shipdate)})

       (aggregate supp-nation cust-nation l-year {:revenue (sum (* l-extendedprice (- 1 l-discount)))})

       (order-by supp-nation cust-nation l-year)))

(def q7-str "XTQL
  UNIFY (
    FROM orders {o_custkey: c},
    FROM lineitem {l_orderkey: o, l_suppkey: s, l_shipdate, l_discount, l_extendedprice}
    WHERE l_shipdate >= DATE '1995-01-01',
    WHERE l_shipdate <= DATE '1996-12-31',

    FROM supplier {xt$id: s, s_nationkey: n1},
    FROM nation {xt$id: n1, n_name: supp_nation},
    FROM customer {xt$id: c, c_nationkey: n2},
    FROM nation {xt$id: n2, n_name: cust_nation}

    WHERE (supp_nation = 'FRANCE' AND cust_nation = 'GERMANY') OR
          (supp_nation = 'GERMANY' AND cust_nation = 'FRANCE')
  )

  WITH {l_year: EXTRACT('YEAR', l_shipdate)}

  AGGREGATE {
    supp_nation, cust_nation, l_year,
    revenue: SUM(l_extendedprice * (1 - l_discount))
  }

  ORDER BY supp_nation, cust_nation, l_year")

(def q8
  '(-> (unify (from :orders [{:xt/id o, :o-custkey c} o-orderdate])
              (where (>= o-orderdate #time/date "1995-01-01")
                     (<= o-orderdate #time/date "1996-12-31"))

              (from :lineitem [{:xt/id l, :l-orderkey o, :l-suppkey s, :l-partkey p}
                               l-extendedprice l-discount])

              (from :customer [{:xt/id c, :c-nationkey n1}])
              (from :nation [{:xt/id n1, :n-regionkey r1}])
              (from :region [{:xt/id r1, :r-name "AMERICA"}])

              (from :supplier [{:xt/id s, :s-nationkey n2}])
              (from :nation [{:xt/id n2, :n-name nation}])

              (from :part [{:xt/id p, :p-type "ECONOMY ANODIZED STEEL"}]))

       (return {:o-year (extract "YEAR" o-orderdate)}
               {:volume (* l-extendedprice (- 1 l-discount))}
               nation)

       (aggregate o-year
                  {:mkt-share (/ (sum (if (= "BRAZIL" nation) volume 0))
                                 (sum volume))})

       (order-by o-year)))

(def q8-str "XTQL
  UNIFY (
    FROM orders {xt$id: o, o_custkey: c, o_orderdate},
    WHERE o_orderdate >= DATE '1995-01-01',
    WHERE o_orderdate <= DATE '1996-12-31',

    FROM lineitem {xt$id: l, l_orderkey: o, l_suppkey: s, l_partkey: p, l_extendedprice, l_discount},
    FROM customer {xt$id: c, c_nationkey: n1},
    FROM nation {xt$id: n1, n_regionkey: r1},
    FROM region {xt$id: r1, r_name: 'AMERICA'},
    FROM supplier {xt$id: s, s_nationkey: n2},
    FROM nation {xt$id: n2, n_name: nation},
    FROM part {xt$id: p, p_type: 'ECONOMY ANODIZED STEEL'}
  )
  RETURN {o_year: EXTRACT('YEAR', o_orderdate), volume: l_extendedprice * (1 - l_discount), nation}
  AGGREGATE { o_year, mkt_share: SUM(IF(nation = 'BRAZIL', volume, 0)) / SUM(volume) }
  ORDER BY o_year")

(def q9
  '(-> (unify (from :part [{:xt/id p} p-name])
              (where (like p-name "%green%"))

              (from :orders [{:xt/id o} o-orderdate])

              (from :lineitem [{:l-orderkey o, :l-suppkey s, :l-partkey p}
                               l-quantity l-extendedprice l-discount])

              (from :partsupp [{:ps-partkey p, :ps-suppkey s} ps-supplycost])

              (from :supplier [{:xt/id s, :s-nationkey n}])
              (from :nation [{:xt/id n, :n-name nation}]))

       (with {:o-year (extract "YEAR" o-orderdate)})

       (aggregate nation o-year
                  {:sum-profit (sum (- (* l-extendedprice (- 1 l-discount))
                                       (* ps-supplycost l-quantity)))})))

(def q9-str "XTQL
  UNIFY(
    FROM part {xt$id: p, p_name},
    WHERE p_name LIKE '%green%',

    FROM orders {xt$id: o, o_orderdate},
    FROM lineitem {l_orderkey: o, l_suppkey: s, l_partkey: p, l_quantity, l_extendedprice, l_discount},
    FROM partsupp {ps_partkey: p, ps_suppkey: s, ps_supplycost},
    FROM supplier {xt$id: s, s_nationkey: n},
    FROM nation {xt$id: n, n_name: nation}
  )

  WITH {o_year: EXTRACT('YEAR', o_orderdate)}

  AGGREGATE {
    nation, o_year,
    sum_profit: SUM(l_extendedprice * (1 - l_discount) - ps_supplycost * l_quantity)
  }")

(def q10
  '(-> (unify (from :orders [{:xt/id o, :o-custkey c} o-orderdate])
              (where (>= o-orderdate #time/date "1993-10-01")
                     (< o-orderdate #time/date "1994-01-01"))

              (from :customer [{:xt/id c, :c-nationkey n}
                               c-name c-address c-phone
                               c-acctbal c-comment])

              (from :nation [{:xt/id n, :n-name n-name}])

              (from :lineitem [{:l-orderkey o, :l-returnflag "R"}
                               l-extendedprice l-discount]))

       (aggregate c c-name
                  {:revenue (sum (* l-extendedprice (- 1 l-discount)))}
                  c-acctbal c-phone n-name c-address c-comment)

       (order-by {:val revenue, :dir :desc})
       (limit 20)))

(def q10-str "XTQL
  UNIFY(
    FROM customer {xt$id: c, c_custkey, c_name, c_acctbal, c_phone, c_address, c_comment, c_nationkey: n},
    FROM orders {xt$id: o, o_custkey: c, o_orderdate},
    WHERE o_orderdate >= DATE '1993-10-01',
    WHERE o_orderdate < DATE '1993-10-01' + INTERVAL '3' MONTH,
    FROM lineitem {l_orderkey: o, l_extendedprice, l_discount, l_returnflag: 'R'},
    FROM nation {xt$id: n, n_name}
  )

  AGGREGATE {c_custkey, c_name, c_acctbal, c_phone, n_name, c_address, c_comment, revenue: SUM(l_extendedprice * (1 - l_discount))}
  ORDER BY revenue DESC
  LIMIT 20")

(def q11
  '(-> (unify (join (-> (unify (from :partsupp [{:ps-suppkey s} ps-availqty ps-supplycost])
                               (from :supplier [{:xt/id s, :s-nationkey n}])
                               (from :nation [{:xt/id n, :n-name "GERMANY"}]))
                        (aggregate {:total-value (sum (* ps-supplycost ps-availqty))}))

                    [total-value])

              (join (-> (unify (from :partsupp [{:ps-suppkey s} ps-availqty ps-supplycost ps-partkey])
                               (from :supplier [{:xt/id s, :s-nationkey n}])
                               (from :nation [{:xt/id n, :n-name "GERMANY"}]))
                        (aggregate ps-partkey {:value (sum (* ps-supplycost ps-availqty))}))

                    [ps-partkey value]))

       (where (> value (* 0.0001 total-value)))
       (return ps-partkey value)
       (order-by {:val value, :dir :desc})))

(def q11-str "XTQL
  UNIFY(
    JOIN(
      UNIFY(
        FROM partsupp {ps_suppkey: s, ps_availqty, ps_supplycost},
        FROM supplier {xt$id: s, s_nationkey: n},
        FROM nation {xt$id: n, n_name: 'GERMANY'}
      )
      AGGREGATE { total_value: SUM(ps_supplycost * ps_availqty) }
    ) {total_value},

    JOIN (
      UNIFY(
        FROM partsupp {ps_suppkey: s, ps_availqty, ps_supplycost, ps_partkey},
        FROM supplier {xt$id: s, s_nationkey: n},
        FROM nation {xt$id: n, n_name: 'GERMANY'}
      )
      AGGREGATE { ps_partkey, value: SUM(ps_supplycost * ps_availqty) }
    ) {ps_partkey, value}
  )

  WHERE value > 0.0001 * total_value
  RETURN {ps_partkey, value}
  ORDER BY value DESC")

(def q12
  (-> '(-> (unify (from :lineitem [{:l-orderkey o} l-receiptdate l-commitdate l-shipdate l-shipmode])
                  ;; TODO `in?`
                  (where (in? l-shipmode $ship-modes)

                         (>= l-receiptdate #time/date "1994-01-01")
                         (< l-receiptdate #time/date "1995-01-01")
                         (< l-commitdate l-receiptdate)
                         (< l-shipdate l-commitdate))

                  (from :orders [{:xt/id o} o-orderpriority]))

           (aggregate l-shipmode
                      {:high-line-count (sum (case o-orderpriority "1-URGENT" 1, "2-HIGH" 1, 0))}
                      {:low-line-count (sum (case o-orderpriority "1-URGENT" 0, "2-HIGH" 0, 1))})

           (order-by l-shipmode))

      (with-args [{:ship-modes #{"MAIL" "SHIP"}}])))

(def q12-str "XTQL {ship_modes: ?} ->
  UNIFY(
    FROM lineitem {l_orderkey: o, l_receiptdate, l_commitdate, l_shipdate, l_shipmode},
    WHERE l_shipmode IN ship_modes,
    WHERE l_receiptdate >= DATE '1994-01-01',
    WHERE l_receiptdate < DATE '1995-01-01',
    WHERE l_commitdate < l_receiptdate,
    WHERE l_shipdate < l_commitdate,

    FROM orders {xt$id: o, o_orderpriority}
  )

  AGGREGATE {
    l_shipmode,
    high_line_count: SUM(CASE o_orderpriority WHEN '1-URGENT' THEN 1 WHEN '2-HIGH' THEN 1 ELSE 0 END),
    low_line_count: SUM(CASE o_orderpriority WHEN '1-URGENT' THEN 0 WHEN '2-HIGH' THEN 0 ELSE 1 END)
  }

  ORDER BY l_shipmode")

(def q13
  '(-> (unify (from :customer [{:xt/id c}])
              (left-join (unify (from :orders [{:xt/id o, :o-custkey c} o-comment])
                                (where (not (like o-comment "%special%requests%"))))
                         [c o]))
       (aggregate c {:c-count (count o)})
       (aggregate c-count {:custdist (row-count)})
       (order-by {:val custdist, :dir :desc} {:val c-count, :dir :desc})))

(def q13-str "XTQL
  UNIFY (
    FROM customer {xt$id: c},
    LEFT JOIN (
      FROM orders {xt$id: o, o_custkey: c, o_comment}
      WHERE o_comment NOT LIKE '%special%requests%'
    ) {c, o}
  )

  AGGREGATE {c, c_count: COUNT(o)}
  AGGREGATE {c_count, custdist: COUNT(*)}
  ORDER BY custdist DESC, c_count DESC")

(def q14
  '(-> (unify (from :lineitem [{:l-partkey p} l-shipdate l-extendedprice l-discount])
              (where (>= l-shipdate #time/date "1995-09-01")
                     (< l-shipdate #time/date "1995-10-01"))

              (from :part [{:xt/id p} p-type]))

       (aggregate {:promo (sum (if (like p-type "PROMO%")
                                (* l-extendedprice (- 1 l-discount))
                                0))}
                  {:total (sum (* l-extendedprice (- 1 l-discount)))})

       (return {:promo-revenue (* 100 (/ promo total))})))

(def q14-str "XTQL
  UNIFY(
    FROM lineitem {l_partkey: p, l_shipdate, l_extendedprice, l_discount}
    WHERE l_shipdate >= DATE '1995-09-01',
    WHERE l_shipdate < DATE '1995-10-01',
    FROM part {xt$id: p, p_type}
  )

  AGGREGATE {
    promo: SUM(IF(p_type LIKE 'PROMO%', l_extendedprice * (1 - l_discount), 0)),
    total: SUM(l_extendedprice * (1 - l_discount)
  }

  RETURN {promo_revenue: 100 * promo / total}
  ")

(def q15
  '(letfn [(revenue []
             (q (-> (from :lineitem [{:l-suppkey s} l-shipdate l-extendedprice l-discount])
                    (where (>= l-shipdate #time/date "1996-01-01")
                           (< l-shipdate #time/date "1996-04-01"))
                    (aggregate s {:total-revenue (sum (* l-extendedprice (- 1 l-discount)))}))))]

     (-> (unify (call (revenue) [s total-revenue])

                (where (= total-revenue
                          (q (-> (call (revenue) [total-revenue])
                                 (aggregate {:max-revenue (max total-revenue)})))))

                (from :supplier [{:xt/id s} s-name s-address s-phone]))

         (return s s-name s-address s-phone total-revenue))))

(def q15-str "XTQL
  WITH revenue AS (
    FROM lineitem {l_suppkey: s, l_shipdate, l_extendedprice, l_discount}
    WHERE l_shipdate FROM DATE '1996-01-01' UNTIL '1996-04-01'
    AGGREGATE {s, total_revenue: SUM(l_extendedprice * (1 - l_discount))}
  )

  UNIFY(
    FROM revenue {s, total_revenue},
    WHERE total_revenue = ({} ->
      FROM revenue {total_revenue}
      AGGREGATE {max_revenue: MAX(total_revenue)}
    ),

    FROM supplier {xt$id: s, s_name, s_address, s_phone}
  )

  RETURN {s, s_name, s_address, s_phone, total_revenue}")

(def q16
  (-> '(-> (unify (from :part [{:xt/id p} p-brand p-type p-size])
                  ;; TODO `in?`
                  (where (in? p-size sizes)
                         (<> p-brand "Brand#45")
                         (not (like p-type "MEDIUM POLISHED%")))

                  (from :partsupp [{:ps-partkey p, :ps-suppkey s}])

                  (where (not (exists? (-> (from :supplier [{:xt/id $s} s-comment])
                                           (where (like "%Customer%Complaints%" s-comment)))
                                       {:args [s]}))))
           (aggregate p-brand p-type p-size {:supplier-cnt (count-distinct s)})
           (order-by {:val supplier-cnt, :dir :desc} p-brand p-type p-size))

      (with-args [{:sizes #{3 9 14 19 23 36 45 49}}])))

(def q16-str "XTQL {sizes: ?} ->
  UNIFY(
    FROM part {xt$id: p, p_brand, p_type, p_size},
    WHERE p_size IN sizes,
    WHERE p_brand <> 'Brand#45',
    WHERE p_type NOT LIKE 'MEDIUM POLISHED%',

    FROM partsupp {ps_partkey: p, ps_suppkey: s},
    WHERE NOT EXISTS ({s} ->
      FROM supplier {xt$id: s, s_comment}
      WHERE s_comment LIKE '%Customer%Complaints%'
    )
  )

  AGGREGATE {p_brand, p_type, p_size, supplier_cnt: COUNT(DISTINCT s)}
  ORDER BY supplier_cnt DESC, p_brand, p_type, p_size
")

(def q17
  '(-> (unify (from :part [{:xt/id p, :p-brand "Brand#23", :p-container "MED BOX"}])
              (from :lineitem [{:l-partkey p} l-quantity l-extendedprice])

              (join (-> (from :lineitem [{:l-partkey $p} l-quantity])
                        (aggregate {:avg-quantity (avg l-quantity)}))
                    {:args [p]
                     :bind [avg-quantity]})

              (where (< l-quantity (* 0.2 avg-quantity))))

       (aggregate {:sum-extendedprice (sum l-extendedprice)})

       (return {:avg-yearly (/ sum-extendedprice 7.0)})))

(def q17-str "XTQL
  UNIFY(
    FROM part {xt$id: p, p_brand: 'Brand#23', p_container: 'MED BOX'},
    FROM lineitem {l_partkey: p, l_quantity, l_extendedprice},

    JOIN ({p} ->
      FROM lineitem {l_partkey: p, l_quantity}
      AGGREGATE {avg_quantity: AVG(l_quantity)}
    ) {avg_quantity}
    WHERE l_quantity < 0.2 * avg_quantity
  )

  AGGREGATE {sum_extendedprice: SUM(l_extendedprice)}
  RETURN {avg_yearly: sum_extendedprice / 7.0}
")

(def q18
  '(-> (unify (from :customer [{:xt/id c} c-name])
              (from :orders [{:xt/id o, :o-custkey c} o-orderdate o-totalprice]))

       ;; TODO `in?`
       (where (in? o
                   (q (-> (from :lineitem [{:l-orderkey o} l-quantity])
                          (aggregate o {:sum-quantity (sum l-quantity)})
                          (where (> sum-quantity 300.0))
                          (return o)))))

       (return c-name {:c-custkey c} {:o-orderkey o} o-orderdate o-totalprice sum-qty)

       (order-by {:val o-totalprice, :dir :desc} o-orderdate)
       (limit 100)))

(def q18-str "XTQL
  UNIFY(
    FROM customer {xt$id: c, c_name},
    FROM orders {xt$id: o, o_custkey: c, o_orderdate, o_totalprice}
  )

  WHERE o IN ({} ->
    FROM lineitem { l_orderkey: o, l_quantity }
    AGGREGATE { sum_quantity: SUM(l_quantity) }
    WHERE sum_quantity > 300.0
    RETURN o
  )

  RETURN { c_name, c_custkey: c, o_orderkey: o, o_orderdate, o_totalprice, sum_qty: sum_quantity }
  ORDER BY o_totalprice DESC, o_orderdate
  LIMIT 100
")

(def q19
  (-> '(-> (unify (from :part [{:xt/id p} p-size p-brand p-container])

                  (from :lineitem
                        [{:l-shipinstruct "DELIVER IN PERSON", :l-partkey p}
                         l-shipmode l-discount l-extendedprice l-quantity])

                  ;; TODO `in?`
                  (where (in? l-shipmode ship-modes)

                         (or (and (= p-brand "Brand#12")
                                  ;; TODO `in?`
                                  (in? p-container #{"SM CASE" "SM BOX" "SM PACK" "SM PKG"})
                                  (>= l-quantity 1.0)
                                  (<= l-quantity 11.0)
                                  (>= p-size 1)
                                  (<= p-size 5))

                             (and (= p-brand "Brand#23")
                                  ;; TODO `in?`
                                  (in? p-container #{"MED BAG" "MED BOX" "MED PKG" "MED PACK"})
                                  (>= l-quantity 10.0)
                                  (<= l-quantity 20.0)
                                  (>= p-size 1)
                                  (<= p-size 10))

                             (and (= p-brand "Brand#34")
                                  ;; TODO `in?`
                                  (in? p-container #{"LG CASE" "LG BOX" "LG PACK" "LG PKG"})
                                  (>= l-quantity 20.0)
                                  (<= l-quantity 30.0)
                                  (>= p-size 1)
                                  (<= p-size 15)))))

           (aggregate {:revenue (sum (* l-extendedprice (- 1 l-discount)))}))

      (with-args [{:ship-modes #{"AIR" "AIR REG"}}])))

(def q19-str "XTQL {ship_modes: ?} ->
  UNIFY(
    FROM part {xt$id: p, p_size, p_brand, p_container},
    FROM lineitem {l_shipinstruct: 'DELIVER IN PERSON', l_partkey: p, l_shipmode, l_discount, l_extendedprice, l_quantity}
    WHERE l_shipmode IN ('AIR', 'AIR REG'),
    WHERE (
            (p_brand = 'Brand#12' AND p_container IN ('SM CASE', 'SM BOX', 'SM PACK', 'SM PKG') AND l_quantity >= 1.0 AND l_quantity <= 11.0 AND p_size >= 1 AND p_size <= 5) OR
            (p_brand = 'Brand#23' AND p_container IN ('MED BAG', 'MED BOX', 'MED PKG', 'MED PACK') AND l_quantity >= 10.0 AND l_quantity <= 20.0 AND p_size >= 1 AND p_size <= 10) OR
            (p_brand = 'Brand#34' AND p_container IN ('LG CASE', 'LG BOX', 'LG PACK', 'LG PKG') AND l_quantity >= 20.0 AND l_quantity <= 30.0 AND p_size >= 1 AND p_size <= 15)
          )
  )

  AGGREGATE { revenue: SUM(l_extendedprice * (1 - l_discount)) }")

(def q20
  '(-> (unify (from :part [{:xt/id p} p-name])
              (where (like p-name "forest%"))

              (from :partsupp [{:ps-suppkey s, :ps-partkey p} ps-availqty])
              (from :supplier [{:xt/id s, :s-nationkey n} s-name s-address])
              (from :nation [{:xt/id n, :n-name "CANADA"}])

              (where (> ps-availqty
                        (* 0.5
                           (q (-> (unify (from :lineitem [{:l-partkey $p, :l-suppkey $s} l-shipdate l-quantity])
                                         (where (>= l-shipdate #time/date "1994-01-01")
                                                (< l-shipdate #time/date "1995-01-01")))
                                  (aggregate {:sum-quantity (sum l-quantity)}))
                              {:args [s p]})))))

       (return s-name s-address)
       (order-by s-name)))

(def q20-str "XTQL
  UNIFY(
    FROM part {xt$id: p, p_name},
    WHERE p_name LIKE 'forest%',

    FROM partsupp {ps_suppkey: s, ps_partkey: p, ps_availqty},
    FROM supplier {xt$id: s, s_nationkey: n, s_name, s_address},
    FROM nation {xt$id: n, n_name: 'CANADA'}

    WHERE ps_availqty > 0.5 * ({s, p} ->
      FROM lineitem {l_partkey: p, l_suppkey: s, l_shipdate, l_quantity}
      WHERE l_shipdate >= DATE '1994-01-01'
      WHERE l_shipdate < DATE '1995-01-01'
      AGGREGATE {sum_quantity: SUM(l_quantity)}
    )
  )

  RETURN s_name, s_address
  ORDER BY s_name")

(def q21
  '(-> (unify (from :nation [{:xt/id n, :n-name "SAUDI ARABIA"}])
              (from :supplier [{:xt/id s, :s-nationkey n} s-name])
              (from :orders [{:xt/id o, :o-orderstatus "F"}])

              (from :lineitem [{:xt/id l1, :l-suppkey s, :l-orderkey o}
                               l-receiptdate l-commitdate]))
       (where (> l-receiptdate l-commitdate)

              (exists? (-> (from :lineitem [{:l-orderkey o, :l-suppkey l2s}])
                           (where (<> $s l2s)))
                       {:args [o s]})

              (not (exists? (-> (from :lineitem [{:l-orderkey o, :l-suppkey l3s}
                                                 l-receiptdate l-commitdate])
                                (where (<> $s l3s)
                                       (> l-receiptdate l-commitdate)))
                            {:args [o s]})))

       (aggregate s-name {:numwait (count l1)})
       (order-by {:val numwait, :dir :desc} s-name)
       (limit 100)))

(def q21-str "XTQL
  UNIFY (
    FROM nation {xt$id: n, n_name: 'SAUDI ARABIA'}
    FROM supplier {xt$id: s, s_nationkey: n, s_name}
    FROM orders {xt$id: o, o_orderstatus: 'F'}
    FROM lineitem {xt$id: l1, l_suppkey: s, l_orderkey: o, l_receiptdate, l_commitdate}
  )

  WHERE l_receiptdate > l_commitdate

  WHERE EXISTS ({o, s} ->
          FROM lineitem {l_orderkey: o, l_suppkey: l2s}
          WHERE s <> l2s
        )

  WHERE NOT EXISTS ({o, s} ->
          FROM lineitem {l_orderkey: o, l_suppkey: l3s, l_receiptdate, l_commitdate}
          WHERE s <> l3s
          WHERE l_receiptdate > l_commitdate
        )

  AGGREGATE {s_name, numwait: COUNT(l1)}
  ORDER BY numwait DESC, s_name
  LIMIT 100")

(def q22
  '(-> (from :customer [c-phone c-acctbal])
       ;; TODO `in?`
       (where (in? (subs c-phone 0 2) #{"13" "31" "23" "29" "30" "18" "17"})

              (> c-acctbal
                 (q (-> (from :customer [c-acctbal c-phone])
                        (where (> c-acctbal 0.0)
                               ;; TODO `in?`
                               (in? (subs c-phone 0 2) #{"13" "31" "23" "29" "30" "18" "17"}))
                        (aggregate {:avg-acctbal (avg c-acctbal)}))))

              ;; TODO `in?`
              (not (in? c (q (from :orders [{:o-custkey c}])))))

       (aggregate cntrycode
                  {:numcust (count c)}
                  {:totacctbal (sum c-acctbal)})

       (order-by cntrycode)))

(def q22-str "XTQL
  FROM customer {c_phone, c_acctbal}
  WHERE SUBSTRING(c_phone, 1, 2) IN ('13', '31', '23', '29', '30', '18', '17')
  WHERE c_acctbal > (
          FROM customer {c_acctbal, c_phone}
          WHERE c_acctbal > 0.0
          WHERE SUBSTRING(c_phone, 1, 2) IN ('13', '31', '23', '29', '30', '18', '17')
          AGGREGATE {avg_acctbal: AVG(c_acctbal)}
        ),
        c NOT IN (
          FROM orders {o_custkey: c}
        )

  AGGREGATE { cntrycode, numcust: COUNT(c), totacctbal: SUM(c_acctbal) }
  ORDER BY cntrycode")

(def queries
  [#'q1 #'q2 #'q3 #'q4 #'q5 #'q6 #'q7 #'q8 #'q9 #'q10 #'q11
   #'q12 #'q13 #'q14 #'q15 #'q16 #'q17 #'q18 #'q19 #'q20 #'q21 #'q22])
