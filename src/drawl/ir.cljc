(ns drawl.ir
  "IR transforms: level inference, at-level filtering, validation."
  (:require [clojure.string :as str]))

(defn- collect-kinds
  "Walk an element tree, returning a set of all element :kinds present."
  [el]
  (into #{(:kind el)}
        (mapcat collect-kinds (:children el))))

(defn- diagram-kinds [ir]
  (into #{} (mapcat collect-kinds (:elements ir))))

(defn infer-level
  "Deepest :kind wins (SPEC §3.2)."
  [ir]
  (let [kinds (diagram-kinds ir)]
    (cond
      (kinds :component) :component
      (kinds :container) :container
      :else              :context)))

(defn with-level
  "Attach :level to the IR."
  [ir]
  (assoc ir :level (infer-level ir)))

(defn- collect-ids
  "Walk an element tree, collecting [id-symbol] pairs. Throws on duplicates."
  [el seen]
  (let [seen' (if-let [id (:id el)]
                (if (contains? seen id)
                  (throw (ex-info (str "Duplicate id: " id)
                                  {:type :walk-error :id id}))
                  (conj seen id))
                seen)]
    (reduce (fn [s c] (collect-ids c s)) seen' (:children el))))

(defn collect-all-ids
  "Set of all ids defined anywhere in the IR. Throws on duplicates."
  [ir]
  (reduce (fn [s e] (collect-ids e s)) #{} (:elements ir)))

(defn collect-edges
  "All edges in the IR: top-level :relationships plus per-element :edges
  recursing through :children. Used by validators and emitters."
  [ir]
  (let [walk-el (fn walk-el [el]
                  (concat (:edges el) (mapcat walk-el (:children el))))]
    (concat (:relationships ir) (mapcat walk-el (:elements ir)))))

(defn validate-refs
  "Throws on duplicate ids or unresolved edge endpoints. Returns IR on success."
  [ir]
  (let [ids   (collect-all-ids ir)
        edges (collect-edges ir)]
    (doseq [{:keys [from to]} edges]
      (when-not (contains? ids from)
        (throw (ex-info (str "Unresolved edge endpoint: " from)
                        {:type :walk-error :id from})))
      (when-not (contains? ids to)
        (throw (ex-info (str "Unresolved edge endpoint: " to)
                        {:type :walk-error :id to}))))
    ir))

(def ^:private valid-parent-kinds
  "Per SPEC §3.3: components only inside containers; containers inside systems
  or other containers (sub-containers); systems inside diagrams or other
  systems (system-of-systems view). :person is unrestricted."
  {:system    #{nil :system}
   :container #{:system :container}
   :component #{:container}})

(defn- parent-label [k] (if k (name k) "diagram"))

(defn validate-nesting
  "Throws when an element is nested under an illegal parent kind. Returns IR."
  [ir]
  (letfn [(check [el parent-kind]
            (when-let [allowed (valid-parent-kinds (:kind el))]
              (when-not (contains? allowed parent-kind)
                (throw (ex-info
                         (str (name (:kind el)) " " (:id el)
                              " must be inside "
                              (str/join " or " (map parent-label allowed))
                              ", got " (parent-label parent-kind))
                         {:type :walk-error :id (:id el) :kind (:kind el)}))))
            (doseq [c (:children el)] (check c (:kind el))))]
    (doseq [el (:elements ir)] (check el nil))
    ir))

(defn validate
  "Run all IR validations. Returns IR on success."
  [ir]
  (-> ir validate-refs validate-nesting))

(def ^:private level->keep-kinds
  {:context   #{:person :system}
   :container #{:person :system :container}
   :component #{:person :system :container :component}})

(defn- filter-kinds
  "First pass: drop elements (and their subtrees) whose :kind isn't kept."
  [keep-kinds el]
  (when (keep-kinds (:kind el))
    (assoc el :children (vec (keep #(filter-kinds keep-kinds %) (:children el))))))

(defn- all-ids [elements]
  (set (mapcat (fn collect [el] (cons (:id el) (mapcat collect (:children el))))
               elements)))

(defn- filter-dangling-edges [survive el]
  (-> el
      (update :edges    (fn [es] (filterv #(and (survive (:from %))
                                                 (survive (:to %))) es)))
      (update :children (fn [cs] (mapv #(filter-dangling-edges survive %) cs)))))

(defn at-level
  "Filter the IR to a target level by stripping deeper element kinds.
  Per SPEC §3.2: components stripped at :container level, containers at :context.
  Edges whose endpoints are filtered out are dropped."
  [level ir]
  (let [keep-set (level->keep-kinds level)]
    (when-not keep-set
      (throw (ex-info (str "Unknown level: " level)
                      {:type :walk-error :level level})))
    (let [els     (vec (keep #(filter-kinds keep-set %) (:elements ir)))
          survive (all-ids els)
          els'    (mapv #(filter-dangling-edges survive %) els)
          rels    (filterv #(and (survive (:from %)) (survive (:to %)))
                           (:relationships ir))]
      (assoc ir :level level :elements els' :relationships rels))))
