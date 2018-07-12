(ns ^{:author "Daniel Leong"
      :doc "Shared widgets for dnd5e sheet "}
  wish.sheets.dnd5e.widgets
  (:require [clojure.string :as str]
            [wish.util :refer [<sub click>evt invoke-callable]]
            [wish.sheets.dnd5e.style :refer [styles]]
            [wish.views.widgets :as widgets
             :refer-macros [icon]
             :refer [formatted-text]]))

(defn- stringify-components
  [{components :comp}]
  (letfn [(vs-parts [k]
            (case k
              :v "V"
              :s "S"
              :vs "V, S"))
          (m-part [p]
            (str "M (" p ")"))]
    (cond
      (keyword? components)
      (vs-parts components)

      ; are any material only?
      (string? components)
      (m-part components)

      (= 1 (count components))
      (m-part (first components))

      :else
      (str (vs-parts (first components))
           ", "
           (m-part (second components))))))

(defn- stringify-dam-type
  [t]
  ; TODO table?
  (when t
    (-> t
        name
        str/capitalize
        (str/replace "-" " "))))

(defn spell-card
  "Spell info card widget"
  [s]
  [:div {:class (:spell-card styles)}
   [:table.info
    [:tbody
     [:tr
      [:th.header "Casting Time"]
      [:td (:time s)]]
     [:tr
      [:th.header "Range"]
      [:td (:range s)]]
     [:tr
      [:th.header "Components"]
      [:td (stringify-components s)]]
     [:tr
      [:th.header "Duration"]
      [:td (:duration s)]]

     (when-let [dice (:dice s)]
       (let [{:keys [dam-type]} s]
         [:tr
          [:th.header
           (if dam-type
             "Damage"
             "Healing")]

          [:td
           [:u
            (invoke-callable
              (update s :spell-mod #(or % "(spell mod)"))
              :dice)]

           (if-let [dam-type (stringify-dam-type dam-type)]
             (str " " dam-type)
             " HP")]]))

     (when-let [save (:save s)]
       [:tr
        [:th.header
         "Saving Throw"]
        [:td
         (case save
           :str "Strength"
           :dex "Dexterity"
           :con "Constitution"
           :int "Intelligence"
           :wis "Wisdom"
           :cha "Charisma")]])
     ]]

   [formatted-text :div.desc (:desc s)]])