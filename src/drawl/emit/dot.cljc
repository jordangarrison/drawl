(ns drawl.emit.dot
  "IR -> graphviz dot (SPEC §6.1).

  Honors:
    elements: :role :database (cylinder), :external (dashed), :tech (sub-label)
    edges:    :style passthrough, :tech (label suffix), :bidirectional? (dir=both)

  Unknown attributes are silently passed over (per SPEC §2.4)."
  (:require [clojure.string :as str]
            [drawl.ir :as ir]))

(defn- esc
  "Escape for a dot quoted string. Per graphviz, only `\"` needs escaping;
  backslash is literal so escape sequences like `\\n` pass through unchanged."
  [s]
  (str/replace (or s "") "\"" "\\\""))

(defn- attr-pair [[k v]]
  (str (name k) "=\"" (esc (str v)) "\""))

(defn- attr-list
  "Serialize attr pairs in insertion order, dropping nil/empty values."
  [pairs]
  (->> pairs
       (remove (fn [[_ v]] (or (nil? v) (= "" v))))
       (map attr-pair)
       (str/join " ")))

(defn- shape-for
  "Default shape for a leaf element, before role/external overrides."
  [{:keys [kind]}]
  (case kind
    :person    {:shape "oval"}
    :system    {:shape "box" :style "rounded"}
    :container {:shape "box" :style "rounded,filled"}
    :component {:shape "box"}))

(defn- with-style
  "Append `extra` to an existing comma-separated dot style attr."
  [shape-attrs extra]
  (let [cur (:style shape-attrs)]
    (assoc shape-attrs :style (if (str/blank? cur) extra (str cur "," extra)))))

(defn- apply-role [shape-attrs role]
  (case role
    :database (assoc shape-attrs :shape "cylinder" :style nil)
    shape-attrs))

(defn- node-shape [{:keys [attrs] :as el}]
  (cond-> (shape-for el)
    (:role attrs)     (apply-role (:role attrs))
    (:external attrs) (with-style "dashed")))

(defn- with-tech
  "Append a `\\n[tech]` suffix to a label string. Graphviz interprets `\\n`
  inside a quoted label as a newline."
  [base tech]
  (if tech (str (or base "") "\\n[" tech "]") base))

(defn- node-label [{:keys [id title attrs]}]
  (with-tech (or title (name id)) (:tech attrs)))

(defn- ordered-pairs
  "Build an ordered seq of [k v] pairs, dropping nil/empty values."
  [& kvs]
  (->> (partition 2 kvs)
       (remove (fn [[_ v]] (or (nil? v) (= "" v))))))

(defn- node-line [{:keys [id] :as el}]
  (let [shape (node-shape el)]
    (str "  \"" (esc (name id)) "\" ["
         (attr-list (ordered-pairs
                     :shape (:shape shape)
                     :style (:style shape)
                     :label (node-label el)))
         "];")))

(defn- edge-label [{:keys [description attrs]}]
  (let [tech (:tech attrs)]
    (cond
      description (with-tech description tech)
      tech        (str "[" tech "]"))))

(defn- edge-style [attrs]
  (when-let [s (:style attrs)] (name s)))

(defn- edge-line [{:keys [from to bidirectional? attrs] :as e}]
  (let [pairs (ordered-pairs
               :label (edge-label e)
               :style (edge-style attrs)
               :dir   (when bidirectional? "both"))]
    (str "  \"" (esc (name from)) "\" -> \"" (esc (name to)) "\""
         (when (seq pairs) (str " [" (attr-list pairs) "]"))
         ";")))

(defn- element-lines
  "Leaves render as a single node line; elements with children render as a
  graphviz cluster subgraph carrying the element's title."
  [{:keys [id children] :as el}]
  (if (seq children)
    (let [external? (:external (:attrs el))]
      (concat
       [(str "  subgraph \"cluster_" (esc (name id)) "\" {")
        (str "    " (attr-list (ordered-pairs
                                :label (node-label el)
                                :style (if external? "rounded,dashed" "rounded")))
             ";")]
       (mapcat element-lines children)
       ["  }"]))
    [(node-line el)]))

(defn emit
  "IR -> dot string."
  [{:keys [title elements] :as ir}]
  (str/join
   "\n"
   (concat
    ["digraph G {"
     (str "  label=\"" (esc (or title "")) "\";")
     "  rankdir=LR;"]
    (mapcat element-lines elements)
    (map edge-line (ir/collect-edges ir))
    ["}"])))
