(ns drawl.emit.excalidraw
  "IR -> Excalidraw scene JSON (SPEC §6.3).

  Pure: (emit ir) -> string. No IO, no external tools. Layout is
  computed by a recursive containment pass over the IR (see
  test/fixtures/excalidraw/TRANSFORM.org for the algorithm and
  worked numbers)."
  (:require [drawl.ir :as ir]
            #?(:clj [clojure.data.json :as json])))

;; ── Layout constants ────────────────────────────────────────────────

(def ^:private LEAF-H        36)
(def ^:private LEAF-MIN-W    72)
(def ^:private CHAR-W         9)
(def ^:private PAD-LABEL     24)
(def ^:private PAD-SIBLING   24)
(def ^:private PAD-INNER     16)
(def ^:private FRAME-NAME-H  28)
(def ^:private LABEL-H       20)

;; ── Sizing & placement ─────────────────────────────────────────────

(defn- label-of [el] (or (:title el) (name (:id el))))

(defn- el-size [el]
  (if (seq (:children el))
    (let [csizes  (mapv el-size (:children el))
          n       (count csizes)
          inner-w (+ (reduce + (map :w csizes))
                     (* PAD-SIBLING (max 0 (dec n))))
          inner-h (apply max (map :h csizes))]
      {:w (+ inner-w (* 2 PAD-INNER))
       :h (+ inner-h FRAME-NAME-H PAD-INNER)})
    {:w (max LEAF-MIN-W (+ (* CHAR-W (count (label-of el))) PAD-LABEL))
     :h LEAF-H}))

(defn- place
  "Recursively place el and its descendants. Returns a flat seq of records
   {:el :x :y :w :h :frame-id} in depth-first, child-order."
  [el x y frame-id]
  (let [{:keys [w h]} (el-size el)
        self {:el el :x x :y y :w w :h h :frame-id frame-id}]
    (if (seq (:children el))
      (loop [cx (+ x PAD-INNER)
             cs (:children el)
             acc [self]]
        (if-let [c (first cs)]
          (let [child-recs (place c cx (+ y FRAME-NAME-H) (:id el))
                cw        (-> child-recs first :w)]
            (recur (+ cx cw PAD-SIBLING) (rest cs) (into acc child-recs)))
          acc))
      [self])))

(defn- layout
  "Walk the IR's top-level elements, flowing them left to right,
   vertically centered on the tallest. Returns flat placed records."
  [ir]
  (let [els   (:elements ir)
        sizes (mapv el-size els)
        max-h (apply max (map :h sizes))]
    (loop [cx 0
           ess (map vector els sizes)
           acc []]
      (if-let [[el sz] (first ess)]
        (let [y       (quot (- max-h (:h sz)) 2)
              records (place el cx y nil)]
          (recur (+ cx (:w sz) PAD-SIBLING) (rest ess) (into acc records)))
        acc))))

;; ── ID scheme & determinism ────────────────────────────────────────

(defn- shape-id        [id]        (str "drawl:" (name id)))
(defn- shape-label-id  [id]        (str "drawl:" (name id) ":label"))
(defn- edge-id         [from to]   (str "drawl:e:" (name from) ":" (name to)))
(defn- edge-label-id   [from to]   (str "drawl:e:" (name from) ":" (name to) ":label"))

(defn- stable-int
  "Deterministic non-negative 31-bit int derived from a string."
  [s]
  (let [h #?(:clj  (.hashCode ^String s)
             :cljs (hash s))]
    (bit-and h 0x7fffffff)))

;; ── Element construction ───────────────────────────────────────────

(def ^:private base
  {:angle 0
   :strokeColor "#1e1e1e"
   :backgroundColor "transparent"
   :fillStyle "solid"
   :strokeWidth 2
   :strokeStyle "solid"
   :roughness 1
   :opacity 100
   :groupIds []
   :isDeleted false
   :updated 0
   :link nil
   :locked false
   :version 1})

(defn- shape-type [el]
  (cond
    (= :person (:kind el))                 "ellipse"
    (= :database (:role (:attrs el)))      "ellipse"
    :else                                  "rectangle"))

(defn- stroke-style [el]
  (cond
    (:external (:attrs el))                "dashed"
    (= :queue (:role (:attrs el)))         "dashed"
    :else                                  "solid"))

(defn- rounded-leaf? [el]
  (and (empty? (:children el))
       (contains? #{:system :container :component} (:kind el))))

(defn- frame-record? [rec]
  (seq (:children (:el rec))))

(defn- frame-element [rec]
  (let [el (:el rec)
        id (shape-id (:id el))]
    (merge base
           {:id id
            :type "frame"
            :name (label-of el)
            :x (:x rec) :y (:y rec)
            :width (:w rec) :height (:h rec)
            :frameId (when-let [f (:frame-id rec)] (shape-id f))
            :roundness nil
            :seed         (stable-int (str id "/seed"))
            :versionNonce (stable-int (str id "/nonce"))
            :boundElements []})))

(defn- shape-bound-elements
  "For shape's :boundElements — its own label, plus any arrow it
   appears in (as :from or :to). Order: label first, then arrows in
   the order ir/collect-edges yields them."
  [el edges]
  (let [self (:id el)]
    (into [{:id (shape-label-id self) :type "text"}]
          (keep (fn [e]
                  (when (or (= self (:from e)) (= self (:to e)))
                    {:id (edge-id (:from e) (:to e)) :type "arrow"}))
                edges))))

(defn- shape-element [rec edges]
  (let [el (:el rec)
        id (shape-id (:id el))]
    (merge base
           {:id id
            :type (shape-type el)
            :x (:x rec) :y (:y rec)
            :width (:w rec) :height (:h rec)
            :frameId (when-let [f (:frame-id rec)] (shape-id f))
            :strokeStyle (stroke-style el)
            :roundness (when (rounded-leaf? el) {:type 3})
            :seed         (stable-int (str id "/seed"))
            :versionNonce (stable-int (str id "/nonce"))
            :boundElements (shape-bound-elements el edges)})))

(defn- shape-label-element [rec]
  (let [el  (:el rec)
        sid (shape-id (:id el))
        id  (shape-label-id (:id el))]
    (merge base
           {:id id
            :type "text"
            :x (:x rec)
            :y (+ (:y rec) (quot (- (:h rec) LABEL-H) 2))
            :width (:w rec) :height LABEL-H
            :frameId (when-let [f (:frame-id rec)] (shape-id f))
            :roundness nil
            :seed         (stable-int (str id "/seed"))
            :versionNonce (stable-int (str id "/nonce"))
            :boundElements nil
            :text (label-of el)
            :originalText (label-of el)
            :fontSize 16
            :fontFamily 5
            :textAlign "center"
            :verticalAlign "middle"
            :lineHeight 1.25
            :containerId sid})))

(defn- edge-stroke-style [e]
  (let [s (:style (:attrs e))]
    (case s
      (:dashed "dashed") "dashed"
      (:dotted "dotted") "dotted"
      "solid")))

(defn- arrow-element [e from to]
  (let [sx (+ (:x from) (:w from))
        sy (+ (:y from) (quot (:h from) 2))
        ex (:x to)
        ey (+ (:y to) (quot (:h to) 2))
        dx (- ex sx)
        dy (- ey sy)
        cross? (not= (:frame-id from) (:frame-id to))
        fid    (when (not cross?) (when-let [f (:frame-id from)] (shape-id f)))
        id     (edge-id (:from e) (:to e))]
    (merge base
           {:id id
            :type "arrow"
            :x sx :y sy
            :width dx :height dy
            :frameId fid
            :strokeStyle (edge-stroke-style e)
            :roundness {:type 2}
            :seed         (stable-int (str id "/seed"))
            :versionNonce (stable-int (str id "/nonce"))
            :boundElements (when (:description e)
                             [{:id (edge-label-id (:from e) (:to e)) :type "text"}])
            :points [[0 0] [dx dy]]
            :lastCommittedPoint nil
            :startBinding {:elementId (shape-id (:from e)) :focus 0 :gap 4}
            :endBinding   {:elementId (shape-id (:to e))   :focus 0 :gap 4}
            :startArrowhead (when (:bidirectional? e) "arrow")
            :endArrowhead   "arrow"
            :elbowed false})))

(defn- arrow-label-element [e from to]
  (let [sx (+ (:x from) (:w from))
        sy (+ (:y from) (quot (:h from) 2))
        ex (:x to)
        ey (+ (:y to) (quot (:h to) 2))
        dx (- ex sx)
        cross? (not= (:frame-id from) (:frame-id to))
        fid    (when (not cross?) (when-let [f (:frame-id from)] (shape-id f)))
        aid    (edge-id (:from e) (:to e))
        id     (edge-label-id (:from e) (:to e))]
    (merge base
           {:id id
            :type "text"
            :x sx
            :y (- (min sy ey) LABEL-H 2)
            :width dx :height LABEL-H
            :frameId fid
            :roundness nil
            :seed         (stable-int (str id "/seed"))
            :versionNonce (stable-int (str id "/nonce"))
            :boundElements nil
            :text (:description e)
            :originalText (:description e)
            :fontSize 16
            :fontFamily 5
            :textAlign "center"
            :verticalAlign "middle"
            :lineHeight 1.25
            :containerId aid})))

(defn- build-elements [ir]
  (let [placed (layout ir)
        edges  (vec (ir/collect-edges ir))
        by-id  (into {} (map (juxt #(:id (:el %)) identity)) placed)]
    (-> []
        (into (mapcat (fn [rec]
                        (if (frame-record? rec)
                          [(frame-element rec)]
                          [(shape-element rec edges)
                           (shape-label-element rec)])))
              placed)
        (into (mapcat (fn [e]
                        (let [from (by-id (:from e))
                              to   (by-id (:to e))
                              arrow (arrow-element e from to)]
                          (if (:description e)
                            [arrow (arrow-label-element e from to)]
                            [arrow]))))
              edges))))

(defn- scene [ir]
  {:type     "excalidraw"
   :version  2
   :source   "drawl"
   :appState {:viewBackgroundColor "#ffffff"
              :gridSize            nil
              :gridStep            5
              :theme               "light"}
   :files    {}
   :elements (build-elements ir)})

(defn- to-json [m]
  #?(:clj  (json/write-str m)
     :cljs (.stringify js/JSON (clj->js m))))

(defn emit
  "IR -> Excalidraw scene JSON string."
  [ir]
  (to-json (scene ir)))
