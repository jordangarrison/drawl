(ns drawl.emit.dot
  "IR -> graphviz dot (SPEC §6.1).

  Slices covered: diagram + system + person + plain ->/<-> edges.
  Future slices add: container/component clusters, role/external/tech
  shape overrides, bidirectional dir=both."
  (:require [clojure.string :as str]
            [drawl.ir :as ir]))

(defn- esc [s]
  (-> (or s "")
      (str/replace "\\" "\\\\")
      (str/replace "\"" "\\\"")))

(defn- attr-list [pairs]
  (->> pairs
       (remove (fn [[_ v]] (or (nil? v) (= "" v))))
       (map (fn [[k v]] (str (name k) "=\"" (esc (str v)) "\"")))
       (str/join " ")))

(defmulti node-shape :kind)
(defmethod node-shape :person    [_] {:shape "oval"})
(defmethod node-shape :system    [_] {:shape "box" :style "rounded"})
(defmethod node-shape :container [_] {:shape "box" :style "rounded,filled"})
(defmethod node-shape :component [_] {:shape "box"})

(defn- display-label [{:keys [id title]}]
  (or title (name id)))

(defn- quote-id [id]
  (str "\"" (esc (name id)) "\""))

(defn- node-line [{:keys [id] :as el}]
  (str "  " (quote-id id) " ["
       (attr-list (assoc (node-shape el) :label (display-label el)))
       "];"))

(defn- edge-line [{:keys [from to description]}]
  (str "  " (quote-id from) " -> " (quote-id to)
       (when description (str " [" (attr-list {:label description}) "]"))
       ";"))

(defn- element-lines
  "An element renders as a leaf node, or as a subgraph cluster when it has
  children. The cluster name uses the element's own id (unique by validation),
  prefixed with cluster_ so graphviz treats it as a cluster."
  [{:keys [id children] :as el}]
  (if (seq children)
    (concat
     [(str "  subgraph \"cluster_" (esc (name id)) "\" {")
      (str "    " (attr-list {:label (display-label el)
                              :style "rounded,dashed"}) ";")]
     (mapcat element-lines children)
     ["  }"])
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
