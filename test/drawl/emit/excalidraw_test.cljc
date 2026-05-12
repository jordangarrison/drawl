(ns drawl.emit.excalidraw-test
  (:require [clojure.test :refer [deftest is testing]]
            [drawl.compiler :as c]
            #?(:clj  [clojure.data.json :as json])))

(defn- parse [s]
  #?(:clj  (json/read-str s :key-fn keyword)
     :cljs (js->clj (.parse js/JSON s) :keywordize-keys true)))

(defn- elements-by-id [scene]
  (into {} (map (juxt :id identity)) (:elements scene)))

(defn- compile-scene [src]
  (parse (c/compile src :excalidraw)))

(deftest envelope-shape
  (let [s (compile-scene "(diagram \"Hi\" (system foo \"Foo\"))")]
    (is (= "excalidraw" (:type s)))
    (is (= 2 (:version s)))
    (is (= "drawl" (:source s)))
    (is (map? (:appState s)))
    (is (map? (:files s)))
    (is (vector? (:elements s)))))

(deftest leaf-person-renders-ellipse
  (let [s (compile-scene "(diagram (person alice \"Alice\"))")
        el (get (elements-by-id s) "drawl:alice")]
    (is (= "ellipse" (:type el)))
    (is (zero? (:x el)))
    (is (pos? (:width el)))
    (is (= 36 (:height el)))))

(deftest leaf-system-renders-rounded-rectangle
  (let [s (compile-scene "(diagram (system foo \"Foo\"))")
        el (get (elements-by-id s) "drawl:foo")]
    (is (= "rectangle" (:type el)))
    (is (some? (:roundness el)))))

(deftest system-with-children-renders-frame
  (let [s (compile-scene
            "(diagram (system bank \"Bank\" (container db \"DB\")))")
        idx (elements-by-id s)
        frame (get idx "drawl:bank")
        child (get idx "drawl:db")]
    (is (= "frame" (:type frame)))
    (is (= "Bank" (:name frame)))
    (is (= "drawl:bank" (:frameId child)))))

(deftest edge-binds-both-ends
  (let [s (compile-scene
            "(diagram (person a) (system b) (-> a b \"visits\"))")
        idx (elements-by-id s)
        arrow (get idx "drawl:e:a:b")
        a     (get idx "drawl:a")
        b     (get idx "drawl:b")]
    (is (= "arrow" (:type arrow)))
    (is (= "drawl:a" (-> arrow :startBinding :elementId)))
    (is (= "drawl:b" (-> arrow :endBinding :elementId)))
    (is (some #(= "drawl:e:a:b" (:id %)) (:boundElements a)))
    (is (some #(= "drawl:e:a:b" (:id %)) (:boundElements b)))))

(deftest bidirectional-edge-sets-both-arrowheads
  (let [s (compile-scene
            "(diagram (person a) (system b) (<-> a b))")
        arrow (get (elements-by-id s) "drawl:e:a:b")]
    (is (= "arrow" (:startArrowhead arrow)))
    (is (= "arrow" (:endArrowhead arrow)))))

(deftest edge-without-description-omits-label
  (let [s (compile-scene "(diagram (person a) (system b) (-> a b))")
        idx (elements-by-id s)]
    (is (some? (get idx "drawl:e:a:b")))
    (is (nil?  (get idx "drawl:e:a:b:label")))))

(deftest edge-with-description-emits-bound-label
  (let [s (compile-scene "(diagram (person a) (system b) (-> a b \"hi\"))")
        idx (elements-by-id s)
        label (get idx "drawl:e:a:b:label")]
    (is (= "text" (:type label)))
    (is (= "drawl:e:a:b" (:containerId label)))
    (is (= "hi" (:text label)))))

(deftest external-attr-sets-dashed-stroke
  (let [s (compile-scene "(diagram (system api \"API\" :external true))")
        el (get (elements-by-id s) "drawl:api")]
    (is (= "dashed" (:strokeStyle el)))))

(deftest database-role-renders-ellipse
  (let [s (compile-scene "(diagram (system db \"DB\" :role :database))")
        el (get (elements-by-id s) "drawl:db")]
    (is (= "ellipse" (:type el)))))

(deftest deterministic-emit
  (let [src "(diagram (person a) (system b) (-> a b \"hi\"))"]
    (is (= (c/compile src :excalidraw)
           (c/compile src :excalidraw)))))

(deftest text-uses-numeric-font-family
  (let [s (compile-scene "(diagram (person alice \"Alice\"))")
        label (get (elements-by-id s) "drawl:alice:label")]
    (is (number? (:fontFamily label)))
    (is (= 5 (:fontFamily label)))))

(deftest cross-frame-arrow-has-nil-frame-id
  (let [s (compile-scene
            "(diagram (person u) (system bank (container api \"API\")) (-> u api))")
        idx (elements-by-id s)
        arrow (get idx "drawl:e:u:api")]
    (is (nil? (:frameId arrow)))))

(deftest within-frame-arrow-belongs-to-frame
  (let [s (compile-scene
            "(diagram (system bank (container a) (container b) (-> a b)))")
        arrow (get (elements-by-id s) "drawl:e:a:b")]
    (is (= "drawl:bank" (:frameId arrow)))))

#?(:clj
   (deftest fixture-snapshot
     (testing "example 03 emitter output matches the hand-written fixture
               on all load-bearing fields."
       (let [src      (slurp "examples/03-bank-containers.drawl")
             out      (parse (c/compile src :excalidraw))
             fixture  (parse (slurp "test/fixtures/excalidraw/03-bank.excalidraw"))
             pick     #(select-keys % [:id :type :x :y :width :height
                                       :frameId :containerId :name :text
                                       :startArrowhead :endArrowhead])
             out-by   (into {} (map (juxt :id pick)) (:elements out))
             fix-by   (into {} (map (juxt :id pick)) (:elements fixture))]
         (is (= (set (keys fix-by)) (set (keys out-by))))
         (doseq [id (sort (keys fix-by))]
           (is (= (fix-by id) (out-by id))
               (str "element " id)))))))
