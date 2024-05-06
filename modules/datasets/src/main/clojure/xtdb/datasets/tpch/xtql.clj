(ns xtdb.datasets.tpch.xtql
  (:require [honey.sql :as sql]))

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

(def q1-d
  '{:where [($ :lineitem
               l-shipdate l-quantity
               l-extendedprice l-discount l-tax
               l-returnflag l-linestatus)
            (<= l-shipdate #time/date "1998-09-02")]
    :find [l-returnflag
           l-linestatus
           {:sum-qty (sum l-quantity)
            :sum-base-price (sum l-extendedprice)
            :sum-disc-price (sum (* l-extendedprice (- 1 l-discount)))
            :sum-charge (sum (* (* l-extendedprice (- 1 l-discount)) (+ 1 l-tax)))
            :avg-qty (avg l-quantity)
            :avg-price (avg l-extendedprice)
            :avg-disc (avg l-discount)
            :count-order (row-count)}]
    :order-by [l-returnflag l-linestatus]})

(def q1-hs
  (sql/format
   '{:from :lineitem
     :where (<= l-shipdate #time/date "1998-09-02")
     :select [l-returnflag
              l-linestatus
              [(sum l-quantity) l-quantity]
              [(sum l-extendedprice) :sum-base-price]
              [(sum (* l-extendedprice (- [:inline 1] l-discount))) :sum-disc-price]
              [(sum (* (* l-extendedprice (- [:inline 1] l-discount)) (+ [:inline 1] l-tax))) :sum-charge]
              [(avg l-quantity) :avg-qty]
              [(avg l-extendedprice) :avg-price]
              [(avg l-discount) :avg-disc]
              [(row-count) :count-order]]
     :order-by [l-returnflag l-linestatus]}))

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

       (return s-acctbal s-name n-name p p-mfgr s-address s-phone s-comment)
       (order-by {:val s-acctbal, :dir :desc} n-name s-name p)
       (limit 100)))

(def q2-d
  '{:where [($ :part {:xt/id p, :p-size 15} p-mfgr p-type)
            (like p-type "%BRASS")

            ($ :partsupp {:ps-partkey p, :ps-suppkey s} ps-supplycost)
            ($ :supplier {:xt/id s, :s-nationkey n}
               s-acctbal s-address s-name s-phone s-comment)
            ($ :nation {:xt/id n, :n-regionkey r} n-name)
            ($ :region {:xt/id r, :r-name "EUROPE"})
            (= ps-supplycost
               (q {:where [($ :partsupp {:ps-partkey %, :ps-suppkey s} ps-supplycost)
                           ($ :supplier {:xt/id s, :s-nationkey n})
                           ($ :nation {:xt/id n, :n-regionkey r})
                           ($ :region {:xt/id r, :r-name "EUROPE"})]
                   :find [{:min-supplycost (min ps-supplycost)}]}
                  p))]

    :find [s-acctbal s-name n-name p p-mfgr s-address s-phone s-comment]

    :order-by [[s-acctbal :desc] n-name s-name p]
    :limit 100})

(def q2-hs-xtid
  (sql/format
   '{:from [part p]
     :join [[partsupp ps] (= p/xt$id ps/ps-partkey)
            [supplier s] (= ps/ps-suppkey s/xt$id)
            [nation n] (= n/xt$id s/s-nationkey)
            [region r] (= n/n-regionkey r/xt$id)]
     :where (and (= p-size 15)
                 (like p-type [:inline "%BRASS"])
                 (= r-name [:inline "EUROPE"])

                 (= ps-supplycost
                    {:from [partsupp ps]
                     :join [[supplier s] (= ps/ps-suppkey s/xt$id)
                            [nation n] (= n/xt$id s/s-nationkey)
                            [region r] (= n/n-regionkey r/xt$id)]
                     :where (and (= ps/ps-partkey p/xt$id)
                                 (= r/r-name [:inline "EUROPE"]))
                     :select [[(min ps-supplycost) min-supplycost]]}))

     :select [s-acctbal s-name n-name p-partkey p-mfgr s-address s-phone s-comment]

     :order-by [[s-acctbal :desc] n-name s-name p-partkey]
     :limit [:inline 100]}))

