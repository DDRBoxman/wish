(ns wish.sources.compiler-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [wish.sources.compiler :refer [apply-levels apply-options compile-directives inflate]]
            [wish.sources.core :as src :refer [->DataSource]]))

(def character-state
  {:level 42})

(deftest provide-feature-test
  (testing "Simple provide"
    (let [s (compile-directives
              [[:!provide-feature
                {:id :hit-dice/d10
                 :name "Hit Dice: D10"}]])]
      (is (contains? s :features))
      (is (contains? (:features s)
                     :hit-dice/d10))))

  (testing "Apply directives of provided feature"
    ; for example, when the options are applied for
    ; a :background feature, that option's features get installed,
    ; but if they provide a feature (for example, proficiency with
    ; a skill) that feature's directives need to be applied (in this
    ; case, providing an attr.)
    (let [s (compile-directives
              [[:!provide-feature
                {:id :proficiency/stealth
                 :name "Stealth"
                 :! [[:!provide-attr :proficiency/stealth true]]}]

               [:!provide-feature
                {:id :background
                 :max-options 1}]

               [:!provide-options
                :background

                {:id :background/rogue
                 :primary-only? true
                 :name "Fake Rogue background"

                 :! [[:!provide-feature
                      :proficiency/stealth]]}]

               [:!declare-class
                {:id :fake-rogue
                 :features [:background]}]])
          ds (->DataSource :ds s)
          class-inst (src/find-class ds :fake-rogue)
          ch (inflate (assoc class-inst :primary? true)
                      ds
                      {:background [:background/rogue]})
          attrs (:attrs ch)
          ]
      (is (identity class-inst))
      (is (= {:proficiency/stealth true} attrs)))))

(deftest provide-attr-test
  (testing "Provide in path"
    (let [s (compile-directives
              [[:!provide-attr
                [:5e/ac :monk/unarmored-defense]
                :value]])]
      (is (= {:attrs {:5e/ac {:monk/unarmored-defense :value}}}
             (select-keys s [:attrs]))))))

(deftest class-test
  (testing "Inflate features by id"
    (let [s (compile-directives
              [[:!declare-class
                {:id :classy
                 :features [:hit-dice/d10]}]
               [:!provide-feature
                {:id :hit-dice/d10
                 :name "Hit Dice: D10"}]])]
      (is (contains? s :classes))
      (is (contains? (:classes s)
                     :classy))
      (is (= :hit-dice/d10
             (-> s :classes :classy :features :hit-dice/d10 :id)))))
  (testing "Apply feature directives when installed"
    (let [s (compile-directives
              [[:!declare-class
                {:id :classy
                 :features [:hit-dice/d10]}]
               [:!provide-feature
                {:id :hit-dice/d10
                 :! [[:!provide-attr
                      :5e/hit-dice 10]]}]])]
      (is (contains? s :classes))
      (is (contains? (:classes s)
                     :classy))
      (is (= 10
             (-> s :classes :classy :attrs :5e/hit-dice))))))

(deftest options-test
  (testing "Options provided before the feature"
    (let [s (compile-directives
              [[:!provide-options
                :feature
                {:id :feature/opt1}
                {:id :feature/opt2}]

               [:!provide-feature
                {:id :feature
                 :max-options 1}]])
          f (get-in s [:features :feature])]
      (is (= [{:id :feature/opt1}
              {:id :feature/opt2}]
             (:values f))))))

(deftest apply-options-test
  (testing "Apply options to class instance"
    (let [ds (->DataSource
               :source
               (compile-directives
                 [[:!provide-feature
                   {:id :proficiency/stealth
                    :name "Stealth"
                    :! [[:!provide-attr :proficiency/stealth true]]}]
                  [:!declare-class
                   {:id :cleric
                    :features
                    [{:id :rogue/skill-proficiencies
                      :name "Proficiencies"
                      :max-options 2
                      :values [:proficiency/stealth]}
                     ]}]]))
          opts-map {:rogue/skill-proficiencies [:proficiency/stealth]}
          applied (apply-options
                    ; NOTE: they must *have* the feature for the option
                    ; to get applied
                    {:features {:rogue/skill-proficiencies true}}
                    ds opts-map)]
      (is (= {:attrs {:proficiency/stealth true}}
             (select-keys applied [:attrs])))))
  (testing "Only apply :primary-only? options to :primary? class instance"
    (let [ds (->DataSource
               :source
               (compile-directives
                 [[:!provide-feature
                   {:id :proficiency/stealth
                    :name "Stealth"
                    :primary-only? true
                    :! [[:!provide-attr :proficiency/stealth true]]}]
                  [:!declare-class
                   {:id :cleric
                    :features
                    [{:id :rogue/skill-proficiencies
                      :name "Proficiencies"
                      :max-options 2
                      :values [:proficiency/stealth]}
                     ]}]]))
          opts-map {:rogue/skill-proficiencies [:proficiency/stealth]}
          applied (apply-options
                    ; NOTE: they must *have* the feature for the option
                    ; to get applied
                    {:features {:rogue/skill-proficiencies true}}
                    ds opts-map)]
      (is (= {}
             (select-keys applied [:attrs]))))))

(deftest apply-levels-test
  (let [ds (->DataSource
             :source
             (compile-directives
               [[:!provide-feature
                 {:id :proficiency/stealth
                  :name "Stealth"
                  :! [[:!provide-attr :proficiency/stealth true]]}
                 {:id :proficiency/insight
                  :name "Insight"
                  :! [[:!provide-attr :proficiency/insight true]]}]]))]

    (testing "Combine levels"
      (let [class-def {:id :cleric
                       :&levels {2 {:+features
                                    [:proficiency/stealth]}
                                 3 {:+features
                                    [:proficiency/insight]}}}
            entity-base (assoc class-def :level 1)
            level-2 (assoc entity-base :level 2)
            level-3 (assoc entity-base :level 3) ]
        (is (nil?
              (:attrs (apply-levels entity-base ds))))

        (is (= {:proficiency/stealth true}
               (:attrs (apply-levels level-2 ds))))
        (is (= {:proficiency/stealth true
                :proficiency/insight true}
               (:attrs (apply-levels level-3 ds))))))

    (testing "Replace levels"
      ; This is a contrived example, but...
      (let [class-def {:id :cleric
                       :levels {2 {:+features
                                   [:proficiency/stealth]}
                                3 {:+features
                                   [:proficiency/insight]}}}
            entity-base (assoc class-def :level 1)
            level-2 (assoc entity-base :level 2)
            level-3 (assoc entity-base :level 3) ]
        (is (nil?
              (:attrs (apply-levels entity-base ds))))

        (is (= {:proficiency/stealth true}
               (:attrs (apply-levels level-2 ds))))
        (is (= {:proficiency/insight true}
               (:attrs (apply-levels level-3 ds))))))))
