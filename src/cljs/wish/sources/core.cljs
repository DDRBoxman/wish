(ns ^{:author "Daniel Leong"
      :doc "Core types, etc for DataSource"}
  wish.sources.core
  (:require-macros [wish.util.log :as log])
  (:require [wish.util :refer [->set]]))

(defprotocol IDataSource
  "Anything that provides features, classes, etc."
  (id [this])
  (expand-list [this id options])
  (find-class [this id])
  (find-feature [this id])
  (find-item [this id])
  (find-list-entity [this id] "Find an entity that was in a list")
  (find-race [this id])
  (list-entities [this kind] "Return a list of all entities of the given kind"))

(defn- key-by-id
  [^DataSource s, k id]
  (get-in (.-data s)
          [k id]))

(deftype DataSource [id data]
  IDataSource
  (expand-list [this id options]
    (let [entries (key-by-id this :lists id)]
      (if options
        (let [options-set (->set options)]
          (filter (comp options-set :id)
                  entries))

        ; just return them all
        entries)))

  (find-class [this id]
    (key-by-id this :classes id))

  (find-feature [this id]
    (key-by-id this :features id))

  (find-item [this id]
    (key-by-id this :items id))

  (find-list-entity [this id]
    (key-by-id this :list-entities id))

  (find-race [this id]
    (key-by-id this :races id))

  (list-entities [this kind]
    (vals (get (.-data this) kind)))

  (id [this]
    (.-id this)))

(defn- first-delegate-by-id
  [^CompositeDataSource s, method id]
  (some #(method % id) (.-delegates s)))

(defn- cat-all
  [^CompositeDataSource s, accessor-fn]
  (mapcat
    accessor-fn
    (.-delegates s)) )

(deftype CompositeDataSource [id delegates]
  IDataSource
  (id [this]
    (.-id this))

  (expand-list [this id options]
    (cat-all this
             #(expand-list % id options)))

  (find-class [this id]
    (first-delegate-by-id this find-class id))

  (find-feature [this id]
    ; merge features to pull in alternate options from
    ; secondary data sources
    (->> (.-delegates this)
         (map #(find-feature % id))
         (reduce (fn [a b]
                   (merge-with
                     conj
                     a b)))))

  (find-item [this id]
    (first-delegate-by-id this find-item id))
  (find-list-entity [this id]
    (first-delegate-by-id this find-list-entity id))
  (find-race [this id]
    (first-delegate-by-id this find-race id))

  (list-entities [this kind]
    (cat-all this
             #(list-entities % kind))))

(defn composite
  [id sources]
  (if (not= (count sources) 1)
    (->CompositeDataSource id sources)

    ; if there's only one source, don't bother wrapping it
    (first sources)))
