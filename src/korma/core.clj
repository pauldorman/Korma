(ns korma.core
  (:require [korma.sql.engine :as eng]
            [korma.sql.fns :as sfns]
            [korma.sql.utils :as utils]
            [clojure.set :as set]
            [korma.db :as db])
  (:use [korma.sql.engine :only [bind-query bind-params]]
        [clojure.walk :only [postwalk-replace]]
        [clojure.string :only [split]]))

(def ^{:dynamic true} *exec-mode* false)
(declare get-rel)

;;*****************************************************
;; Query types
;;*****************************************************

(defn- check-ent [ent]
  (when-not (or (string? ent)
                (map? ent))
    (throw (Exception. (str "Invalid entity provided for the query: " ent)))))

(defn empty-query [ent]
  (let [ent (if (keyword? ent)
              (name ent)
              ent)
        [ent table alias db opts] (if (string? ent)
                                    [{:table ent} ent nil nil nil]
                                    [ent (:table ent) (:alias ent) 
                                     (:db ent) (get-in ent [:db :options])])]
    {:ent ent
     :table table
     :db db
     :options opts
     :alias alias}))

(defn select* 
  "Create an empty select query. Ent can either be an entity defined by defentity,
  or a string of the table name"
  [ent]
  (if (:type ent)
    ent
    (let [q (empty-query ent)]
      (merge q {:type :select
                :fields [::*]
                :from [(:ent q)]
                :modifiers []
                :joins []
                :where []
                :order []
                :group []
                :results :results}))))

(defn update* 
  "Create an empty update query. Ent can either be an entity defined by defentity,
  or a string of the table name."
  [ent]
  (if (:type ent)
    ent
    (let [q (empty-query ent)]
      (merge q {:type :update
                :fields {}
                :where []
                :results :keys}))))

(defn delete* 
  "Create an empty delete query. Ent can either be an entity defined by defentity,
  or a string of the table name"
  [ent]
  (if (:type ent)
    ent
    (let [q (empty-query ent)]
      (merge q {:type :delete
                :where []
                :results :keys}))))

(defn insert* 
  "Create an empty insert query. Ent can either be an entity defined by defentity,
  or a string of the table name"
  [ent]
  (if (:type ent)
    ent
    (let [q (empty-query ent)]
      (merge q {:type :insert
                :values []
                :results :keys}))))

;;*****************************************************
;; Query macros
;;*****************************************************

