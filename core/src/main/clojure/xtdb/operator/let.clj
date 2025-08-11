(ns xtdb.operator.let
  (:require [clojure.spec.alpha :as s]
            [xtdb.logical-plan :as lp]
            [xtdb.util :as util])
  (:import (xtdb.operator.let LetCursor UseLetCursor)))

(s/def ::binding simple-symbol?)

(defmethod lp/ra-expr :let [_]
  (s/cat :op #{:let}
         :binding (s/tuple ::binding ::lp/ra-expression)
         :relation ::lp/ra-expression))

(defmethod lp/emit-expr :let [{[binding bound-rel] :binding, :keys [relation]} emit-opts]
  (let [{->bound-cursor :->cursor, :as emitted-bound-rel} (lp/emit-expr bound-rel emit-opts)
        {->body-cursor :->cursor, :as emitted-body-rel} (lp/emit-expr relation (assoc-in emit-opts [:let-bindings binding]
                                                                                         emitted-bound-rel))]
    {:fields (:fields emitted-body-rel)
     :stats (:stats emitted-body-rel)
     :->cursor (fn [{:keys [allocator] :as opts}]
                 (util/with-close-on-catch [bound-cursor (->bound-cursor opts)]
                   (LetCursor. allocator bound-cursor
                               (fn [bound-batches]
                                 (->body-cursor (assoc-in opts [:let-bindings binding] bound-batches))))))}))

(defmethod lp/ra-expr :use-let [_]
  (s/cat :op #{:use-let}
         :binding ::binding))

(defmethod lp/emit-expr :use-let [{:keys [binding]} emit-opts]
  (let [{:keys [fields stats]} (get-in emit-opts [:let-bindings binding])]
    {:fields fields, :stats stats
     :->cursor (fn [{:keys [allocator] :as opts}]
                 (UseLetCursor. allocator (get-in opts [:let-bindings binding])))}))
