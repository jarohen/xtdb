(ns xtdb.xtql.edn
  (:require [xtdb.error :as err])
  (:import (clojure.lang MapEntry)
           (java.util List)
           (xtdb.api.query Queries XtqlQuery$Aggregate XtqlQuery$DocsRelation XtqlQuery$Limit XtqlQuery$Offset XtqlQuery$OrderBy XtqlQuery$OrderDirection XtqlQuery$OrderDirection XtqlQuery$OrderNulls XtqlQuery$OrderSpec XtqlQuery$ParamRelation XtqlQuery$Pipeline XtqlQuery$Return XtqlQuery$Unify XtqlQuery$UnionAll XtqlQuery$Unnest XtqlQuery$Where XtqlQuery$With XtqlQuery$Without)))

(extend-protocol UnparseQuery
  XtqlQuery$Pipeline (unparse-query [query] (list* '-> (unparse-query (.query query)) (mapv unparse-query-tail (.tails query))))

  XtqlQuery$Unify (unparse-query [query] (list* 'unify (mapv unparse-unify-clause (.clauses query))))
  XtqlQuery$UnionAll (unparse-query [query] (list* 'union-all (mapv unparse-query (.queries query))))

  XtqlQuery$DocsRelation
  (unparse-query [this]
    (list 'rel
          (mapv #(into {} (map (fn [[k v]] (MapEntry/create (keyword k) (unparse v)))) %) (.documents this))
          (mapv unparse-out-spec (.bindings this))))

  XtqlQuery$ParamRelation
  (unparse-query [this]
    (list 'rel (symbol (.v (.param this))) (mapv unparse-out-spec (.bindings this)))))

(extend-protocol UnparseQueryTail
  XtqlQuery$Where (unparse-query-tail [query] (list* 'where (mapv unparse (.preds query))))
  XtqlQuery$With (unparse-query-tail [query] (list* 'with (mapv unparse-col-spec (.bindings query))))
  XtqlQuery$Without (unparse-query-tail [query] (list* 'without (map keyword (.cols query))))
  XtqlQuery$Return (unparse-query-tail [query] (list* 'return (mapv unparse-col-spec (.cols query))))
  XtqlQuery$Aggregate (unparse-query-tail [query] (list* 'aggregate (mapv unparse-col-spec (.cols query))))
  XtqlQuery$Limit (unparse-query-tail [this] (list 'limit (.length this)))
  XtqlQuery$Offset (unparse-query-tail [this] (list 'offset (.length this)))
  XtqlQuery$Unnest (unparse-query-tail [this] (list 'unnest (unparse-col-spec (.binding this)))))

