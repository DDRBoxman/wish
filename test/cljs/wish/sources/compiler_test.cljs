(ns wish.sources.compiler-test
  (:require [cljs.test :refer-macros [deftest testing is]]
            [cljs.nodejs :as node]
            [wish.sources.compiler :refer [compile-directives]]))

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
                     :hit-dice/d10)))))

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