(def q2-hs
  (sql/format
   '{:from [part]
     :join [partsupp (:using partkey)
            supplier (:using suppkey)
            nation (:using nationkey)
            region (:using regionkey)]
     :where (and (= p-size 15)
                 (like p-type [:inline "%BRASS"])
                 (= r-name [:inline "EUROPE"])

                 (= ps-supplycost
                    {:from partsupp
                     :join [supplier (:using suppkey)
                            nation (:using nationkey)
                            [region r] (:using regionkey)]
                     :where (= r/r-name [:inline "EUROPE"])
                     :select [[(min ps-supplycost) min-supplycost]]}))

     :select [s-acctbal
              s-name
              n-name
              p-partkey
              p-mfgr
              s-address
              s-phone
              s-comment]

     :order-by [[s-acctbal :desc] n-name s-name p-partkey]
     :limit [:inline 100]}))

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

(def q3-d
  '{:where [($ :customer {:xt/id c, :c-mktsegment %})
            ($ :orders {:xt/id o, :o-custkey c} o-shippriority o-orderdate)
            (< o-orderdate #time/date "1995-03-15")
            ($ :lineitem {:l-orderkey o} l-discount l-extendedprice l-shipdate)
            (> l-shipdate #time/date "1995-03-15")]

    :find [{:l-orderkey o}
           {:revenue (sum (* l-extendedprice (- 1 l-discount)))}
           o-orderdate o-shippriority]
    :order-by [[revenue :desc] o-orderdate]
    :limit 10})

(def q3-hs-xtid
  (sql/format
   '{:from [[customer c]]
     :join [[orders o] (= c/xt$id o/o-custkey)
            [lineitem l] (= o/xt$id l/l-orderkey)]
     :where (and (< o-orderdate #time/date "1995-03-15")
                 (> l-shipdate #time/date "1995-03-15")
                 (= c-mktsegment [:inline "BUILDING"]))

     :select [o-orderkey
              [(sum (* l-extendedprice (- l-discount))) revenue]
              o-orderdate
              o-shippriority]

     :order-by [[revenue :desc] o-orderdate]
     :limit [:inline 10]}))

(def q3-hs
  (sql/format
   '{:from [customer]
     :join [orders (:using custkey)
            lineitem (:using orderkey)]
     :where (and (< o-orderdate #time/date "1995-03-15")
                 (> l-shipdate #time/date "1995-03-15")
                 (= c-mktsegment [:inline "BUILDING"]))

     :select [o-orderkey
              [(sum (* l-extendedprice (- l-discount))) revenue]
              o-orderdate
              o-shippriority]

     :order-by [[revenue :desc] o-orderdate]
     :limit [:inline 10]}))

(def q4
  '(-> (from :orders [{:xt/id o} o-orderdate o-orderpriority])
       (where (>= o-orderdate #time/date "1993-07-01")
              (< o-orderdate #time/date "1993-10-01")

              (exists? (-> (from :lineitem [{:l-orderkey $o} l-commitdate l-receiptdate])
                           (where (< l-commitdate l-receiptdate)))
                       {:args [o]}))

       (aggregate o-orderpriority {:order-count (count o)})
       (order-by o-orderpriority)))

(def q4-d
  '{:where [($ :orders {:xt/id o} o-orderdate o-orderpriority)
            (>= o-orderdate #time/date "1993-07-01")
            (< o-orderdate #time/date "1993-10-01")
            (exists? {:where [($ :lineitem {:l-orderkey %} l-commitdate l-receiptdate)
                              (< l-commitdate l-receiptdate)]}
                     o)]

    :find [{:order-count (count o)}]
    :order-by [o-orderpriority]})

(def q4-hs-xtid
  (sql/format
   '{:from orders
     :where (and (>= o-orderdate #time/date "1993-07-01")
                 (< o-orderdate #time/date "1993-10-01")
                 (exists {:from [lineitem li]
                          :where (and (< l-commitdate l-receiptdate)
                                      (= li/li-orderkey o/xt$id))
                          :select [[[:inline 1]]]}))

     :select [o-orderpriority
              [(count o) order-count]]
     :order-by [o-orderpriority]}))

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

(def q5-d
  '{:where [($ :orders {:xt/id o, :o-custkey c} o-orderdate)
            (>= o-orderdate #time/date "1994-01-01")
            (< o-orderdate #time/date "1995-01-01")

            ($ :lineitem {:l-orderkey o, :l-suppkey s}
               l-extendedprice l-discount)

            ($ :supplier {:xt/id s, :s-nationkey n})
            ($ :customer {:xt/id c, :c-nationkey n})
            ($ :nation {:xt/id n, :n-regionkey r} n-name)
            ($ :region {:xt/id r, :r-name %})]

    :find [{:n-name n-name
            :revenue (sum (* l-extendedprice (- 1 l-discount)))}]
    :order-by [[revenue :desc]]})

(def q5-hs-xtid
  (sql/format
   '{:from [[orders o]]
     :join [lineitem (= o/xt$id l/l-orderkey)
            supplier (= l/l-suppkey s/xt$id)
            customer (= o/o-custkey c/xt$id)
            nation (= c/c-nationkey n/xt$id)
            region (= n/n-regionkey r/xt$id)]
     :where (and (>= o-orderdate #time/date "1994-01-01")
                 (< o-orderdate #time/date "1995-01-01")
                 (= r/r-name [:inline "ASIA"]))

     :select [n-name
              [(sum (* l-extendedprice (- l-discount))) revenue]]
     :order-by [[revenue :desc]]}))

(def q6
  '(-> (unify (from :lineitem [l-shipdate l-quantity l-extendedprice l-discount])
              (where (>= l-shipdate #time/date "1994-01-01")
                     (< l-shipdate #time/date "1995-01-01")
                     (>= l-discount 0.05)
                     (<= l-discount 0.07)
                     (< l-quantity 24.0)))

       (aggregate {:revenue (sum (* l-extendedprice l-discount))})))

(def q6-d
  '{:where [($ :lineitem {:l-shipdate l-shipdate
                          :l-quantity l-quantity
                          :l-extendedprice l-extendedprice
                          :l-discount l-discount})
            (>= l-shipdate #time/date "1994-01-01")
            (< l-shipdate #time/date "1995-01-01")
            (>= l-discount 0.05)
            (<= l-discount 0.07)
            (< l-quantity 24.0)]

    :find [{:revenue (sum (* l-extendedprice l-discount))}]})

(def q6-hs
  (sql/format
   '{:from lineitem
     :where (and (>= l-shipdate #time/date "1994-01-01")
                 (< l-shipdate #time/date "1995-01-01")
                 (>= l-discount 0.05)
                 (<= l-discount 0.07)
                 (< l-quantity 24.0))
     :select [[(sum (* l-extendedprice l-discount)) revenue]]}))

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

(def q7-d
  '{:where [($ :orders {:o-custkey c})
            ($ :lineitem {:l-orderkey o, :l-suppkey s}
               l-shipdate l-discount l-extendedprice)
            (>= l-shipdate #time/date "1995-01-01")
            (<= l-shipdate #time/date "1996-12-31")

            ($ :supplier {:xt/id s, :s-nationkey n1})
            ($ :nation {:xt/id n1, :n-name supp-nation})

            ($ :customer {:xt/id c, :c-nationkey n2})
            ($ :nation {:xt/id n2, :n-name cust-nation})

            (or (and (= "FRANCE" supp-nation)
                     (= "GERMANY" cust-nation))
                (and (= "GERMANY" supp-nation)
                     (= "FRANCE" cust-nation)))]

    :find [supp-nation cust-nation
           {:l-year (extract "YEAR" l-shipdate)}
           {:revenue (sum (* l-extendedprice (- 1 l-discount)))}]

    :order-by [supp-nation cust-nation l-year]})

(def q7-hs-xtid
  (sql/format
   '{:from [[orders o]]
     :join [[lineitem l] (= o/xt$id l/l-orderkey)
            [supplier s] (= l/l-suppkey s/xt$id)
            [nation sn] (= s/s-nationkey sn/xt$id)
            [customer c] (= o/o-custkey c/xt$id)
            [nation cn] (= c/c-nationkey cn/xt$id)]
     :where (and (>= l-shipdate #time/date "1995-01-01")
                 (<= l-shipdate #time/date "1996-12-31")

                 (or (and (= sn/n-name [:inline "FRANCE"])
                          (= cn/n-name [:inline "GERMANY"]))
                     (and (= sn/n-name [:inline "GERMANY"])
                          (= cn/n-name [:inline "FRANCE"]))))

     :select [[sn/n-name supp-nation]
              [cn/n-name cust-nation]
              [(extract [:inline "YEAR"] l-shipdate) l-year]
              [(sum (* l-extendedprice (- l-discount))) revenue]]
     :order-by [supp-nation cust-nation l-year]}))

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

(def q8-d
  '{:where [($ :orders {:xt/id o, :o-custkey c} o-orderdate)
            (>= o-orderdate #time/date "1995-01-01")
            (<= o-orderdate #time/date "1996-12-31")

            ($ :lineitem {:xt/id l, :l-orderkey o, :l-suppkey s, :l-partkey p}
               l-extendedprice l-discount)

            ($ :customer {:xt/id c, :c-nationkey n1})
            ($ :nation {:xt/id n1, :n-regionkey r1})
            ($ :region {:xt/id r1, :r-name "AMERICA"})

            ($ :supplier {:xt/id s, :s-nationkey n2})
            ($ :nation {:xt/id n2, :n-name nation})

            ($ :part {:xt/id p, :p-type "ECONOMY ANODIZED STEEL"})

            {:volume (* l-extendedprice (- 1 l-discount))}]

    :find [{:o-year (extract "YEAR" o-orderdate)}
           {:mkt-share (/ (sum (if (= "BRAZIL" nation) volume 0))
                          (sum volume))}]

    :order-by [o-year]})

(def q8-hs-xtid
  (sql/format
   '{:from [[orders o]]
     :join [[lineitem l] (= o/xt$id l/l-orderkey)
            [customer c] (= o/o-custkey c/xt$id)
            [nation cn] (= c/c-nationkey cn/xt$id)
            [region r] (= cn/n-regionkey r/xt$id)
            [supplier s] (= l/l-suppkey s/xt$id)
            [nation sn] (= s/s-nationkey sn/xt$id)
            [part p] (= l/l-partkey p/xt$id)]
     :where (and (>= o-orderdate #time/date "1995-01-01")
                 (<= o-orderdate #time/date "1996-12-31")
                 (= r/r-name [:inline "AMERICA"])
                 (= p/p-type [:inline "ECONOMY ANODIZED STEEL"]))

     :group-by [o-year]
     :select [[(extract [:inline "YEAR"] o-orderdate) o-year]
              [(sum (* l-extendedprice (- l-discount))) volume]
              [sn/n-name nation]]
     :order-by [o-year]}))

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

(def q9-d
  '{:where [($ :part {:xt/id p} p-name)
            (like p-name "%green%")

            ($ :orders {:xt/id o} o-orderdate)

            ($ :lineitem {:l-orderkey o, :l-suppkey s, :l-partkey p}
               l-quantity l-extendedprice l-discount)

            ($ :partsupp {:ps-partkey p, :ps-suppkey s} ps-supplycost)

            ($ :supplier {:xt/id s, :s-nationkey n})
            ($ :nation {:xt/id n, :n-name nation})]

    :find [nation
           {:o-year (extract "YEAR" o-orderdate)}
           {:sum-profit (sum (- (* l-extendedprice (- 1 l-discount))
                               (* ps-supplycost l-quantity)))}]})

(def q9-hs-xtid
  (sql/format
   '{:from [[part p]]
     :join [[orders o] (= p/xt$id o/o-partkey)
            [lineitem l] (= o/xt$id l/l-orderkey)
            [partsupp ps] (= l/l-partkey ps/ps-partkey)
            [supplier s] (= l/l-suppkey s/xt$id)
            [nation n] (= s/s-nationkey n/xt$id)]
     :where (like p-name [:inline "%green%"])

     :group-by [n-name (extract [:inline "YEAR"] o-orderdate)]
     :select [[n-name nation]
              [(extract [:inline "YEAR"] o-orderdate) o-year]
              [(sum (- (* l-extendedprice (- l-discount))
                       (* ps-supplycost l-quantity))) sum-profit]]}))

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

(def q10-d
  '{:where [($ :orders {:xt/id o, :o-custkey c} o-orderdate)
            (>= o-orderdate #time/date "1993-10-01")
            (< o-orderdate #time/date "1994-01-01")

            ($ :customer {:xt/id c, :c-nationkey n}
               c-name c-address c-phone
               c-acctbal c-comment)

            ($ :nation {:xt/id n, :n-name n-name})

            ($ :lineitem {:l-orderkey o, :l-returnflag "R"}
               l-extendedprice l-discount)]

    :find [c c-name
           {:revenue (sum (* l-extendedprice (- 1 l-discount)))}
           c-acctbal c-phone n-name c-address c-comment]

    :order-by [[revenue :desc]]
    :limit 20})

(def q10-hs-xtid
  (sql/format
   '{:from [[orders o]]
     :join [[customer c] (= o/o-custkey c/xt$id)
            [nation n] (= c/c-nationkey n/xt$id)
            [lineitem li] (= o/xt$id l/l-orderkey)]
     :where (and (>= o-orderdate #time/date "1993-10-01")
                 (< o-orderdate #time/date "1994-01-01")
                 (= l-returnflag [:inline "R"]))

     :select [c/xt$id c-name
              [(sum (* l-extendedprice (- l-discount))) revenue]
              c-acctbal c-phone n/n-name c-address c-comment]
     :order-by [[revenue :desc]]
     :limit [:inline 20]}))

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

(def q11-d
  '{:where [(q {:where [($ :partsupp {:ps-suppkey s} ps-availqty ps-supplycost)
                        ($ :supplier {:xt/id s, :s-nationkey n})
                        ($ :nation {:xt/id n, :n-name "GERMANY"})]
                :find [{:total-value (sum (* ps-supplycost ps-availqty))}]})

            (q {:where [($ :partsupp {:ps-suppkey s} ps-availqty ps-supplycost ps-partkey)
                        ($ :supplier {:xt/id s, :s-nationkey n})
                        ($ :nation {:xt/id n, :n-name "GERMANY"})]
                :find [ps-partkey {:value (sum (* ps-supplycost ps-availqty))}]})

            (> value (* 0.0001 total-value))]

    :find [ps-partkey value]

    :order-by [[value :desc]]})

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

