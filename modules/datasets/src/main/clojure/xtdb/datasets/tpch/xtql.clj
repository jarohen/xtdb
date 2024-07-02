(ns xtdb.datasets.tpch.xtql)

(defn- with-args [q args]
  (vary-meta q assoc ::args (vec args)))

(def q1
  '{:match [(from :lineitem)
            (<= l-shipdate #xt.time/date "1998-09-02")

            {disc-price (* l-extendedprice (- 1 l-discount))}]

    :yield [l-returnflag l-linestatus
            {:sum-qty (sum l-quantity)
             :sum-base-price (sum l-extendedprice)
             :sum-disc-price (sum disc-price)
             :sum-charge (sum (* disc-price (+ 1 l-tax)))
             :avg-qty (avg l-quantity)
             :avg-price (avg l-extendedprice)
             :avg-disc (avg l-discount)
             :count-order (row-count)}]

    :order-by [l-returnflag l-linestatus]})

(def q2
  '{:match [(from :part {:xt/id p, :p-size 15})
            (like p-type "%BRASS")

            (from :partsupp {:ps-partkey p, :ps-suppkey s
                             :ps-supplycost (q {:match [(given p)
                                                        (from :partsupp {:ps-partkey p, :ps-suppkey s})
                                                        (from :supplier {:xt/id s, :s-nationkey n})
                                                        (from :nation {:xt/id n, :n-regionkey r})
                                                        (from :region {:xt/id r, :r-name "EUROPE"})]
                                                :yield (min ps-supplycost)})})

            (from :supplier {:xt/id s, :s-nationkey n})
            (from :nation {:xt/id n, :n-regionkey r} n-name)
            (from :region {:xt/id r, :r-name "EUROPE"})]

    :yield [s-acctbal s-name s-address s-phone s-comment
            n-name {:p-partkey p} p-mfgr]

    :order-by [(desc s-acctbal) n-name s-name p]
    :limit 100})

