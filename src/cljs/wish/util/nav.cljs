(ns ^{:author "Daniel Leong"
      :doc "Navigation util"}
  wish.util.nav
  (:require [clojure.string :as string]
            [secretary.core :as secretary]
            [goog.events :as gevents]
            [goog.history.EventType :as HistoryEventType]
            [pushy.core :as pushy])
  (:import goog.History))

;; TODO we need a custom 404 page
#_(def pushy-supported? (pushy/supported?))
(def pushy-supported? false)

(defn init! []
  (secretary/set-config! :prefix (if pushy-supported?
                                   "/"
                                   "#")))

;; must be called after routes have been defined
(defn hook-browser-navigation! []
  (if pushy-supported?
    ; fancy html5 navigation
    (let [history (pushy/pushy
                    secretary/dispatch!
                    (fn [x]
                      (let [[uri-path query-string]
                            (string/split (secretary/uri-without-prefix x) #"\?")
                            uri-path (secretary/uri-with-leading-slash uri-path)]
                        (when (secretary/locate-route uri-path)
                          x))))]
      (pushy/start! history))

    ; #-based navigation
    (doto (History.)
      (gevents/listen
        HistoryEventType/NAVIGATE
        (fn [event]
          (secretary/dispatch! (.-token event))))
      (.setEnabled true))))