(def q12-d
  '{:where [($ :lineitem {:l-orderkey o} l-receiptdate l-commitdate l-shipdate l-shipmode)
            (in? l-shipmode %)
            (>= l-receiptdate #time/date "1994-01-01")
            (< l-receiptdate #time/date "1995-01-01")
            (< l-commitdate l-receiptdate)
            (< l-shipdate l-commitdate)

            ($ :orders {:xt/id o} o-orderpriority)]

    :find [l-shipmode
           {:high-line-count (sum (case o-orderpriority "1-URGENT" 1, "2-HIGH" 1, 0))}
           {:low-line-count (sum (case o-orderpriority "1-URGENT" 0, "2-HIGH" 0, 1))}]

    :order-by [l-shipmode]})

(def q13
  '(-> (unify (from :customer [{:xt/id c}])
              (left-join (unify (from :orders [{:xt/id o, :o-custkey c} o-comment])
                                (where (not (like o-comment "%special%requests%"))))
                         [c o]))
       (aggregate c {:c-count (count o)})
       (aggregate c-count {:custdist (row-count)})
       (order-by {:val custdist, :dir :desc} {:val c-count, :dir :desc})))

(def q13-d
  '{:where [(q {:where [($ :customer {:xt/id c})
                        (left-join {:where [($ :orders {:xt/id o, :o-custkey c} o-comment)
                                            (not (like o-comment "%special%requests%"))]
                                    :find [c o]})]

                :find [c {:c-count (count o)}]})]

    :find [c-count {:custdist (row-count)}]
    :order-by [[custdist :desc] [c-count :desc]]})

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