(def q3
  (-> '{:match [(given segment)
                (from :customer {:xt/id c, :c-mktsegment segment})

                (from :orders {:xt/id o, :o-custkey c})
                (< o-orderdate #xt.time/date "1995-03-15")

                (from :lineitem {:l-orderkey o})
                (> l-shipdate #xt.time/date "1995-03-15")]

        :yield [{:l-orderkey o}
                {:revenue (sum (* l-extendedprice (- 1 l-discount)))}
                o-orderdate o-shippriority]

        :order-by [(desc revenue) o-orderdate]
        :limit 10}

      (with-args [{:segment "BUILDING"}])))

(def q4
  '{:match [(from :orders {:xt/id o})
            (>= o-orderdate #xt.time/date "1993-07-01")
            (< o-orderdate #xt.time/date "1993-10-01")

            (exists? {:match [(given o)
                              (from :lineitem {:l-orderkey o})
                              (< l-commitdate l-receiptdate)]})]

    :yield [o-orderpriority {:order-count (count o)}]
    :order-by [o-orderpriority]})

(def q5
  (-> '{:match [(given region)

                (from :orders {:xt/id o, :o-custkey c})
                (>= o-orderdate #xt.time/date "1994-01-01")
                (< o-orderdate #xt.time/date "1995-01-01")

                (from :lineitem {:l-orderkey o, :l-suppkey s})
                (from :supplier {:xt/id s, :s-nationkey n})
                (from :customer {:xt/id c, :c-nationkey n})
                (from :nation {:xt/id n, :n-regionkey r})
                (from :region {:xt/id r, :r-name region})]

        :yield [n-name {:revenue (sum (* l-extendedprice (- 1 l-discount)))}]
        :order-by [(desc revenue)]}

      (with-args [{:region "ASIA"}])))

(def q6
  '{:match [(from :lineitem)
            (>= l-shipdate #xt.time/date "1994-01-01")
            (< l-shipdate #xt.time/date "1995-01-01")
            (>= l-discount 0.05)
            (<= l-discount 0.07)
            (< l-quantity 24.0)]

    :yield [{:revenue (sum (* l-extendedprice (- 1 l-discount)))}]})

(def q7
  '{:match [(from :orders {:o-custkey c})
            (from :lineitem {:l-orderkey o, :l-suppkey s})

            (>= l-shipdate #xt.time/date "1995-01-01")
            (<= l-shipdate #xt.time/date "1996-12-31")

            (from :supplier {:xt/id s, :s-nationkey n1})
            (from :nation {:xt/id n1, :n-name supp-nation})
            (from :customer {:xt/id c, :c-nationkey n2})
            (from :nation {:xt/id n2, :n-name cust-nation})

            (or (and (= "FRANCE" supp-nation)
                     (= "GERMANY" cust-nation))
                (and (= "GERMANY" supp-nation)
                     (= "FRANCE" cust-nation)))

            {l-year (extract "YEAR" l-shipdate)}]

    :yield [supp-nation cust-nation l-year
            {:revenue (sum (* l-extendedprice (- 1 l-discount)))}]})

(def q8
  '{:match [(from :orders {:xt/id o, :o-custkey c})
            (>= o-orderdate #xt.time/date "1995-01-01")
            (<= o-orderdate #xt.time/date "1996-12-31")
            {o-year (extract "YEAR" o-orderdate)}

            (from :lineitem {:xt/id l, :l-orderkey o, :l-suppkey s, :l-partkey p})

            (from :customer {:xt/id c, :c-nationkey n1})
            (from :nation {:xt/id n1, :n-regionkey r1})
            (from :region {:xt/id r1, :r-name "AMERICA"})

            (from :supplier {:xt/id s, :s-nationkey n2})
            (from :nation {:xt/id n2, :n-name nation})

            (from :part {:xt/id p, :p-type "ECONOMY ANODIZED STEEL"})

            {volume (* l-extendedprice (- 1 l-discount))}]

    :yield [o-year
            {:mkt-share (/ (sum (if (= "BRAZIL" nation) volume 0))
                           (sum volume))}]

    :order-by o-year})

(def q9
  '{:match [(from :part {:xt/id p})
            (like p-name "%green%")

            (from :orders {:xt/id o})
            {o-year (extract "YEAR" o-orderdate)}

            (from :lineitem {:l-orderkey o, :l-suppkey s, :l-partkey p})

            (from :partsupp {:ps-partkey p, :ps-suppkey s})

            (from :supplier {:xt/id s, :s-nationkey n})
            (from :nation {:xt/id n, :n-name nation})]

    :yield [nation o-year
            {:sum-profit (sum (- (* l-extendedprice (- 1 l-discount))
                                 (* ps-supplycost l-quantity)))}]

    :order-by [nation (desc o-year)]})

(def q10
  '{:match [(from :orders {:xt/id o, :o-custkey c})
            (>= o-orderdate #xt.time/date "1993-10-01")
            (< o-orderdate #xt.time/date "1994-01-01")

            (from :customer {:xt/id c, :c-nationkey n})
            (from :nation {:xt/id n, :n-name n-name})
            (from :lineitem {:l-orderkey o, :l-returnflag "R"})]

    :yield [c c-name
            {:revenue (sum (* l-extendedprice (- 1 l-discount)))}
            c-acctbal c-phone n-name c-address c-comment]

    :order-by (desc revenue)
    :limit 20})

(def q11
  '{:match [(q {:match [(from :partsupp {:ps-suppkey s})
                        (from :supplier {:xt/id s, :s-nationkey n})
                        (from :nation {:xt/id n, :n-name "GERMANY"})]

                :yield {:total-value (sum (* ps-supplycost ps-availqty))}})

            (q {:match [(from :partsupp {:ps-suppkey s})
                        (from :supplier {:xt/id s, :s-nationkey n})
                        (from :nation {:xt/id n, :n-name "GERMANY"})]

                :yield [ps-partkey {:value (sum (* ps-supplycost ps-availqty))}]})

            (> value (* 0.0001 total-value))]

    :yield [ps-partkey value]

    :order-by (desc value)})

