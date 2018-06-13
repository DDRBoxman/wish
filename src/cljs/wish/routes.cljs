(ns wish.routes
  (:require-macros [secretary.core :refer [defroute]])
  (:require [re-pressed.core :as rp]
            [pushy.core :as pushy]
            [wish.events :as events]
            [wish.util :refer [>evt navigate!]]
            [wish.util.nav :as nav :refer [hook-browser-navigation!]]))

(defn app-routes []
  (nav/init!)

  ;; --------------------
  ;; define routes here
  (defroute "/" []
    (navigate! :home)
    (>evt [::events/set-re-pressed-example nil])
    (>evt
     [::rp/set-keydown-rules
      {:event-keys [[[::events/set-re-pressed-example "Hello, world!"]
                     [{:which 72} ;; h
                      {:which 69} ;; e
                      {:which 76} ;; l
                      {:which 76} ;; l
                      {:which 79} ;; o
                      ]]]

       :clear-keys
       [[{:which 27} ;; escape
         ]]}])
    )

  (defroute "/about" []
    (navigate! :about))


  ;; --------------------
  (hook-browser-navigation!))

