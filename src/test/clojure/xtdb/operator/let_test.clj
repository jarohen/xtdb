(ns xtdb.operator.let-test
  (:require [clojure.test :as t]
            [xtdb.test-util :as tu]))

(t/use-fixtures :each tu/with-allocator)

(t/deftest test-let
  (t/is (= [{:a 4}]
           (tu/query-ra '[:let [foo [::tu/pages
                                     [[{:a 12}, {:a 0}]
                                      [{:a 12}, {:a 100}]]]]
                          [:table [{:a 4}]]]))
        "unused let")

  (t/is (= [[{:a 12}, {:a 0}]
            [{:a 12}, {:a 100}]]
           (tu/query-ra '[:let [foo [::tu/pages
                                     [[{:a 12}, {:a 0}]
                                      [{:a 12}, {:a 100}]]]]
                          [:use-let foo]]
                        {:preserve-pages? true}))
        "normal usage")

  (t/is (= [[{:a 12}, {:a 0}]
            [{:a 12}, {:a 100}]
            [{:a 12}, {:a 0}]
            [{:a 12}, {:a 100}]]
           (tu/query-ra '[:let [foo [::tu/pages
                                     [[{:a 12}, {:a 0}]
                                      [{:a 12}, {:a 100}]]]]
                          [:union-all
                           [:use-let foo]
                           [:use-let foo]]]
                        {:preserve-pages? true}))
        "can use it multiple times")

  (t/is (= [{:a 0} {:a 0}
            {:a 12} {:a 12} {:a 12} {:a 12}
            {:a 100} {:a 100}]
           (tu/query-ra '[:let [foo [::tu/pages
                                     [[{:a 12}, {:a 0}]
                                      [{:a 12}, {:a 100}]]]]
                          [:order-by [[a]]
                           [:union-all
                            [:use-let foo]
                            [:use-let foo]]]]))
        "can pass it to other operators"))
