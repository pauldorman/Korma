(ns korma.sql.fns
  (:require [korma.sql.engine :as eng])
  (:use [korma.sql.engine :only [infix group-with wrapper sql-func]]))

;;*****************************************************
;; Predicates
;;*****************************************************

(def pred-and  eng/pred-and)
(defn pred-or [& args] (group-with " OR " args))
(defn pred-not [v] (wrapper "NOT" v))

(defn pred-in [k v] (infix k "IN" v))
(defn pred-> [k v] (infix k ">" v))
(defn pred-< [k v] (infix k "<" v))
(defn pred-between [k from to]
  (infix k "BETWEEN" (group-with " AND " [from to])))
(defn pred->= [k v] (infix k ">=" v))
(defn pred-<= [k v] (infix k "<=" v))
(defn pred-like [k v] (infix k "LIKE" v))

(def pred-= eng/pred-=)
(defn pred-not= [k v] (cond
                        (and k v) (infix k "!=" v)
                        k (infix k "IS NOT" v)
                        v (infix v "IS NOT" k)))

;;*****************************************************
;; Aggregates
;;*****************************************************

(defn agg-count [v] (sql-func "COUNT" v))
(defn agg-sum [v] (sql-func "SUM" v))
(defn agg-avg [v] (sql-func "AVG" v))
(defn agg-min [v] (sql-func "MIN" v))
(defn agg-max [v] (sql-func "MAX" v))
(defn agg-first [v] (sql-func "FIRST" v))
(defn agg-last [v] (sql-func "LAST" v))
