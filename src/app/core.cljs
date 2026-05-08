(ns app.core
  "Slice 9c: CodeMirror 6 editor -> drawl.compiler/compile -> viz.js -> SVG.
  Compile/render pipeline unchanged from slice 9a; the source pane is now
  a CM6 view (see app.editor) and the cheatsheet overlay (keyboard +
  drawl syntax) toggles via the ? button or F1."
  (:require [app.editor :as editor]
            [drawl.compiler :as drawl]
            ["@viz-js/viz" :as viz]))

(def ^:private initial-doc
  "(diagram \"Hello drawl\"
  (system foo \"Foo system\"))
")

(def ^:private default-tab "drawl")

(defn- by-id [id] (.getElementById js/document id))

(defonce ^:private viz-instance (.instance viz))

(defn- show-error! [err-el msg]
  (set! (.-textContent err-el) msg))

(defn- render-svg-via-viz! [out-el err-el dot-source]
  (-> viz-instance
      (.then (fn [^js v]
               (.replaceChildren out-el (.renderSVGElement v dot-source))))
      (.catch (fn [e]
                (show-error! err-el (str "render: " (or (ex-message e) e)))))))

(defn- render! [src out-el err-el dot-el]
  (try
    (let [dot-source (drawl/compile src :dot)]
      (set! (.-textContent dot-el) dot-source)
      (show-error! err-el "")
      (render-svg-via-viz! out-el err-el dot-source))
    (catch :default e
      (show-error! err-el (or (ex-message e) (str e))))))

(defn- select-tab! [tab-id]
  (doseq [^js btn (array-seq (.querySelectorAll js/document "#cheatsheet .tab"))]
    (.toggle (.-classList btn) "active"
             (= tab-id (.getAttribute btn "data-tab"))))
  (doseq [^js panel (array-seq (.querySelectorAll js/document "#cheatsheet .tab-panel"))]
    (.toggle (.-classList panel) "active"
             (= tab-id (.getAttribute panel "data-tab")))))

(defn- toggle-cheatsheet! []
  (.toggle (.-classList (by-id "cheatsheet")) "open"))

(defn- close-cheatsheet! []
  (.remove (.-classList (by-id "cheatsheet")) "open"))

(defn- wire-cheatsheet! []
  (select-tab! default-tab)
  (.addEventListener (by-id "cheatsheet-toggle") "click" toggle-cheatsheet!)
  (.addEventListener (by-id "cheatsheet-close") "click" close-cheatsheet!)
  (doseq [^js btn (array-seq (.querySelectorAll js/document "#cheatsheet .tab"))]
    (.addEventListener btn "click"
                       #(select-tab! (.getAttribute btn "data-tab"))))
  (.addEventListener js/document "keydown"
                     (fn [^js e]
                       (cond
                         (and (.-ctrlKey e) (= "/" (.-key e)))
                         (do (.preventDefault e)
                             (.stopPropagation e)
                             (toggle-cheatsheet!))

                         (= "Escape" (.-key e))
                         (close-cheatsheet!)))
                     true))

(defn- register-service-worker! []
  (let [host (.-hostname js/location)
        sw   (.-serviceWorker js/navigator)]
    (when (and sw (not (contains? #{"localhost" "127.0.0.1" ""} host)))
      (-> (.register sw "/sw.js")
          (.catch (fn [e] (js/console.warn "sw register failed:" e)))))))

(defn ^:export init []
  (let [out-el (by-id "out")
        err-el (by-id "err")
        dot-el (by-id "dot")
        run    #(render! % out-el err-el dot-el)]
    (editor/mount (by-id "editor") initial-doc run)
    (run initial-doc)
    (wire-cheatsheet!)
    (register-service-worker!)))