(def q12
  (-> '{:match [(given ship-modes)

                (from :lineitem {:l-orderkey o})
                (in? l-shipmode ship-modes)

                (>= l-receiptdate #xt.time/date "1994-01-01")
                (< l-receiptdate #xt.time/date "1995-01-01")
                (< l-commitdate l-receiptdate)
                (< l-shipdate l-commitdate)

                (from :orders {:xt/id o})]

        :yield [l-shipmode
                {:high-line-count (sum (case o-orderpriority "1-URGENT" 1, "2-HIGH" 1, 0))}
                {:low-line-count (sum (case o-orderpriority "1-URGENT" 0, "2-HIGH" 0, 1))}]

        :order-by l-shipmode}

      (with-args [{:ship-modes #{"MAIL" "SHIP"}}])))

(def q13
  '{:match [(q {:match [(from :customer {:xt/id c})
                        (optional {:match [(given c)
                                           (from :orders {:xt/id o, :o-custkey c})
                                           (not (like o-comment "%special%requests%"))]})]
                :yield [c {:c-count (count o)}]})]
    :yield [c-count {:custdist (row-count)}]
    :order-by [(desc custdist) (desc c-count)]})

(def q14
  '{:match [(from :lineitem {:l-partkey p})
            (>= l-shipdate #xt.time/date "1995-09-01")
            (< l-shipdate #xt.time/date "1995-10-01")

            (from :part {:xt/id p})]

    :yield [{:promo-revenue (* 100 (/ (sum (if (like p-type "PROMO%")
                                             (* l-extendedprice (- 1 l-discount))
                                             0))
                                      (sum (* l-extendedprice (- 1 l-discount)))))}]})

(def q15
  '{:with [revenue {:match [(from :lineitem {:l-suppkey s})
                            (>= l-shipdate #xt.time/date "1996-01-01")
                            (< l-shipdate #xt.time/date "1996-04-01")]
                    :yield [s {:total-revenue (sum (* l-extendedprice (- 1 l-discount)))}]}]

    :match [(from revenue [s {:total-revenue (q {:match (from revenue), :yield (max total-revenue)})}])
            (from :supplier {:xt/id s})]

    :yield [{:s-suppkey s} s-name s-address s-phone total-revenue]})

(def q16
  (-> '{:match [(given sizes)

                (from :part {:xt/id p})
                (in? p-size sizes)
                (<> p-brand "Brand#45")
                (not (like p-type "MEDIUM POLISHED%"))

                (from :partsupp {:ps-partkey p, :ps-suppkey s})

                (not (exists? {:match [(given s)
                                       (from :supplier {:xt/id s})
                                       (like "%Customer%Complaints%" s-comment)]}))]

        :yield [p-brand p-type p-size
                {:supplier-cnt (count-distinct s)}]

        :order-by [supplier-cnt :desc, p-brand p-type p-size]}

      (with-args [{:sizes #{3 9 14 19 23 36 45 49}}])))

(def q17
  '{:match [(from :part {:xt/id p, :p-brand "Brand#23", :p-container "MED BOX"})
            (from :lineitem {:l-partkey p})

            (q {:match [(given p)
                        (from :lineitem {:l-partkey p})]
                :yield {:avg-quantity (avg l-quantity)}})

            (< l-quantity (* 0.2 avg-quantity))]

    :yield {:avg-yearly (/ (sum l-extendedprice) 7.0)}})

(def q18
  '{:match [(from :customer {:xt/id c})
            (from :orders {:xt/id o, :o-custkey c})
            (in? o
                 (q {:match [(q {:match [(from :lineitem {:l-orderkey o})]
                                 :yield [o {:sum-quantity (sum l-quantity)}]})
                             (> sum-quantity 300.0)]

                     :yield o}))]
    :yield [c-name {:c-custkey c} {:o-orderkey o} o-orderdate o-totalprice sum-qty]
    :order-by [(desc o-totalprice) o-orderdate]
    :limit 100})