(def q14-d
  '{:where [(q {:where [($ :lineitem {:l-partkey p} l-shipdate l-extendedprice l-discount)
                        (>= l-shipdate #time/date "1995-09-01")
                        (< l-shipdate #time/date "1995-10-01")

                        ($ :part {:xt/id p} p-type)]

                :find [{:promo (sum (if (like p-type "PROMO%")
                                      (* l-extendedprice (- 1 l-discount))
                                      0))}
                       {:total (sum (* l-extendedprice (- 1 l-discount)))}]})]

    :find {:promo-revenue (* 100 (/ promo total))}})

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

(def q15-d
  '{:with [revenue {:where [($ :lineitem {:l-suppkey s} l-shipdate l-extendedprice l-discount)
                            (>= l-shipdate #time/date "1996-01-01")
                            (< l-shipdate #time/date "1996-04-01")]
                    :find [s {:total-revenue (sum (* l-extendedprice (- 1 l-discount)))}]}]

    :where [($ revenue s total-revenue)
            (= total-revenue
               (q {:where [($ revenue total-revenue)]
                   :find [{:max-revenue (max total-revenue)}]}))
            ($ :supplier {:xt/id s} s-name s-address s-phone)]

    :find [s s-name s-address s-phone total-revenue]})

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

(def q16-d
  '{:where [($ :part {:xt/id p} p-brand p-type p-size)
            (in? p-size %)
            (<> p-brand "Brand#45")
            (not (like p-type "MEDIUM POLISHED%"))

            ($ :partsupp {:ps-partkey p, :ps-suppkey s})
            (not (exists? {:where [($ :supplier {:xt/id %} s-comment)
                                   (like "%Customer%Complaints%" s-comment)]}
                          s))]

    :find [p-brand p-type p-size {:supplier-cnt (count-distinct s)}]
    :order-by [[supplier-cnt :desc] p-brand p-type p-size]})

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

