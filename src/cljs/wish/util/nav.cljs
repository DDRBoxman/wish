(ns ^{:author "Daniel Leong"
      :doc "Navigation util"}
  wish.util.nav
  (:require [clojure.string :as string]
            [secretary.core :as secretary]
            [goog.events :as gevents]
            [goog.history.EventType :as HistoryEventType]
            [pushy.core :as pushy])
  (:import goog.History))

; NOTE: figwheel css live-reload doesn't work so well with
; the fancy nav
(def pushy-supported? false #_(and (not js/goog.DEBUG)
                           (pushy/supported?)))

(def pushy-prefix "/wish")

(defn init! []
  (secretary/set-config! :prefix (if pushy-supported?
                                   (str pushy-prefix "/")
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

(defn replace!
  "Wrapper around js/window.location.replace"
  [new-location]
  (let [new-location (if pushy-supported?
                       new-location
                       (str "#" new-location))]
    (js/window.location.replace new-location)))

(defn sheet-url
  "Generate the url to a sheet, optionally with
   extra path sections after it"
  [id & extra-sections]
  (apply str "/sheets/" (namespace id)
       "/" (name id)
       (when extra-sections
         (interleave (repeat "/")
                     (map
                       #(if (keyword? %)
                          (name %)
                          (str %))
                       extra-sections)))))
