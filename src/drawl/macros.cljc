(ns drawl.macros
  "Per-compile macro registry. Built-in shorthand and user-defined
  `(defmacro NAME [params...] body)` forms share one mechanism: the walker
  consults `(:macros ctx)` for any unknown head, binds args to params, and
  substitutes them into the body before re-dispatching.

  Substitution is plain symbol replacement. A param after `&` captures the
  remaining call-site args; when that symbol appears as a leaf inside any
  list in the body, the captured seq is spliced in. No `eval`, no nested
  unquote markers, no `~`/`~@` reader gymnastics — just lists with
  symbol-leaf substitution.

  This namespace owns macro data + expansion only; the walker depends on it
  to expand calls in its `:default` dispatch.")

(defn- parse-params
  "Vector like '[name & opts] -> {:fixed ['name] :rest 'opts}.
  Throws if `&` appears without a following symbol or if more than one
  symbol follows it."
  [params]
  (let [[fixed [_amp & tail]] (split-with #(not= '& %) params)]
    (cond
      (and (empty? tail) (some #(= '& %) params))
      (throw (ex-info "macro params: `&` must be followed by a symbol"
                      {:type :walk-error :params params}))

      (> (count tail) 1)
      (throw (ex-info "macro params: only one symbol allowed after `&`"
                      {:type :walk-error :params params}))

      :else
      {:fixed (vec fixed) :rest (first tail)})))

(defn parse-defmacro
  "Validate a (defmacro NAME [params...] body) form and return [head template]."
  [form]
  (let [[_ head params body & extra] form]
    (when-not (symbol? head)
      (throw (ex-info (str "defmacro requires a symbol name, got: " (pr-str head))
                      {:type :walk-error :form form})))
    (when-not (vector? params)
      (throw (ex-info (str "defmacro requires a params vector, got: " (pr-str params))
                      {:type :walk-error :form form})))
    (when (seq extra)
      (throw (ex-info "defmacro accepts exactly one body form"
                      {:type :walk-error :form form})))
    [head {:params (parse-params params) :body body}]))

(defn- warn! [msg]
  #?(:clj  (binding [*out* *err*] (println "WARN" msg))
     :cljs (js/console.warn (str "WARN " msg))))

(defn register
  "Add a template to the registry. Warns on override (Lisp convention: last
  definition wins)."
  [registry head template]
  (when (contains? registry head)
    (warn! (str "redefining macro `" head "` (overrides previous definition)")))
  (assoc registry head template))

(defn- bind-args [{:keys [fixed rest]} args]
  (let [n (count fixed)]
    (cond-> (zipmap fixed (take n args))
      rest (assoc rest (vec (drop n args))))))

(defn- substitute
  "Walk `form`, replacing param symbols with their bound values. The
  `rest-sym`, when encountered as a leaf inside a list/vector, splices the
  captured seq into the surrounding collection."
  [bindings rest-sym form]
  (letfn [(walk-list [items]
            (reduce
             (fn [acc item]
               (if (and rest-sym (= item rest-sym))
                 (into acc (get bindings rest-sym))
                 (conj acc (sub item))))
             []
             items))
          (sub [f]
            (cond
              (and (symbol? f) (contains? bindings f)) (get bindings f)
              (seq? f)                                 (apply list (walk-list f))
              (vector? f)                              (walk-list f)
              (map? f)                                 (into {} (map (fn [[k v]] [(sub k) (sub v)])) f)
              :else                                    f))]
    (sub form)))

(defn expand-call
  "Apply a registered template to a call form. Returns the expanded form."
  [{:keys [params body]} call-form]
  (substitute (bind-args params (rest call-form)) (:rest params) body))

(def builtins
  "Templates seeded into the per-compile registry. Users may override any
  of these with `(defmacro NAME ...)` at the top level of their source."
  {'webapp      {:params {:fixed ['name] :rest 'opts}
                 :body   '(container name :tech "Web" opts)}
   'postgres-db {:params {:fixed ['name] :rest 'opts}
                 :body   '(container name :role :database :tech "Postgres" opts)}
   'redis-cache {:params {:fixed ['name] :rest 'opts}
                 :body   '(container name :role :cache :tech "Redis" opts)}
   'rest-api    {:params {:fixed ['name] :rest 'opts}
                 :body   '(container name :tech "REST API" opts)}})
