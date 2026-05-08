(ns drawl.walker
  "Forms -> IR. Multimethod dispatch on head symbol (per SPEC §4.3).

  Attribute parsing rule (SPEC §2.2): positional args first, then keyword/value
  pairs greedily until first non-keyword form, then everything else is children.
  Single pass, no backtracking.")

(defn split-attrs
  "Greedily peel keyword/value pairs from the head of `forms`.
  Returns [attrs-map remaining-forms]."
  [forms]
  (loop [attrs {} fs forms]
    (if (and (seq fs) (keyword? (first fs)) (>= (count fs) 2))
      (recur (assoc attrs (first fs) (second fs)) (drop 2 fs))
      [attrs fs])))

(defn- take-string [forms]
  (if (string? (first forms))
    [(first forms) (rest forms)]
    [nil forms]))

(defmulti walk-form
  "Dispatch on form head symbol. Returns an IR fragment."
  (fn [form _ctx]
    (cond
      (not (seq? form))
      (throw (ex-info (str "Expected a list form, got: " (pr-str form))
                      {:type :walk-error :form form}))
      :else (first form))))

(defmethod walk-form :default [form _ctx]
  (throw (ex-info (str "Unknown form head: " (pr-str (first form)))
                  {:type :walk-error :form form})))

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
  Layout: (head ID title? description? :attrs... children...)"
  [kind form ctx]
  (let [[_ id & rest] form
        _ (when-not (symbol? id)
            (throw (ex-info (str (name kind) " requires a symbol id, got: "
                                 (pr-str id))
                            {:type :walk-error :form form})))
        [title rest]       (take-string rest)
        [description rest] (take-string rest)
        [attrs children]   (split-attrs rest)
        [child-els edges]  (walk-children children ctx)]
    {:kind        kind
     :id          id
     :title       title
     :description description
     :attrs       attrs
     :children    child-els
     :edges       edges}))

(defmethod walk-form 'diagram [form ctx]
  (let [[_ & rest]        form
        [title rest]      (take-string rest)
        [attrs children]  (split-attrs rest)
        [elements rels]   (walk-children children ctx)]
    {:title         title
     :attrs         attrs
     :elements      elements
     :relationships rels}))

(defmethod walk-form 'person    [f c] (walk-element :person    f c))
(defmethod walk-form 'system    [f c] (walk-element :system    f c))
(defmethod walk-form 'container [f c] (walk-element :container f c))
(defmethod walk-form 'component [f c] (walk-element :component f c))

(defmethod walk-form '-> [form _ctx]
  (let [[_ from to & rest] form
        [desc rest]        (take-string rest)
        [attrs _]          (split-attrs rest)]
    {:kind           :edge
     :from           from
     :to             to
     :bidirectional? false
     :description    desc
     :attrs          attrs}))

(defmethod walk-form '<-> [form _ctx]
  (let [[_ a b & rest] form
        [desc rest]    (take-string rest)
        [attrs _]      (split-attrs rest)]
    {:kind           :edge
     :from           a
     :to             b
     :bidirectional? true
     :description    desc
     :attrs          attrs}))