(def q17-d
  '{:where [(q {:where [($ :part {:xt/id p, :p-brand "Brand#23", :p-container "MED BOX"})
                        ($ :lineitem {:l-partkey p} l-quantity l-extendedprice)

                        (q {:where [($ :lineitem {:l-partkey %} l-quantity)]
                            :find [{:avg-quantity (avg l-quantity)}]}

                           p)

                        (< l-quantity (* 0.2 avg-quantity))]

                :find [{:sum-extendedprice (sum l-extendedprice)}]})]

    :find {:avg-yearly (/ sum-extendedprice 7.0)}})

(def q18
  '(-> (unify (from :customer [{:xt/id c} c-name])
              (from :orders [{:xt/id o, :o-custkey c} o-orderdate o-totalprice]))

       ;; TODO `in?`
       (where (in? o
                   (q (-> (from :lineitem [{:l-orderkey o} l-quantity])
                          (aggregate o {:sum-quantity (sum l-quantity)})
                          (where (> sum-quantity 300.0))
                          (return o)))))

       (aggregate c-name {:c-custkey c} {:o-orderkey o} o-orderdate o-totalprice {:sum-qty (sum l-quantity)})

       (order-by {:val o-totalprice, :dir :desc} o-orderdate)
       (limit 100)))

(def q18-d
  '{:where [($ :customer {:xt/id c} c-name)
            ($ :orders {:xt/id o, :o-custkey c} o-orderdate o-totalprice)

            (in? o
                 (q {:where [($ :lineitem {:l-orderkey o} l-quantity)
                             ($ :orders {:xt/id o} o-orderdate o-totalprice)
                             (> (sum l-quantity) 300.0)]
                     :find [o]}))]

    :find [c-name {:c-custkey c} {:o-orderkey o} o-orderdate o-totalprice {:sum-qty (sum l-quantity)}]

    :order-by [[o-totalprice :desc] o-orderdate]
    :limit 100})

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