(def q19
  (-> '{:match [(given ship-modes)

                (from :part {:xt/id p})

                (from :lineitem {:l-shipinstruct "DELIVER IN PERSON", :l-partkey p})
                (in? l-shipmode ship-modes)

                (or (and (= p-brand "Brand#12")
                         (in? p-container #{"SM CASE" "SM BOX" "SM PACK" "SM PKG"})
                         (>= l-quantity 1.0)
                         (<= l-quantity 11.0)
                         (>= p-size 1)
                         (<= p-size 5))

                    (and (= p-brand "Brand#23")
                         (in? p-container #{"MED BAG" "MED BOX" "MED PKG" "MED PACK"})
                         (>= l-quantity 10.0)
                         (<= l-quantity 20.0)
                         (>= p-size 1)
                         (<= p-size 10))

                    (and (= p-brand "Brand#34")
                         (in? p-container #{"LG CASE" "LG BOX" "LG PACK" "LG PKG"})
                         (>= l-quantity 20.0)
                         (<= l-quantity 30.0)
                         (>= p-size 1)
                         (<= p-size 15)))]

        :yield {:revenue (sum (* l-extendedprice (- 1 l-discount)))}}

      (with-args [{:ship-modes #{"AIR" "AIR REG"}}])))

(def q20
  '{:match [(from :part {:xt/id p})
            (like p-name "forest%")

            (from :partsupp {:ps-suppkey s, :ps-partkey p})
            (from :supplier {:xt/id s, :s-nationkey n})
            (from :nation {:xt/id n, :n-name "CANADA"})

            (> ps-availqty
               (* 0.5
                  (q {:match [(given s p)
                              (from :lineitem {:l-partkey p, :l-suppkey s})
                              (>= l-shipdate #xt.time/date "1994-01-01")
                              (< l-shipdate #xt.time/date "1995-01-01")]

                      :yield {:sum-quantity (sum l-quantity)}})))]

    :yield [s-name s-address]
    :order-by s-name})

(def q21
  '{:match [(from :nation {:xt/id n, :n-name "SAUDI ARABIA"})
            (from :supplier {:xt/id s, :s-nationkey n})
            (from :orders {:xt/id o, :o-orderstatus "F"})

            (from :lineitem {:xt/id l1, :l-suppkey s, :l-orderkey o})
            (> l-receiptdate l-commitdate)

            (exists? {:match [(given o s)
                              (from :lineitem {:l-orderkey o, :l-suppkey l2s})
                              (<> s l2s)]})

            (not (exists? {:match [(given o s)
                                   (from :lineitem {:l-orderkey o, :l-suppkey l3s})
                                   (<> s l3s)
                                   (> l-receiptdate l-commitdate)]}))]

    :yield [s-name {:numwait (count l1)}]
    :order-by [(desc numwait) s-name]
    :limit 100})

(def q22
  '{:match [(from :customer)
            (in? (subs c-phone 0 2) #{"13" "31" "23" "29" "30" "18" "17"})

            (> c-acctbal
               (q {:match [(from :customer)
                           (> c-acctbal 0.0)
                           (in? (subs c-phone 0 2) #{"13" "31" "23" "29" "30" "18" "17"})]
                   :yield {:avg-acctbal (avg c-acctbal)}}))

            (not (in? c (q {:match (from :orders)
                            :yield o-custkey})))]

    :yield [cntrycode {:numcust (count c), :totacctbal (sum c-acctbal)}]
    :order-by cntrycode})

(def queries
  [#'q1 #'q2 #'q3 #'q4 #'q5 #'q6 #'q7 #'q8 #'q9 #'q10 #'q11
   #'q12 #'q13 #'q14 #'q15 #'q16 #'q17 #'q18 #'q19 #'q20 #'q21 #'q22])
