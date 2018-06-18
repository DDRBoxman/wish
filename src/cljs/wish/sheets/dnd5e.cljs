(ns ^{:author "Daniel Leong"
      :doc "DND 5e sheet"}
  wish.sheets.dnd5e
  (:require [clojure.string :as str]
            [cljs-css-modules.macro :refer-macros [defstyle]]
            [wish.util :refer [<sub click>evt invoke-callable]]
            [wish.sheets.dnd5e.subs :as dnd5e]
            [wish.sheets.dnd5e.events :as events]
            [wish.sheets.dnd5e.util :refer [ability->mod mod->str]]
            [wish.views.widgets :refer-macros [icon]]))


; ======= CSS ==============================================

(def color-proficient "#77E731")
(def color-expert "#E8E154")

; TODO we should maybe just provide global styles with the
; right fallbacks
(def flex {:display 'flex})
(def flex-vertical (merge
                     flex
                     {:flex-direction 'column}))
(def flex-center (merge
                   flex
                   {:align-items 'center}))
(def flex-grow {:flex-grow 1})

(defstyle styles
  [:.spell-slot-level flex-center
   [:.label flex-grow]]

  [:.spell-slots-container flex
   [:.slot {:width "24px"
            :height "24px"
            :border "1px solid #333"
            :margin "4px"}
    [:&.used {:cursor 'pointer}]]]

  [:.skill-col (merge
                 flex-vertical
                 flex-grow)
   [:.skill flex
    [:.label flex-grow]
    [:.score {:padding "0 4px"}]
    [:.proficiency
     {:color color-proficient
      :padding-right "12px"}
     [:&::before
      {:content "' '"}]  ; en-space unicode
     [:&.proficient::before
      {:content "'●'"}]
     [:&.expert::before
      {:color color-expert}]]]])

; ======= Utils ============================================

(defn hp
  []
  (let [[hp max-hp] (<sub [::dnd5e/hp]) ]
    [:div.hp "HP"
     [:div.now hp]
     [:div.max  (str "/" max-hp)]
     [:a
      {:href "#"
       :on-click (click>evt [::events/update-hp])}
      "Test"]]))

(defn header
  []
  (let [common (<sub [:sheet-meta])
        classes (<sub [:classes])]
    [:div.header "D&D"
     [:div.name (:name common)]
     [:div.classes (->> classes
                        (map (fn [c]
                               (str (-> c :name) " " (:level c))))
                        (str/join " / "))]
     [:div.race (:name (<sub [:race]))]

     [hp]]))

(defn section
  [title & content]
  (apply vector
         :div.section
         [:h1 title]
         content))


; ======= sections =========================================

(def labeled-abilities
  [[:str "Strength"]
   [:dex "Dexterity"]
   [:con "Constitution"]
   [:int "Intelligence"]
   [:wis "Wisdom"]
   [:cha "Charisma"]])

(defn abilities-section
  []
  (let [abilities (<sub [::dnd5e/abilities])]
    [:table.abilities
     [:tbody
      (for [[id label] labeled-abilities]
        (let [score (get abilities id)]
          ^{:key id}
          [:tr
           [:td score]
           [:td label]
           [:td "mod"]
           [:td (mod->str
                  (ability->mod score))]
           ; TODO saving throws:
           [:td "save"]
           [:td (mod->str
                  (ability->mod score))]]))]]))


; ======= Skills ===========================================

(def ^:private skills-table
  [[[:dex :acrobatics "Acrobatics"]
    [:wis :animal-handling "Animal Handling"]
    [:int :arcana "Arcana"]
    [:str :athletics "Athletics"]
    [:cha :deception "Deception"]
    [:int :history "History"]
    [:wis :insight "Insight"]
    [:cha :intimidation "Intimidation"]
    [:int :investigation "Investigation"]]
   [[:wis :medicine "Medicine"]
    [:int :nature "Nature"]
    [:wis :perception "Perception"]
    [:cha :performance "Performance"]
    [:cha :persuasion "Persuasion"]
    [:int :religion "Religion"]
    [:dex :sleight-of-hand "Sleight of Hand"]
    [:dex :stealth "Stealth"]
    [:wis :survival "Survival"]]])

(defn skill-box
  [ability label total-modifier expert? proficient?]
  [:div.skill
   [:div.base-ability
    (str "(" (name ability) ")")]
   [:div.label label]
   [:div.score
    (mod->str
      total-modifier)]
   [:div.proficiency
    {:class (str (when (or expert? proficient?)
                   "proficient ")
                 (when expert?
                   "expert"))}]] )

(defn skills-section []
  (let [abilities (<sub [::dnd5e/abilities])
        expertise (<sub [::dnd5e/skill-expertise])
        proficiencies (<sub [::dnd5e/skill-proficiencies])
        prof-bonus (<sub [::dnd5e/proficiency-bonus])]
    (vec (cons
           :div.sections
           (map
             (fn [col]
               [:div {:class (:skill-col styles)}
                (for [[ability skill-id label] col]
                  (let [expert? (contains? expertise skill-id)
                        proficient? (contains? proficiencies skill-id)
                        total-modifier (+ (ability->mod (get abilities ability))
                                          (cond
                                            expert? (* 2 prof-bonus)
                                            proficient?  prof-bonus))]
                    ^{:key skill-id}
                    [skill-box ability label total-modifier expert? proficient?]))])
             skills-table)))))


; ======= Combat ===========================================

(defn combat-section []
  [:div

   (when-let [s (<sub [::dnd5e/unarmed-strike])]
     [:div.unarmed-strike
      [:div.attack
       [:div.name "Unarmed Strike"]
       [:div.to-hit
        (let [{:keys [to-hit]} s]
          (mod->str to-hit))]
       [:div.dmg
        (:dmg s)]]])

   (when-let [spell-attacks (seq (<sub [::dnd5e/spell-attacks]))]
     [:div.spell-attacks
      [:h4 "Spell Attacks"]
      (for [s spell-attacks]
        ^{:key (:id s)}
        [:div.attack.spell-attack
         [:div.name (:name s)]
         [:div.dice (:base-dice s) ]
         [:div.to-hit
          (mod->str (:to-hit s))]])])])


; ======= Features =========================================

; TODO these should probably be subscriptions
(defn- features-for
  [sub-vector]
  (->> (<sub sub-vector)
       (mapcat (comp vals :features))
       (remove :implicit?)
       seq))

(defn feature
  [f]
  [:div.feature
   [:div.name (:name f)]
   [:div.desc (:desc f)]])

(defn features-section []
  (vec
    (cons
      :div.features
      [(when-let [fs (features-for [:classes])]
         [:div.features-category
          [:h3 "Class features"]
          (for [f fs]
            ^{:key (:id f)}
            [feature f])])
       (when-let [fs (features-for [:races])]
         [:div.features-category
          [:h3 "Racial Traits"]
          (for [f fs]
            ^{:key (:id f)}
            [feature f])])

       ; TODO proficiencies?
       ; TODO feats?
       ])))


; ======= Limited-use ======================================

(def trigger-labels
  {:short-rest "Short Rest"
   :long-rest "Long Rest"})

(defn describe-uses
  [uses trigger]
  (if (= 1 uses)
    (str "Once per " (trigger-labels trigger))
    (str uses " uses / " (trigger-labels trigger))))

(defn limited-use-section [items]
  (let [items (<sub [::dnd5e/limited-uses])
        used (<sub [:limited-used])]
    [:div
     [:div.rests
      [:div.short
       {:on-click (click>evt [:trigger-limited-use-restore :short-rest])}
       "Short Rest"]
      [:div.long
       {:on-click (click>evt [:trigger-limited-use-restore
                              [:short-rest :long-rest]])}
       "Long Rest"]]

     (for [item items]
       (let [uses (invoke-callable item :uses)]
         ^{:key (:id item)}
         [:div.limited-use
          [:div.name (:name item)]
          [:span.recovery
           (describe-uses uses (:restore-trigger item))]]))
     ]))


; ======= Spells ===========================================

(defn spell-block
  [s]
  (println "TODO more rendering for " s)
  [:div.spell
   (:name s)])

(defn spell-slot-use-block
  [level total used]
  [:div.spell-slot-use
   {:class (:spell-slots-container styles)
    :on-click (click>evt [::events/use-spell-slot level total])}
   (for [i (range total)]
     (let [used? (< i used)]
       ^{:key (str level "/" i)}
       [:div.slot
        {:class (when used?
                  "used")
         :on-click (when used?
                     (click>evt [::events/restore-spell-slot level total]
                                :propagate? false))}
        (when used?
          (icon :close))]))])

(defn spells-section []
  (let [spells (<sub [::dnd5e/class-spells])
        slots (<sub [::dnd5e/spell-slots])
        slots-used (<sub [::dnd5e/spell-slots-used])]
    [:div
     [:div.spell-slots
      [:h4 "Spell Slots"]
      (for [[level total] slots]
        ^{:key (str "slots/" )}
        [:div
         {:class (:spell-slot-level styles)}
         [:div.label
          (str "Level " level)]
         [spell-slot-use-block
          level total (get slots-used level)]])]

     [:div.spells
      [:h4 "Available spells"]
      ; TODO toggle only showing known/prepared
      (for [s spells]
        ^{:key (:id s)}
        [spell-block s])]]))


; ======= Public interface =================================

(defn sheet []
  [:div
   [header]
   [:div.sections
    [section "Abilities"
     [abilities-section]]
    [section "Skills"
     [skills-section]]
    [section "Combat"
     [combat-section]]

    [section "Features"
     [features-section]]

    [section "Limited-use"
     [limited-use-section]]
    [section "Spells"
     [spells-section]]]])