(def q19-d
  '{:where [($ :part {:xt/id p} p-size p-brand p-container)

            ($ :lineitem {:l-shipinstruct "DELIVER IN PERSON"
                          :l-partkey p}
               l-shipmode l-discount l-extendedprice l-quantity)

            (in? l-shipmode %)

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

    :find [{:revenue (sum (* l-extendedprice (- 1 l-discount)))}]})

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

(def q20-d
  '{:where [($ :part {:xt/id p} p-name)
            (like p-name "forest%")

            ($ :partsupp {:ps-suppkey s, :ps-partkey p} ps-availqty)
            ($ :supplier {:xt/id s, :s-nationkey n} s-name s-address)
            ($ :nation {:xt/id n, :n-name "CANADA"})

            (> ps-availqty
               (* 0.5
                  (q {:in [s p]
                      :where [($ :lineitem {:l-partkey p, :l-suppkey s} l-shipdate l-quantity)
                              (>= l-shipdate #time/date "1994-01-01")
                              (< l-shipdate #time/date "1995-01-01")]
                      :find [{:sum-quantity (sum l-quantity)}]}
                     s p)))]

    :find [s-name s-address]
    :order-by [s-name]})

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

(def q21-d
  '{:where [($ :nation {:xt/id n, :n-name "SAUDI ARABIA"})
            ($ :supplier {:xt/id s, :s-nationkey n} s-name)
            ($ :orders {:xt/id o, :o-orderstatus "F"})

            ($ :lineitem {:xt/id l1, :l-suppkey s, :l-orderkey o}
               l-receiptdate l-commitdate)

            (> l-receiptdate l-commitdate)

            (exists? {:in [o s]
                      :where [($ :lineitem {:l-orderkey o, :l-suppkey l2s})
                              (<> s l2s)]}
                     o s)

            (not (exists? {:in [o s]
                           :where [($ :lineitem {:l-orderkey o, :l-suppkey l3s}
                                      l-receiptdate l-commitdate)
                                   (<> s l3s)
                                   (> l-receiptdate l-commitdate)]}
                          o s))]

    :find [s-name {:numwait (count l1)}]
    :order-by [[numwait :desc] s-name]
    :limit 100})

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

(def q22-d
  '{:where [($ :customer [c-phone c-acctbal])
            (in? (subs c-phone 0 2) #{"13" "31" "23" "29" "30" "18" "17"})

            (> c-acctbal
               (q {:where [($ :customer [c-acctbal c-phone])
                           (> c-acctbal 0.0)
                           (in? (subs c-phone 0 2) #{"13" "31" "23" "29" "30" "18" "17"})]
                   :find [{:avg-acctbal (avg c-acctbal)}]}))

            (not (in? c (q ($ :orders [{:o-custkey c}]))))]

    :find [cntrycode {:numcust (count c)} {:totacctbal (sum c-acctbal)}]
    :order-by [cntrycode]})

(def queries
  [#'q1 #'q2 #'q3 #'q4 #'q5 #'q6 #'q7 #'q8 #'q9 #'q10 #'q11
   #'q12 #'q13 #'q14 #'q15 #'q16 #'q17 #'q18 #'q19 #'q20 #'q21 #'q22])
