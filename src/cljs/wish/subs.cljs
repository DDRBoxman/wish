(ns wish.subs
  (:require [re-frame.core :refer [reg-sub subscribe]]
            [wish.db :as db]
            [wish.subs-util :refer [active-sheet-id]]
            [wish.sheets :as sheets]
            [wish.sources.compiler :refer [inflate]]
            [wish.sources.core :as src :refer [find-class find-race]]))

(reg-sub :showing-overlay :showing-overlay)

; ======= Provider-related =================================

(reg-sub :provider-states :provider-states)
(reg-sub
  :provider-state
  :<- [:provider-states]
  (fn [states [_ provider-id]]
    (when-not provider-id
      (js/console.warn "[subs] nil provider-id provided to :provider-state"))
    (get states provider-id :idle)))

(reg-sub
  :providers-listing?
  (fn [db _]
    (seq (:providers-listing db))))


; ======= Sheet-related ====================================

(defn reg-sheet-sub
  [id getter]
  ; NOTE: instead of depending on a single subscription,
  ; we go ahead and create a separate subscription for
  ; each part of the sheet, to avoid a small edit to HP,
  ; for example, causing all of the spell lists and features
  ; (which rely on classes, etc.) to be re-calculated
  (reg-sub
    id
    :<- [:sheet-meta]
    (fn [sheet _]
      (getter sheet))))

(reg-sub :page :page)
(reg-sub :sheets :sheets)
(reg-sub :sheet-sources :sheet-sources)

(reg-sheet-sub :sheet :sheet)
(reg-sheet-sub :sheet-kind :kind)
(reg-sheet-sub :class-metas (comp vals :classes))
(reg-sheet-sub :race-ids :races)
(reg-sheet-sub :limited-used :limited-uses)
(reg-sheet-sub :options :options)
(reg-sheet-sub :inventory :inventory)
(reg-sheet-sub :items :items)

(reg-sub
  :active-sheet-id
  :<- [:page]
  (fn [page-vec _]
    (active-sheet-id nil page-vec)))

(reg-sub
  :provided-sheet
  :<- [:sheets]
  (fn [sheets [_ sheet-id]]
    (get sheets sheet-id)))

(reg-sub
  :known-sheets
  :<- [:sheets]
  (fn [sheets _]
    (->> sheets
         (map (fn [[id v]]
                (assoc v :id id)))
         (filter :name)
         (sort-by :name))))

; if a specific sheet-id is not provided, loads
; for the active sheet id
(reg-sub
  :sheet-source
  :<- [:sheet-sources]
  :<- [:active-sheet-id]
  (fn [[sources active-id] [_ sheet-id]]
    (let [{:keys [source loaded?]} (get sources (or sheet-id
                                                    active-id))]
      (when loaded?
        source))))

(reg-sub
  :sheet-error-info
  :<- [:sheet-sources]
  :<- [:active-sheet-id]
  (fn [[sources active-id] [_ sheet-id]]
    (let [{:keys [err] :as info} (get sources (or sheet-id
                                                    active-id))]
      (when err
        info))))


; ======= Accessors for the active sheet ===================

(reg-sub
  :sheet-meta
  :<- [:sheets]
  :<- [:active-sheet-id]
  (fn [[sheets id]]
    (get sheets id)))

(reg-sub
  :classes
  :<- [:sheet-kind]
  :<- [:sheet-source]
  :<- [:options]
  :<- [:class-metas]
  (fn [[sheet-kind source options metas] _]
    (when source
      (->> metas
           (map (fn [m]
                  (merge m (find-class source (:id m)))))
           (map (fn [c]
                  (-> c
                      (inflate source options)
                      (sheets/post-process
                        sheet-kind
                        source
                        :class)))))
      )))

(reg-sub
  :races
  :<- [:sheet-meta]
  :<- [:sheet-source]
  :<- [:options]
  :<- [:race-ids]
  (fn [[sheet-meta source options ids] _]
    (when source
      (->> ids
           (map (partial find-race source))
           (map (fn [r]
                  (-> r
                      (inflate source options)
                      (sheets/post-process
                        (:kind sheet-meta)
                        source
                        :race))))))))

