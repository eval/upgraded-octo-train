(ns bubble-up.main
  (:require [babashka.fs :as fs]
            [babashka.pods :as pods]
            [bubble-up.util :refer [print-error index-by]]
            [clojure.string :as str]
            [honey.sql :as sql]
            [honey.sql.helpers :as sqlh]))

(pods/load-pod 'org.babashka/go-sqlite3 "0.1.0")

(require '[pod.babashka.go-sqlite3 :as sqlite])

(defn ->sql [v]
  (str/join ";\n" v))

(defn table-info-q [table]
  (-> {:select [:*]
       :from   [[[:pragma_table_info table]]]} sql/format))

(defn table-info [db table]
  (sqlite/query db (table-info-q table)))

(def pristine "db/pristine.sqlite")
(def db "db/dev.sqlite")

(defn column-diffs
  "Combined collection of table-info from current and wanted database for table `table`.
  Every column-att (e.g. :name, :type, :dflt_value etc) has a tuple
  with the value for current and wanted.

  e.g. [{:name [nil \"to-create\"] :type [,,,] :notnull [,,,] :pk [,,,] :dflt_value [,,,]}
        {:name [\"to-delete\" nil] ,,,}]"
  [{:keys [db pristine]
    :as   _ctx} table]
  (let [current        (table-info db table)
        wanted         (table-info pristine table)
        ->by-name      #(index-by :name %)
        ->col-names    #(set (keys (->by-name %)))
        removed-cols   (apply disj (->col-names current) (->col-names wanted))
        added-cols     (apply disj (->col-names wanted) (->col-names current))
        all-cols       (apply conj (->col-names current) (->col-names wanted))
        init-val-diff  (comp vec #(repeat 2 %)) ;; "foo" -> ["foo" "foo"]
        result-by-name (-> (->by-name wanted)
                           (merge (select-keys (->by-name current) removed-cols))
                           (update-vals #(update-vals % init-val-diff)))]
    (vals (reduce (fn [acc col]
                    (let [step-fn (fn [col-map [k [v1 v2]]]
                                    (let [[v1 v2] (cond
                                                    (added-cols col)   [nil v2]
                                                    (removed-cols col) [v1 nil]
                                                    :else
                                                    (let [v1 (get-in (->by-name current)
                                                                     [col k])]
                                                      [v1 v2]))]
                                      (assoc col-map k [v1 v2])))]
                      (update acc col #(reduce step-fn {} %))))
                  result-by-name all-cols))))

(defn drop-table [{table :name :as _object}]
  {:op           :drop-table
   :destructive? true
   :sql          (-> (sqlh/drop-table [table]) sql/format first)})

(defn create-table [{:keys [sql] :as _object}]
  {:op           :create-table
   :sql          sql
   :destructive? false})

(defn alter-table [{diffs :column-diffs sql :sql table :name :as _object}]
  (let [new-table        (str table "_new")
        columns-raw      (apply str (drop-while #(not= \( %) sql))
        create-new-table (-> {:create-table new-table
                              :raw          [columns-raw]}
                             sql/format)
        ;; Using `keyword` on col-name is needed to prevent the
        ;; insert-into to have single quotes (ie string literal).
        ;; NOTE might not be so bad:
        ;; (sql/format {:select [(keyword "some bar")]} {:inline true})
        ;; ;;=> ["SELECT \"some bar\""]
        common-columns   (map (comp keyword first)
                              (filter #(every? some? %)
                                      (map :name diffs)))
        data->new-table  (-> (sqlh/insert-into new-table common-columns
                                               {:select common-columns
                                                :from   [[[:inline table]]]})
                             (sql/format {:inline true}))
        drop-old-table   (-> (sqlh/drop-table [table])
                             sql/format)
        rename-new->old  (-> (sqlh/alter-table new-table)
                             (sqlh/rename-table [[:inline table]])
                             sql/format)
        sql              (->sql (vec (concat create-new-table
                                             data->new-table
                                             drop-old-table
                                             rename-new->old)))
        destructive?     (some (comp nil? last :name) diffs)]
    {:op           :alter-table
     :sql          sql
     :destructive? destructive?}))

(defn migrate-object-dispatch [{:keys [type] :as _object}]
  (keyword type))

(defmulti migrate-object #'migrate-object-dispatch)
(defmethod migrate-object :table [object]
  (case (:wanted object)
    :drop   (drop-table object)
    :create (create-table object)
    (alter-table object)))

(defn init-pristine! [schema-file {:keys [path]}]
  (let [db (fs/file path "pristine.sqlite")]
    (fs/delete-if-exists db)
    (fs/delete-on-exit db)
    (doto (str db) (sqlite/execute! (slurp schema-file)))))

(defn find-objects [db]
  (sqlite/query db (-> {:select [:type :name :sql]
                        :from   [:sqlite_schema]
                        :where  [:and
                                 [:= :type "table"] ;; ...only, for now
                                 [:not= :name "sqlite_sequence"]
                                 [:not-like :name [:inline "sqlite_autoindex_%"]]]}
                       (sql/format))))

(defn objects-to-migrate [{:keys [db pristine] :as ctx}]
  (let [current-objects (find-objects db)
        wanted-objects  (find-objects pristine)

        ;; example:
        ;; {"changed-table" {:current {,,,}
        ;;                  :wanted {,,,}}
        ;; "to-create" {:wanted {,,,}}
        ;; "to-drop" {:current {,,,}}}
        current&wanted-tables-by-name
        (merge-with merge
                    (update-vals (index-by :name current-objects)
                                 #(assoc {} :current %))
                    (update-vals (index-by :name wanted-objects)
                                 #(assoc {} :wanted %)))]
    (reduce (fn [acc [table-name {:keys [current wanted]}]]
              (if-let [object (cond
                                (and current (nil? wanted))
                                (assoc current :wanted :drop)

                                (and wanted (nil? current))
                                (assoc wanted :wanted :create)

                                (not= (:sql current) (:sql wanted))
                                (assoc wanted
                                       :wanted :alter
                                       :column-diffs (column-diffs ctx table-name)))]
                (conj acc object)
                acc))
            [] current&wanted-tables-by-name)))

(defn wrap-tx [sql]
  (str "BEGIN;" \newline
       sql ";" \newline
       "COMMIT;"))

(defn init-ctx [db schema]
  (let [db-path (fs/parent db)]
    {:db       db
     :pristine (doto (init-pristine! schema {:path db-path})
                 (fs/delete-on-exit))}))

(defn validate-ops! [ops {:keys [allow-deletions] :as _cli-opts}]
  (when (and (not allow-deletions)
             (some :destructive? ops))
    (throw (ex-info "Error: Doing destructive operations requires flag --allow-deletions" {}))))

(defn migrate-cmd
  {:org.babashka/cli {:restrict [:e :env ;; bubble-config
                                 :allow-deletions :db :help :schema]
                      :spec     {:help            {:alias :h}
                                 :allow-deletions {:default false :coerce :boolean}
                                 :schema          {:require true}
                                 :db              {:require true}}}}
  [& {:keys [db schema] :as args}]
  #_(prn :args args)
  (let [ctx     (init-ctx db schema)
        ops     (map migrate-object (objects-to-migrate ctx))
        loaded? (= *file* (System/getProperty "babashka.file"))]
    (try
      (validate-ops! ops args)
      (->> ops
           (map :sql)
           ->sql
           wrap-tx
           #_println
           (sqlite/execute! db))
      (catch Exception e
        (if-not loaded?
          (do (print-error (ex-message e)) (System/exit 1))
          (throw e))))))
