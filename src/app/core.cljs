(ns app.core
  "Slice 10 + 9a: textarea -> drawl.compiler/compile -> viz.js -> SVG.
  No React/CodeMirror yet. Slice 9 polish (share links, localStorage,
  backend toggle, mermaid) lands later."
  (:require [drawl.compiler :as drawl]
            ["@viz-js/viz" :as viz]))

(defn- by-id [id] (.getElementById js/document id))

(defonce ^:private viz-instance
  (.instance viz))

(defn- show-error! [err-el msg]
  (set! (.-textContent err-el) msg))

(defn- show-svg! [out-el svg-el]
  (.replaceChildren out-el svg-el))

(defn- render-svg-via-viz! [out-el err-el dot-source]
  (-> viz-instance
      (.then (fn [^js v]
               (show-svg! out-el (.renderSVGElement v dot-source))))
      (.catch (fn [e]
                (show-error! err-el (str "render: " (or (ex-message e) e)))))))

(defn- render! [src-el out-el err-el dot-el]
  (try
    (let [dot-source (drawl/compile (.-value src-el) :dot)]
      (set! (.-textContent dot-el) dot-source)
      (show-error! err-el "")
      (render-svg-via-viz! out-el err-el dot-source))
    (catch :default e
      (show-error! err-el (or (ex-message e) (str e))))))

(defn ^:export init []
  (let [src-el (by-id "src")
        out-el (by-id "out")
        err-el (by-id "err")
        dot-el (by-id "dot")
        run    #(render! src-el out-el err-el dot-el)]
    (.addEventListener src-el "input" run)
    (run)))
