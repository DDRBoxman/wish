(ns ^{:author "Daniel Leong"
      :doc "Template function compilation"}
  wish.sources.compiler.fun
  (:require [clojure.string :as str]
            [clojure.analyzer.api :refer [no-warn]]
            [clojure.walk :refer [postwalk]]
            [cljs.js :refer [empty-state eval js-eval]])
  (:require-macros [wish.sources.compiler.fun :refer [expose-fn export-macro export-sym]]))

;;
;; Clojurescript eval

(def cached-eval-state (atom nil))

(defn ^:export ->number
  [to-coerce]
  (when to-coerce
    (cond
      (number? to-coerce) to-coerce
      (not= -1 (.indexOf to-coerce ".")) (js/parseFloat to-coerce)
      :else (js/parseInt to-coerce))))

(defn ^:export mathify
  [args]
  (map ->number args))

; NOTE anything exposed below also needs to get added to the :refer

(def exposed-fns
  (-> { ; these alias directly to JS functions
       'ceil 'js/Math.ceil
       'floor 'js/Math.floor }

      ;;
      ;; Expose!
      ;;

      (expose-fn + mathify)
      (expose-fn - mathify)
      (expose-fn / mathify)
      (expose-fn * mathify)
      (expose-fn <)
      (expose-fn >)
      (expose-fn <=)
      (expose-fn >=)
      (expose-fn =)
      (expose-fn not=)
      (expose-fn min)
      (expose-fn max)

      (expose-fn inc)
      (expose-fn dec)

      (expose-fn keyword)
      (expose-fn name)
      (expose-fn str)
      (expose-fn symbol)
      (expose-fn vector)

      (expose-fn concat)
      (expose-fn cons)
      (expose-fn contains?)
      (expose-fn count)
      (expose-fn identity)
      (expose-fn keys)
      (expose-fn vals)
      (expose-fn vec)

      (expose-fn get)
      (expose-fn get-in)

      (expose-fn filter)
      (expose-fn keep)
      (expose-fn map)
      (expose-fn mapcat)
      (expose-fn remove)

      (expose-fn partial)

      ; for debugging
      (expose-fn println)))

;;
;; Public API
;;

(defn wrap-unknown-fn
  [sym]
  (let [sym-string (str sym)]
    (fn [& args]
      (js/console.warn
        "UNKNOWN or UNEXPOSED function: "
        sym-string)
      nil)))

(defn ->special-form
  [sym]
  (get
    {'let 'let*
     'fn 'fn*}
    sym))

(defn exposed-fn?
  [sym]
  (or (contains? exposed-fns sym)
      (not (nil? (->special-form sym)))))

(defn ^:export ->fun
  "Given a raw symbol, return the exposed function"
  [sym]
  (or (get exposed-fns sym)
      (->special-form sym)
      sym))  ; just return unchanged

(when-not js/goog.DEBUG
  (export-macro ->)
  (export-macro ->>)
  (export-macro as->)
  (export-macro cond)
  (export-macro cond->)
  (export-macro cond->>)
  (export-macro if-let)
  (export-macro if-not)
  (export-macro if-some)
  (export-macro some->)
  (export-macro some->>)
  (export-macro when)
  (export-macro when-first)
  (export-macro when-let)
  (export-macro when-not)
  (export-macro when-some)

  ; this is required for (cond)
  (export-sym cljs.core/truth_)

  (export-sym cljs.core/Symbol)
  (export-sym cljs.core/Keyword)
  (export-sym cljs.core/PersistentArrayMap)
  (export-sym cljs.core/PersistentHashMap)
  (export-sym cljs.core/PersistentHashSet)
  (export-sym cljs.core/PersistentVector))

(defn- process-source
  [js-src]
  (-> js-src
      (str/replace
        ; we could also just replace the _invoke sequence, but
        ; that may or may not be safe....
        #"(new cljs\.core\.Keyword\(null,\"[^\"]+\",\"[^\"]+\",\([0-9-]+\)\))\.cljs\$core\$IFn\$_invoke\$arity\$([0-9]+)\("
        "$1.call(null,")))

(defn- eval-in
  [state form]
  (eval state
        form
        {:eval (fn [src]
                 (let [src (update src :source process-source)]
                   (try
                     (js-eval src)
                     (catch :default e
                       (js/console.warn (str "FAILED to js/eval:\n\n"
                                             (:source src)
                                             "\n\nOriginal error: " (.-stack e)))
                       (throw (js/Error.
                                (str "FAILED to js/eval:\n\n"
                                     (:source src)
                                     "\n\nOriginal error: " (.-stack e))))))))

         :context :expr
         ;; :source-map true
         :ns 'wish.sources.compiler.fun-eval}
        (fn [res]
          (if (contains? res :value) ; nil or false are fine
            (:value res)
            (when-not (= "Could not require wish.sources.compiler.fun"
                         (ex-message (:error res)))
              ;; (js/console.error (str "Error evaluating: " form))
              ;; (js/console.error (str res))
              (throw (js/Error. (str "Error evaluating: " form "\n" res) )))))))

(defn- clean-form
  [form]
  (postwalk ->fun form))

(defn- eval-form
  [form]
  (let [compiler-state
        (if-let [cached @cached-eval-state]
          cached
          (let [new-state (empty-state)]

            ; eval an ns so the imports are recognized
            (eval-in
              new-state
              '(ns wish.sources.compiler.fun-eval
                 ; NOTE: without this :require, in advanced mode the
                 ; (eval) complains that goog.provides doesn't exist,
                 ; or something like that.
                 (:require [wish.sources.compiler.fun])))

            ; eval a declare so our functions are also recognized
            (reset! cached-eval-state new-state)))

        ; replace fn refs with our exported versions
        cleaned-form (clean-form form)]

    (try
      (no-warn
        (eval-in compiler-state
                 cleaned-form))
      (catch :default e
        (js/console.error "Error compiling:" (str form),
                          "Cleaned: " (str cleaned-form),
                          e)
        (throw e)))))


(defn- ->constant-callable
  [form]
  (let [const-body (eval-form form)]
    (constantly const-body)))

(defn let-args
  "Given a vector of arg symbols, return an arg suitable for use
   with let*. Basically we're manually destructuring
    `[{:keys args} 'wish-fn-input]`,
   since the plumbing for doing that gets lost with :advanced
   optimization"
  [input]
  (reduce
    (fn [bindings sym]
      (conj bindings
            sym
            `(~(keyword sym) ~'wish-fn-input)))
    []
    input))

(defn fn-ify
  [form]
  (let [[_fn args & body] form]
    ; this is basically `(fn [:keys ~args]), but
    ; spelled out more explicitly so it will still
    ; eval under advanced compilation
    `(fn* [~'wish-fn-input]
        (let* ~(let-args args)
            ~@body))))

(defn ->callable
  "Convert a form that could either be a fn or a constant
   value into a callable that accepts some a map"
  [form]
  (when form
    (cond
      ; already callable
      (fn? form) form

      (seq? form) (let [[_fn args & body] form]
                    (if (and (= "fn" (str _fn))
                             (vector? args))
                      (let [fn-form (fn-ify form)]
                        (eval-form fn-form))

                      (->constant-callable form)))
      :else (->constant-callable form))))