(defn- get-features
  [feature-containers [_ entity-id primary?]]
  (->> (if entity-id
         (filter #(= entity-id (:id %)) feature-containers)
         feature-containers)
       (mapcat :features)

       ; remove features that only the primary class should have
       ; if we're not the primary
       (remove #(when (:primary-only? (second %))
                  (not primary?)))))

(defn inflate-option-values
  [data-source feature-id values]
  (or (:values
        (src/find-feature data-source feature-id))

      ; not a feature with :values? Okay inflate now
      (map
        (fn [opt-or-id]
          (if (keyword? opt-or-id)
            (src/find-feature data-source opt-or-id)
            opt-or-id))
        values)))

(defn- inflate-feature-options
  [[features data-source]]
  (->> features
       (filter (comp :max-options second))
       (map (fn [[id v]]
              [id (update v :values (partial inflate-option-values
                                             data-source
                                             id))]))))

(reg-sub
  :class-features
  :<- [:classes]
  get-features)

(reg-sub
  :class-features-with-options
  (fn [[_ entity-id primary?]]
    [(subscribe [:class-features entity-id primary?])
     (subscribe [:sheet-source])])
  inflate-feature-options)

(reg-sub
  :race-features
  :<- [:races]
  get-features)

(reg-sub
  :race-features-with-options
  :<- [:race-features]
  :<- [:sheet-source]
  inflate-feature-options)

; semantic convenience for single-race systems
(reg-sub
  :race
  :<- [:races]
  (fn [races _]
    (first races)))

(defn- uses-with-context
  [kind entity]
  (->> entity
       :limited-uses
       vals
       (map (fn [item]
              (assoc item
                     :wish/context-type kind
                     :wish/context entity)))))

(reg-sub
  :limited-uses
  :<- [:classes]
  :<- [:races]
  ; TODO also, probably, items?
  (fn [[classes races]]
    (flatten
      (concat
        (->> races
             (map (partial uses-with-context :race)))
        (->> classes
             (map (partial uses-with-context :class)))))))

(reg-sub
  :limited-uses-map
  :<- [:limited-uses]
  (fn [limited-uses]
    (reduce
      (fn [m v]
        (assoc m (:id v) v))
      {}
      limited-uses)))

(defn inflate-item
  "Given the character's :items map and datasource,
   return the inflated item for the given inst-id."
  [inst-id items data-source]
  (let [item (get items inst-id)
        item-id (if-not item
                  ; no items entry? must be an item-id already
                  inst-id

                  ; get the :id from the item entry, if any
                  (get item :id))]
    (or (when item-id
          (src/find-item data-source item-id))
        item)))

; map of :inst-id -> inflated item in the active sheet's inventory,
; where each inflated item with an amount > 1 (or which :stacks?)
; includes the special key :wish/amount indicating that amount
(reg-sub
  :inventory-map
  :<- [:inventory]
  :<- [:items]
  :<- [:sheet-source]
  (fn [[raw-inventory items data-source]]
    (reduce-kv
      (fn [m inst-id amount]
        (let [inflated (inflate-item inst-id items data-source)
              show-amount? (or (> amount 1)
                               (:stacks? inflated))
              item (if show-amount?
                     (assoc inflated :wish/amount amount)
                     inflated)]
          (assoc m inst-id item)))
      {}
      raw-inventory)))

; sorted list of inflated inventory items
(reg-sub
  :inventory-sorted
  :<- [:inventory-map]
  (fn [inventory-map]
    (->> inventory-map
         vals
         (sort-by :name))))

; list of all known items for the current sheet
(reg-sub
  :all-items
  :<- [:sheet-source]
  (fn [source]
    (src/list-entities source :items)))


; ======= character builder-related ========================

(reg-sub
  :available-entities
  :<- [:sheet-source]
  (fn [source [_ entity-kind]]
    (src/list-entities source entity-kind)))


(reg-sub
  :options->
  :<- [:options]
  (fn [options [_ path]]
    (get-in options path)))

; ======= Save state =======================================

(reg-sub
  :save-state
  (fn [{::db/keys [pending-saves processing-saves save-errors]}]
    (cond
      ; if there are any processing, show :saving state
      (not (empty? processing-saves)) :saving

      ; nothing processing, but some pending
      (not (empty? pending-saves)) :pending

      ; idle, but something went wrong
      (not (empty? save-errors)) :error

      ; otherwise, idle
      :else :idle)))
