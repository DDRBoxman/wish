(ns ^{:author "Daniel Leong"
      :doc "Data Sources"}
  wish.sources
  (:require-macros [cljs.core.async.macros :refer [go go-loop]])
  (:require [clojure.core.async :as async :refer [alts! chan <! >! put!]]
            [clojure.tools.reader.reader-types :refer [string-push-back-reader]]
            [cljs.reader :as edn]
            [ajax.core :refer [GET]]
            [wish.sources.compiler :refer [compile-directives]]
            [wish.sources.core :refer [IDataSource ->DataSource id ->CompositeDataSource]]
            [wish.util :refer [>evt]]))

;; TODO this is probably different in prod
(def ^:private data-root "/sources")

(def ^:private builtin-sources
  {:wish/dnd5e-srd "/dnd5e.edn"})

(defonce ^:private loaded-sources (atom {}))

(defn- compile-raw-source
  [id raw]
  (loop [reader (string-push-back-reader raw)
         directives []]
    (if-let [d (edn/read reader)]
      (do
        (println "READ << " d)
        (recur reader (conj directives d)))

      (->DataSource
        id
        (compile-directives
          directives)))))

(defn- load-builtin!
  [source-id]
  (let [ch (chan)
        url (str data-root (builtin-sources source-id))]
    (GET url
         {:handler
          (fn [raw]
            (go (>! ch
                    (compile-raw-source source-id raw))))})

    ; return the ch
    ch))

(defn- load-source!
  "Returns a channel that signals with the source when done"
  [source-id]
  (if-let [existing (get @loaded-sources source-id)]
    ; already done!
    existing

    (let [kind (namespace source-id)]
      (case kind
        "wish" (load-builtin! source-id)

        (println "Unknown source kind " kind)))))

(defn- combine-sources!
  "Combine the given sources into a CompositeDataSource
   and save it to the app-db for the given sheet-id"
  [sheet-id sources]
  (>evt [:put-sheet-source!
         sheet-id
         (->CompositeDataSource
           sheet-id
           sources)]))

(defn load!
  [sheet-id sources]
  (let [existing @loaded-sources]
    (if (every? existing sources)
      (combine-sources! sheet-id
                        (map existing sources))

      (let [source-chs (map load-source! sources)
            total-count (count sources)]
        (println "LOAD " sources)
        (go-loop [resolved []]
          (let [[loaded-src _] (alts! source-chs)
                new-resolved (conj resolved loaded-src)]
            (when loaded-src
              (swap! loaded-sources assoc (id loaded-src) loaded-src))
            (if (= total-count (count new-resolved))
              ; DONE!
              (do
                (println "LOADED" new-resolved)
                (combine-sources! sheet-id new-resolved))

              ; still waiting
              (do
                (println "Loaded " (id loaded-src) "; still waiting...")
                (recur new-resolved)))))))))
