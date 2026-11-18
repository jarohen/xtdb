(ns xtdb.predicate-bench-test
  "L0/L1 against the expression engine: a correctness spot-check, then a plain warmed-up timing harness (`bench`, gated on env XTDB_PREDICATE_BENCH)."
  (:require [clojure.test :as t]
            [xtdb.expression :as expr]
            [xtdb.test-util :as tu]
            [xtdb.types :as types])
  (:import (java.time LocalDate)
           (java.util Random)
           (xtdb.api.error Fault)
           (xtdb.arrow Relation RelationReader RowBits VectorReader)
           (xtdb.operator SelectionSpec)))

(t/use-fixtures :each tu/with-allocator)

(def ^:private nan Double/NaN)

(defn- open-rel ^RelationReader [cols]
  (Relation. tu/*allocator*
             (mapv (fn [[nm type vals]] (tu/open-vec (str nm) type (vec vals))) cols)
             (count (nth (first cols) 2))))

(defn- var-types [^RelationReader rel]
  (into {} (map (fn [vec] [(symbol (.getName vec)) (.getType vec)])) rel))

(defn- gen-spec ^SelectionSpec [form ^RelationReader rel ^RelationReader args]
  (let [input-types {:var-types (var-types rel), :param-types (var-types args)}]
    (expr/->expression-selection-spec (expr/form->expr form input-types) input-types)))

(defn- gen-select [form rel args]
  (vec (.select (gen-spec form rel args) tu/*allocator* rel {} args)))

;; --- L0 driven from a scalar

(defn- leaf ^RowBits [^VectorReader v op scalar ^RowBits m]
  (cond
    (instance? LocalDate scalar)
    (case op < (.lt v ^LocalDate scalar m nil) <= (.le v ^LocalDate scalar m nil) > (.gt v ^LocalDate scalar m nil)
              >= (.ge v ^LocalDate scalar m nil) == (.eq v ^LocalDate scalar m nil) <> (.ne v ^LocalDate scalar m nil))

    (instance? Double scalar)
    (let [s (double scalar)]
      (case op < (.lt v s m nil) <= (.le v s m nil) > (.gt v s m nil) >= (.ge v s m nil) == (.eq v s m nil) <> (.ne v s m nil)))

    :else
    (let [s (long scalar)]
      (case op < (.lt v s m nil) <= (.le v s m nil) > (.gt v s m nil) >= (.ge v s m nil) == (.eq v s m nil) <> (.ne v s m nil)))))

(defn- kernel-select [^RelationReader rel col op scalar negated?]
  (let [v (.vectorFor rel (name col))
        all (RowBits/all (.getRowCount rel))
        m (if negated?
            (.andNot (.notNull v all nil) (leaf v op scalar all))
            (leaf v op scalar all))]
    (vec (.toSelection m))))

;; --- correctness

(def ^:private int-vals [0 1 -1 5 -5 127 -128 nil 3 nil 5 4 6 2 100 nil nil 5])
(def ^:private flt-vals [0.0 -0.0 1.5 nan -1.5 5.0 nil Double/POSITIVE_INFINITY Double/NEGATIVE_INFINITY 2.5 nan nil 5.0 -0.0 3.25 0.0 nil nan])
(def ^:private date-vals (concat [nil] (for [d [0 1 18000 18262 18263 -5 19000]] (LocalDate/ofEpochDay d)) (repeat 10 nil)))

(def ^:private col-specs
  [['i32 (types/->type '[:? :i32]) (map #(some-> % (* 100000) int) int-vals)]
   ['i64 (types/->type '[:? :i64]) (map #(some-> % (* 3000000000)) int-vals)]
   ['f64 (types/->type '[:? :f64]) flt-vals]
   ['d (types/->type '[:? :date :day]) (take (count int-vals) date-vals)]])

(def ^:private scalars [0 5 -3 3000000000 15000000000 2.5 -0.0 0.0 nan 5.0 (LocalDate/ofEpochDay 18262)])

(defn- covered? [col scalar]
  (if (= col 'd) (instance? LocalDate scalar) (not (instance? LocalDate scalar))))

(t/deftest l0-equals-generated-code
  (with-open [^Relation base (open-rel col-specs)
              args (tu/open-args {})]
    (let [n (.getRowCount base)
          views {"rel" base
                 "selection" (.select base (int-array (filter even? (range n))))
                 "reversed" (.select base (int-array (reverse (range n))))
                 "slice" (.select base 3 (- n 5))}
          checked (volatile! 0)]
      (doseq [[vname rel] views
              [col _ _] col-specs
              op '[< <= > >= == <>]
              scalar scalars
              :when (covered? col scalar)
              negated? [false true]
              :let [form (if negated? (list 'not (list op col scalar)) (list op col scalar))]]
        (vswap! checked inc)
        (t/is (= (gen-select form rel args) (kernel-select rel col op scalar negated?))
              (pr-str vname form)))
      (t/is (pos? @checked)))))

(t/deftest uncovered-pairs-answer-nil
  (with-open [^Relation base (open-rel col-specs)]
    (let [all (RowBits/all (.getRowCount base))]
      (t/is (nil? (.lt (.vectorFor base "d") 5 all nil)))
      (t/is (nil? (.lt (.vectorFor base "i64") (LocalDate/ofEpochDay 1) all nil))))))

(t/deftest unchecked-read-refuses-rows-outside-the-vector
  (with-open [^Relation base (open-rel col-specs)]
    (let [n (.getRowCount base)
          v (.vectorFor base "i64")]
      (t/is (thrown? Fault (.lt v 0 (RowBits/all (inc n)) nil)) "mask longer than the vector")
      (t/is (thrown? Fault (.lt v 0 (RowBits/all 3) (int-array [0 1]))) "mask longer than the selection")
      (t/is (thrown? Fault (.lt v 0 (RowBits/all 2) (int-array [0 n]))) "selected row past the end")
      (t/is (thrown? Fault (.lt v 0 (RowBits/all 2) (int-array [-1 0]))) "negative selected row")
      (t/is (= [0 1] (vec (.toSelection (.ge v 0 (RowBits/all 2) (int-array [(dec n) 0])))))
            "the last row and the first, through a selection"))))

;; --- bench

(defn- nanos-per-call
  "Best of 3 rounds, each at least 150ms, after a 200ms warm-up."
  ^double [f]
  (let [run (fn [^long ms]
              (let [end (+ (System/nanoTime) (* ms 1000000))]
                (loop [n 0 sink 0]
                  (if (< (System/nanoTime) end)
                    (recur (inc n) (+ sink (alength ^ints (f))))
                    [n (do sink)]))))]
    (run 200)
    (apply min (for [_ (range 3)]
                 (let [t0 (System/nanoTime)
                       [n _] (run 150)]
                   (/ (double (- (System/nanoTime) t0)) n))))))

(defn- gen-col [type n nullable? ^Random rnd]
  (case type
    :i32 (repeatedly n #(if (and nullable? (< (.nextDouble rnd) 0.1)) nil (.nextInt rnd 100)))
    :i64 (repeatedly n #(if (and nullable? (< (.nextDouble rnd) 0.1)) nil (long (.nextInt rnd 100))))
    :f64 (repeatedly n #(if (and nullable? (< (.nextDouble rnd) 0.1)) nil (* 100.0 (.nextDouble rnd))))
    :date (repeatedly n #(if (and nullable? (< (.nextDouble rnd) 0.1)) nil (LocalDate/ofEpochDay (+ 18000 (.nextInt rnd 100)))))))

(defn- vtype [type nullable?]
  (let [spec (vec (concat (when nullable? [:?]) (case type :i32 [:i32], :i64 [:i64], :f64 [:f64], :date [:date :day])))]
    (types/->type (if (= 1 (count spec)) (first spec) spec))))

(defn- scalars-for [type]
  (case type
    :i32 [30 70], :i64 [30 70], :f64 [30.0 70.0], :date [(LocalDate/ofEpochDay 18030) (LocalDate/ofEpochDay 18070)]))

(defn- kernel-fn [^RelationReader rel shape [lo hi]]
  (let [v (.vectorFor rel "c")
        n (.getRowCount rel)
        lt (fn [^RowBits m x] (leaf v '< x m))
        ge (fn [^RowBits m x] (leaf v '>= x m))]
    (case shape
      :lt #(.toSelection ^RowBits (lt (RowBits/all n) hi))
      :range #(.toSelection ^RowBits (lt (ge (RowBits/all n) lo) hi)))))

(defn- gen-fn [^RelationReader rel ^RelationReader args shape lit? [lo hi]]
  (let [form (case [shape lit?]
               [:lt true] (list '< 'c hi)
               [:lt false] '(< c ?hi)
               [:range true] (list 'and (list '>= 'c lo) (list '< 'c hi))
               [:range false] '(and (>= c ?lo) (< c ?hi)))
        spec (gen-spec form rel args)]
    #(.select spec tu/*allocator* rel {} args)))

(t/deftest ^:bench bench
  (when (System/getenv "XTDB_PREDICATE_BENCH")
    (let [rnd (Random. 42)
          rows (atom [])]
      (doseq [type [:i64 :f64 :date :i32]
              nullable? [false true]
              n [75 244 500 1024 4096]
              view [:direct :indirect]
              shape (if (= type :i32) [:lt] [:lt :range])
              :let [[lo hi] (scalars-for type)]]
        (with-open [^Relation base (open-rel [['c (vtype type nullable?) (gen-col type (* 2 n) nullable? rnd)]])
                    args (tu/open-args {:lo lo :hi hi})]
          (let [rel (case view
                      :direct (.select base 0 n)
                      :indirect (.select base (int-array (range 0 (* 2 n) 2))))
                ;; :direct slices too, so both views go through IndirectVector; use a materialised copy for the true direct case
                rel (if (= view :direct) (open-rel [['c (vtype type nullable?) (vec (take n (gen-col type (* 2 n) nullable? (Random. 7))))]]) rel)
                k (nanos-per-call (kernel-fn rel shape [lo hi]))
                gp (nanos-per-call (gen-fn rel args shape false [lo hi]))
                gl (nanos-per-call (gen-fn rel args shape true [lo hi]))]
            (swap! rows conj [(name type) nullable? n (name view) (name shape) k gp gl])
            (when (= view :direct) (.close ^Relation rel)))))
      (spit "/tmp/predicate-bench.tsv"
            (apply str "type\tnullable\trows\tview\tshape\tkernel_ns\tgen_param_ns\tgen_lit_ns\n"
                   (for [[ty nl n v s k gp gl] @rows]
                     (format "%s\t%s\t%d\t%s\t%s\t%.0f\t%.0f\t%.0f\n" ty nl n v s k gp gl)))))))

(t/deftest ^:bench breakdown
  (when (System/getenv "XTDB_PREDICATE_BENCH_BREAKDOWN")
    (let [rnd (Random. 42)
          out (StringBuilder.)]
      (doseq [n [75 244 1024 4096]
              nullable? [false true]]
        (with-open [^Relation rel (open-rel [['c (vtype :i64 nullable?) (gen-col :i64 n nullable? rnd)]])]
          (let [v (.vectorFor rel "c")
                all (RowBits/all n)
                m (leaf v '< 70 all)
                l0 (nanos-per-call #(do (leaf v '< 70 all) (int-array 0)))
                sel (nanos-per-call #(.toSelection ^RowBits m))
                alloc (nanos-per-call #(do (RowBits/all n) (int-array 0)))]
            (.append out (format "i64 nullable=%s n=%d  L0-only=%.0fns  toSelection-only=%.0fns  RowBits/all=%.0fns card=%d\n"
                                 nullable? n l0 sel alloc (.cardinality m))))))
      (spit "/tmp/predicate-breakdown.txt" (str out)))))