(defmacro select 
  "Creates a select query, applies any modifying functions in the body and then
  executes it. `ent` is either a string or an entity created by defentity.
  
  ex: (select user 
        (fields :name :email)
        (where {:id 2}))"
  [ent & body]
  `(let [query# (-> (select* ~ent)
                 ~@body)]
     (exec query#)))

(defmacro update 
  "Creates an update query, applies any modifying functions in the body and then
  executes it. `ent` is either a string or an entity created by defentity.
  
  ex: (update user 
        (set-fields {:name \"chris\"}) 
        (where {:id 4}))"
  [ent & body]
  `(let [query# (-> (update* ~ent)
                  ~@body)]
     (exec query#)))

(defmacro delete 
  "Creates a delete query, applies any modifying functions in the body and then
  executes it. `ent` is either a string or an entity created by defentity.
  
  ex: (delete user 
        (where {:id 7}))"
  [ent & body]
  `(let [query# (-> (delete* ~ent)
                  ~@body)]
     (exec query#)))

(defmacro insert 
  "Creates an insert query, applies any modifying functions in the body and then
  executes it. `ent` is either a string or an entity created by defentity. Inserts
  return the last inserted id.
  
  ex: (insert user 
        (values [{:name \"chris\"} {:name \"john\"}]))"
  [ent & body]
  `(let [query# (-> (insert* ~ent)
                  ~@body)]
     (exec query#)))

;;*****************************************************
;; Query parts
;;*****************************************************

(defn unalias-form
  [form query-or-ent]
  (let [aliases (or (-> query-or-ent :ent :aliases)
                    (:aliases query-or-ent))]
    (postwalk-replace aliases form)))

(defn- unalias-field
  [field aliases]
  (cond (keyword? field) (or (field aliases) field)
        (map? field) field
        (coll? field) (or ((second field) aliases) (first field))
        :default field))

(defn- unalias-fields
  [query]
  (let [fields (-> query :fields)
        aliases (postwalk-replace
                 (into {} (filter coll? fields))
                 (-> query :ent :aliases))]
    (if (and fields (not-empty aliases))
      (-> query
          (assoc-in [:ent :aliases] aliases)
          (assoc-in [:fields] (map #(unalias-field % aliases) fields)))
      query)))

(defn- alias-results
  [query results]
  (if (and (= (:type query) :select)
           (not (empty? results)))
    (let [aliases (into {} (for [f (:fields query)]
                             (-> (filter
                                  #(= f (val %)) (-> query :ent :aliases))
                                 first reverse vec)))]
      (map #(postwalk-replace aliases %) results))))

(defn- update-fields [query fs]
  (let [[first-cur] (:fields query)]
    (if (= first-cur ::*)
      (assoc query :fields fs)
      (update-in query [:fields] concat fs))))

(defn fields
  "Set the fields to be selected in a query. Fields can either be a keyword
  or a vector of two keywords [field alias]:
  
  (fields query :name [:firstname :first])"
  [query & vs]
  (-> query (update-fields vs) unalias-fields))

(defn set-fields
  "Set the fields and values for an update query."
  [query fields-map]
  (update-in query [:set-fields] merge (unalias-form fields-map query)))

(defn from
  "Add tables to the from clause."
  [query table]
  (update-in query [:from] conj table))

(defn where*
  "Add a where clause to the query. Clause can be either a map or a string, and
  will be AND'ed to the other clauses."
  [query clause]
  (update-in query [:where] conj clause))

(defmacro where
  "Add a where clause to the query, expressing the clause in clojure expressions
  with keywords used to reference fields.
  e.g. (where query (or (= :hits 1) (> :hits 5)))

  Available predicates: and, or, =, not=, <, >, <=, >=, in, between, between!, like, not

  There's no standard for the clusivity of the SQL BETWEEN operator, so two predicates
  are provided for the inclusive and exclusive forms.
  e.g. (between :age [20 25]) is equivalent to (between! :age [19 26])

  Where can also take a map at any point and will create a clause that compares keys
  to values. The value can be a vector with one of the above predicate functions 
  describing how the key is related to the value: (where query {:name [like \"chris\"]})"
  [query form]
  `(let [q# ~query]
     (where* q# 
             (bind-query q#
                         (eng/pred-map
                          ~(eng/parse-where
                            `(unalias-form ~form ~query)))))))

(defn order
  "Add an ORDER BY clause to a select query. field should be a keyword of the field name, dir
  is ASC by default.
  
  (order query :created :asc)"
  [query field & [dir]]
  (update-in query [:order]
             conj [(unalias-form field query) (or dir :ASC)]))

(defn values
  "Add records to an insert clause. values can either be a vector of maps or a single
  map.
  
  (values query [{:name \"john\"} {:name \"ed\"}])"
  [query values]
  (let [values (unalias-form values query)]
    (update-in query [:values] concat (if (map? values)
                                        [values]
                                        values))))

(defn join* [query type table clause]
  (update-in query [:joins] conj [type table clause]))

(defmacro join 
  "Add a join clause to a select query, specifying the table name to join and the predicate
  to join on.
  
  (join query addresses)
  (join query addresses (= :address.users_id :users.id))
  (join query :right addresses (= :address.users_id :users.id))"
  ([query ent]
   `(let [q# ~query
          e# ~ent
          rel# (get-rel (:ent q#) e#)]
      (join* q# :left e# (sfns/pred-= (:pk rel#) (:fk rel#)))))
  ([query table clause]
   `(join* ~query :left ~table ~(eng/parse-where clause)))
  ([query type table clause]
   `(join* ~query ~type ~table ~(eng/parse-where clause))))

(defn post-query
  "Add a function representing a query that should be executed for each result in a select.
  This is done lazily over the result set."
  [query post]
  (update-in query [:post-queries] conj post))

(defn limit
  "Add a limit clause to a select query."
  [query v]
  (assoc query :limit v))

(defn offset
  "Add an offset clause to a select query."
  [query v]
  (assoc query :offset v))

(defn group
  "Add a group-by clause to a select query"
  [query & fields]
  (update-in query [:group] concat (unalias-form fields query)))

(defn- aggregate-field
  "Determines field name for the given aggregate form."
  [form]
  (if (coll? form)
    (keyword (first form))
    (keyword form)))

(defmacro aggregate
  "Use a SQL aggregator function, aliasing the results, and optionally grouping by
  a field:
  
  (select users 
    (aggregate (count :*) :cnt :status))
  
  Aggregates available: count, sum, avg, min, max, first, last"
  [query agg alias & [group-by]]
  `(let [q# ~query
         f# ~(aggregate-field agg)
         a# ~alias]
     (bind-query q#
                 (let [alias# {a# f#}
                       res# (-> q#
                                (fields ~(eng/parse-aggregate agg))
                                (update-in [:ent :aliases] merge alias#))]
                   (if ~group-by
                     (group res# ~group-by)
                     res#)))))

;;*****************************************************
;; Other sql
;;*****************************************************

(defn sqlfn* 
  "Call an arbitrary SQL function by providing the name of the function
  and its params"  
  [fn-name & params]
  (apply eng/sql-func (name fn-name) params))

(defmacro sqlfn 
  "Call an arbitrary SQL function by providing func as a symbol or keyword
  and its params"
  [func & params]
  `(sqlfn* (quote ~func) ~@params))

(defmacro subselect [& parts]
  `(utils/sub-query (query-only (select ~@parts))))

(defn modifier [query & modifiers]
  (update-in query [:modifiers] conj (reduce str modifiers)))

(def raw utils/generated)

;;*****************************************************
;; Query exec
;;*****************************************************

(defmacro sql-only
  "Wrap around a set of queries so that instead of executing, each will return a string of the SQL 
  that would be used."
  [& body]
  `(binding [*exec-mode* :sql]
     ~@body))

(defmacro dry-run
  "Wrap around a set of queries to print to the console all SQL that would 
  be run and return dummy values instead of executing them."
  [& body]
  `(binding [*exec-mode* :dry-run]
     ~@body))

(defmacro query-only
  "Wrap around a set of queries to force them to return their query objects."
  [& body]
  `(binding [*exec-mode* :query]
     ~@body))

(defn as-sql
  "Force a query to return a string of SQL when (exec) is called."
  [query]
  (bind-query query (:sql-str (eng/->sql query))))

(defn- apply-posts
  [query results]
  (if-let [posts (seq (:post-queries query))]
    (let [post-fn (apply comp posts)]
      (post-fn results))
    results))

(defn- apply-transforms
  [query results]
  (if (not= (:type query) :select)
    results
    (if-let [trans (seq (-> query :ent :transforms))]
      (let [trans-fn (apply comp trans)]
        (map trans-fn results))
      results)))

(defn- apply-prepares
  [query]
  (if-let [preps (seq (-> query :ent :prepares))]
    (let [preps (apply comp preps)]
      (condp = (:type query)
        :insert (let [values (:values query)]
                  (assoc query :values (map preps values)))
        :update (let [value (:set-fields query)]
                  (assoc query :set-fields (preps value)))
        query))
    query))

(defn exec
  "Execute a query map and return the results."
  [query]
  (let [query (-> query apply-prepares)
        query (bind-query query (eng/->sql query))
        sql (:sql-str query)
        params (:params query)]
    (cond
      (:sql query) sql
      (= *exec-mode* :sql) sql
      (= *exec-mode* :query) query
      (= *exec-mode* :dry-run) (do
                                 (println "dry run ::" sql "::" (vec params))
                                 (let [pk (-> query :ent :pk)
                                       results (apply-posts query [{pk 1}])]
                                   (first results)
                                   results))
      :else (let [results (alias-results query (db/do-query query))]
              (apply-transforms query (apply-posts query results))))))

(defn exec-raw
  "Execute a raw SQL string, supplying whether results should be returned. `sql` can either be
  a string or a vector of the sql string and its params. You can also optionally
  provide the connection to execute against as the first parameter.
  
  (exec-raw [\"SELECT * FROM users WHERE age > ?\" [5]] :results)"
  [conn? & [sql with-results?]]
  (let [sql-vec (fn [v] (if (vector? v) v [v nil]))
        [conn? [sql-str params] with-results?] (if (or (string? conn?)
                                                       (vector? conn?))
                                                 [nil (sql-vec conn?) sql]
                                                 [conn? (sql-vec sql) with-results?])]
    (db/do-query {:db conn? :results with-results? :sql-str sql-str :params params})))

;;*****************************************************
;; Entities
;;*****************************************************

(defn create-entity
  "Create an entity representing a table in a database."
  [table]
  {:table table
   :name table
   :pk :id
   :db nil
   :transforms '()
   :prepares '()
   :fields []
   :aliases {}
   :rel {}}) 

(defn create-relation
  "Create a relation map describing how two entities are related."
  [ent sub-ent type opts]
  (let [[pk fk foreign-ent] (condp = type
                  :has-one [(raw (eng/prefix ent (:pk ent)))
                            (raw (eng/prefix sub-ent (keyword (str (:table ent) "_id"))))
                            sub-ent]
                  :belongs-to [(raw (eng/prefix sub-ent (:pk sub-ent)))
                               (raw (eng/prefix ent (keyword (str (:table sub-ent) "_id"))))
                               ent]
                  :has-many [(raw (eng/prefix ent (:pk ent)))
                             (raw (eng/prefix sub-ent (keyword (str (:table ent) "_id"))))
                             sub-ent])
        opts (when (:fk opts)
               {:fk (raw (eng/prefix foreign-ent (:fk opts)))})]
    (merge {:table (:table sub-ent)
            :alias (:alias sub-ent)
            :rel-type type
            :pk pk
            :fk fk}
           opts)))

(defn rel
  [ent sub-ent type opts]
  (let [var-name (-> sub-ent meta :name)
        cur-ns *ns*]
    (assoc-in ent [:rel (name var-name)]
              (delay 
                (let [resolved (ns-resolve cur-ns var-name)
                      sub-ent (when resolved
                                (deref sub-ent))]
                  (when-not (map? sub-ent)
                    (throw (Exception. (format "Entity used in relationship does not exist: %s" (name var-name)))))
                  (create-relation ent sub-ent type opts))))))

(defn get-rel [ent sub-ent]
  (let [sub-name (if (map? sub-ent)
                   (:name sub-ent)
                   sub-ent)]
    (force (get-in ent [:rel sub-name]))))

(defmacro has-one
  "Add a has-one relationship for the given entity. It is assumed that the foreign key
  is on the sub-entity with the format table_id: user.id = address.user_id
  Opts can include a key for :fk to explicitly set the foreign key.
  
  (has-one users address {:fk :addressID})"
  [ent sub-ent & [opts]]
  `(rel ~ent (var ~sub-ent) :has-one ~opts))

(defmacro belongs-to
  "Add a belongs-to relationship for the given entity. It is assumed that the foreign key
  is on the current entity with the format sub-ent-table_id: email.user_id = user.id.
  Opts can include a key for :fk to explicitly set the foreign key.
  
  (belongs-to users email {:fk :emailID})"
  [ent sub-ent & [opts]]
  `(rel ~ent (var ~sub-ent) :belongs-to ~opts))

(defmacro has-many
  "Add a has-many relation for the given entity. It is assumed that the foreign key
  is on the sub-entity with the format table_id: user.id = email.user_id
  Opts can include a key for :fk to explicitly set the foreign key.
  
  (has-many users email {:fk :emailID})"
  [ent sub-ent & [opts]]
  `(rel ~ent (var ~sub-ent) :has-many ~opts))

(defn pk
  "Set the primary key used for an entity. :id by default."
  [ent pk]
  (let [aliases (:aliases ent)
        pk (keyword pk)]
    (assoc ent :pk (or (pk aliases) pk))))

(defn entity-fields
  "Set the fields to be retrieved by default in select queries for the
  entity."
  [ent & fields]
  (let [aliases (:aliases ent)]
    (update-in ent [:fields]
               concat (map #(eng/prefix ent %)
                           (postwalk-replace aliases fields)))))

(defn realias-pk
  [ent]
  (let [aliases (:aliases ent)]
    (if-let [primary-key (:pk ent)]
      (-> ent (pk (or (:pk aliases) primary-key)))
      ent)))

(defn realias-fields
  [ent]
  (let [aliases (:aliases ent)
        fields (map
                #(if (string? %)
                   (keyword (last (split % #"\""))) %)
                (:fields ent))]
    (if-not (empty? fields)
      (apply entity-fields (dissoc ent :fields) fields)
      ent)))

(defn aliases
  "Set the default field aliases for the entity. If set, alias names can be used in
   place of actual field names. Aliases can be overridden in select queries with field
   alias vectors, and cannot be used in raw SQL queries.

   The alias map takes the form {:alias :field}."
  [ent m]
  (-> ent
      (update-in [:aliases] merge m)
      realias-pk
      realias-fields))

(defn table
  "Set the name of the table and an optional alias to be used for the entity. 
  By default the table is the name of entity's symbol."
  [ent t & [alias]]
  (let [tname (if (or (keyword? t)
                      (string? t))
                (name t)
                (if alias
                  t
                  (throw (Exception. "Generated tables must have aliases."))))
        ent (assoc ent :table tname)]
    (if alias
      (assoc ent :alias (name alias))
      ent)))

(defn database
  "Set the database connection to be used for this entity."
  [ent db]
  (assoc ent :db db))

(defn transform
  "Add a function to be applied to results coming from the database"
  [ent func]
  (update-in ent [:transforms] conj func))

(defn prepare
  "Add a function to be applied to records/values going into the database"
  [ent func]
  (update-in ent [:prepares] conj func))

(defmacro defentity
  "Define an entity representing a table in the database, applying any modifications in
  the body."
  [ent & body]
  `(let [e# (-> (create-entity ~(name ent))
              ~@body)]
     (def ~ent e#)))

;;*****************************************************
;; With
;;*****************************************************

(defn- force-prefix [ent fields]
  (for [field fields]
    (if (vector? field)
      [(utils/generated (eng/prefix ent (first field))) (second field)]
      (eng/prefix ent field))))

(defn merge-part [query neue k]
  (update-in query [k] #(if-let [vs (k neue)]
                          (vec (concat % vs))
                          %)))

(defn- merge-query [query neue]
  (reduce #(merge-part % neue %2)
          query
          [:fields :group :order :where :params :joins :post-queries]))

(defn- sub-query [query sub-ent func]
  (let [neue (select* sub-ent)
        neue (bind-query neue (func neue))
        neue (-> neue
                 (update-in [:fields] #(force-prefix sub-ent %))
                 (update-in [:order] #(force-prefix sub-ent %))
                 (update-in [:group] #(force-prefix sub-ent %)))]
    (merge-query query neue)))

(defn- with-later [rel query ent func]
  (let [fk (:fk rel)
        pk (get-in query [:ent :pk])
        table (keyword (eng/table-alias ent))]
    (post-query query 
                (partial map 
                         #(assoc % table
                                 (select ent
                                         (func)
                                         (where {fk (get % pk)})))))))

(defn- with-now [rel query ent func]
  (let [table (if (:alias rel)
                [(:table ent) (:alias ent)]
                (:table ent))
        query (join query table (= (:pk rel) (:fk rel)))]
    (sub-query query ent func)))

(defn with* [query sub-ent func]
  (let [rel (get-rel (:ent query) sub-ent)]
    (cond
      (not rel) (throw (Exception. (str "No relationship defined for table: " (:table sub-ent))))
      (#{:has-one :belongs-to} (:rel-type rel)) (with-now rel query sub-ent func)
      :else (with-later rel query sub-ent func))))

(defmacro with
  "Add a related entity to the given select query. If the entity has a relationship
  type of :belongs-to or :has-one, the requested fields will be returned directly in
  the result map. If the entity is a :has-many, a second query will be executed lazily
  and a key of the entity name will be assoc'd with a vector of the results.
  
  (defentity email (entity-fields :email))
  (defentity user (has-many email))
  (select user
  (with email) => [{:name \"chris\" :email [{email: \"c@c.com\"}]} ..."
  [query ent & body]
  `(with* ~query ~ent (fn [q#]
                        (-> q#
                            ~@body))))
