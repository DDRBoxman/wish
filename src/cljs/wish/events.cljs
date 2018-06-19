(ns wish.events
  (:require [re-frame.core :refer [dispatch reg-event-db reg-event-fx
                                   inject-cofx trim-v]]
            [day8.re-frame.tracing :refer-macros [fn-traced defn-traced]]
            [vimsical.re-frame.cofx.inject :as inject]
            [wish.db :as db]
            [wish.providers :as providers]
            [wish.sheets.util :refer [update-uses]]
            [wish.subs :refer [active-sheet-id]]
            [wish.util :refer [invoke-callable]]))

(reg-event-fx
  ::initialize-db
  (fn-traced [_ _]
    {:db db/default-db
     :providers/init! :!}))

(reg-event-db
  :navigate!
  [trim-v]
  (fn-traced [db page-spec]
    (assoc db :page page-spec)))


; ======= sheet-related ====================================

(reg-event-fx
  :load-sheet!
  [trim-v]
  (fn-traced [_ [sheet-id]]
    {:load-sheet! sheet-id}))

; sheet loaded
(reg-event-db
  :put-sheet!
  [trim-v]
  (fn-traced [db [sheet-id sheet]]
    (println "PUT " sheet-id)
    (assoc-in db [:sheets sheet-id] sheet)))

(reg-event-fx
  :load-sheet-source!
  [trim-v]
  (fn-traced [{:keys [db]} [sheet-id sources]]
    ; no dup loads, pls
    (when-not (get-in db [:sheet-sources sheet-id])
      {:db (assoc-in db [:sheet-sources sheet-id] {})
       :load-sheet-source! [sheet-id sources]})))

(reg-event-db
  :put-sheet-source!
  [trim-v]
  (fn-traced [db [sheet-id source]]
    (assoc-in db [:sheet-sources sheet-id]
              {:loaded? true
               :source source})))

(defn apply-limited-use-trigger
  [limited-used-map limited-uses trigger]
  (reduce-kv
    (fn [m use-id used]
      (if-let [use-obj (get limited-uses use-id)]
        (let [restore-amount (invoke-callable
                               use-obj
                               :restore-amount
                               :used used
                               :trigger trigger)
              new-amount (max 0
                              (- used
                                 restore-amount))]
          (assoc m use-id new-amount))

        ; else:
        (do
          (js/console.warn "Found unrelated limited-use " use-id " !!")
          ; TODO should we just dissoc the use-id?
          m)))
    limited-used-map
    limited-used-map))

(reg-event-fx
  :trigger-limited-use-restore
  [trim-v
   (inject-cofx ::inject/sub ^:ignore-dispose [:limited-uses-map])
   (inject-cofx ::inject/sub [:active-sheet-id])]
  (fn-traced [{:keys [db limited-uses-map active-sheet-id]} [triggers]]
    {:db (reduce
           (fn [db trigger]
             (update-in db [:sheets active-sheet-id :limited-uses]
                        apply-limited-use-trigger
                        limited-uses-map
                        trigger))
           db
           (if (coll? triggers)
             triggers
             [triggers]))}))

; toggle whether a single-use limited-use item has been used
(reg-event-db
  :toggle-used
  [trim-v]
  (fn-traced [db [use-id]]
    (update-uses db use-id (fn [uses]
                             (if (> uses 0)
                               0
                               1)))))
