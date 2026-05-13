(ns drawl.walker
  "Forms -> IR. Multimethod dispatch on head symbol (per SPEC §4.3).

  Header parsing rule (SPEC §2.2): up to two positional strings (title,
  description) and keyword/value attribute pairs may interleave freely
  in the form's header. The first list form encountered starts the
  children. Single pass, no backtracking. Last-write wins on duplicate
  attribute keys (so macro-injected attrs precede user opts and user opts
  override)."
  (:require [drawl.macros :as macros]))

(defn split-header
  "Peel the header of an element form. Strings (up to 2) become positional
  title/description in the order encountered. Keyword/value pairs accumulate
  into an attrs map (last-write wins). Anything else terminates the header
  and is returned as children.

  Returns [[title description] attrs children]."
  [forms]
  (loop [strs [] attrs {} fs forms]
    (cond
      (and (< (count strs) 2) (string? (first fs)))
      (recur (conj strs (first fs)) attrs (rest fs))

      (and (keyword? (first fs)) (>= (count fs) 2))
      (recur strs (assoc attrs (first fs) (second fs)) (drop 2 fs))

      :else
      [(into strs (repeat (- 2 (count strs)) nil)) attrs fs])))

(defmulti walk-form
  "Dispatch on form head symbol. Returns an IR fragment."
  (fn [form _ctx]
    (cond
      (not (seq? form))
      (throw (ex-info (str "Expected a list form, got: " (pr-str form))
                      {:type :walk-error :form form}))
      :else (first form))))

(defmethod walk-form :default [form ctx]
  (if-let [template (get-in ctx [:macros (first form)])]
    (walk-form (macros/expand-call template form) ctx)
    (throw (ex-info (str "Unknown form head: " (pr-str (first form)))
                    {:type :walk-error :form form}))))

(defn- walk-children
  "Walk each child form and split the results into [elements edges].
  Used by every element-bearing head (diagram + person/system/container/component)."
  [children ctx]
  (let [walked (mapv #(walk-form % ctx) children)
        {elements true edges false}
        (group-by #(not= :edge (:kind %)) walked)]
    [(vec elements) (vec edges)]))

(defn- walk-element
  "Shared shape for person/system/container/component forms.
  Layout: (head ID header... children...) where header is any interleaving
  of positional title/description strings (up to 2) and :attr value pairs."
  [kind form ctx]
  (let [[_ id & rest] form
        _ (when-not (symbol? id)
            (throw (ex-info (str (name kind) " requires a symbol id, got: "
                                 (pr-str id))
                            {:type :walk-error :form form})))
        [[title description] attrs children] (split-header rest)
        child-ctx                            (assoc ctx :current-element id)
        [child-els edges]                    (walk-children children child-ctx)]
    {:kind        kind
     :id          id
     :title       title
     :description description
     :attrs       attrs
     :children    child-els
     :edges       edges}))

(defmethod walk-form 'diagram [form ctx]
  (let [[_ & rest]                      form
        [[title _] attrs children]      (split-header rest)
        [elements rels]                 (walk-children children ctx)]
    {:title         title
     :attrs         attrs
     :elements      elements
     :relationships rels}))

(defmethod walk-form 'person    [f c] (walk-element :person    f c))
(defmethod walk-form 'system    [f c] (walk-element :system    f c))
(defmethod walk-form 'container [f c] (walk-element :container f c))
(defmethod walk-form 'component [f c] (walk-element :component f c))

(defn- walk-edge [bidirectional? form ctx]
  (let [args (rest form)
        [from to rest-args]
        (cond
          (and (symbol? (first args)) (symbol? (second args)))
          [(first args) (second args) (drop 2 args)]

          (and (symbol? (first args)) (:current-element ctx))
          [(:current-element ctx) (first args) (rest args)]

          :else
          (throw (ex-info "edge requires two endpoints at top level"
                          {:type :walk-error :form form})))
        [[desc _] own-attrs leftover] (split-header rest-args)]
    (when (seq leftover)
      (throw (ex-info (str "edge does not accept children, got: "
                           (pr-str leftover))
                      {:type :walk-error :form form})))
    {:kind           :edge
     :from           from
     :to             to
     :bidirectional? bidirectional?
     :description    desc
     :attrs          (merge (:wrap-attrs ctx) own-attrs)}))

(defmethod walk-form '->  [form ctx] (walk-edge false form ctx))
(defmethod walk-form '<-> [form ctx] (walk-edge true  form ctx))
