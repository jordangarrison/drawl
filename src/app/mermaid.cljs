(ns app.mermaid
  "Thin wrapper around mermaid.js. Lazy-init the library once, then
   `render` a source string into an SVG element."
  (:require ["mermaid$default" :as mermaid]))

(defonce ^:private init!
  (do
    ;; Default `securityLevel` is `"strict"` — mermaid runs labels and
    ;; click attributes through DOMPurify. drawl source is user-authored
    ;; (incl. URL hash share links), so a label like `<img onerror=…>`
    ;; would otherwise reach the SVG renderer unescaped. We don't use
    ;; mermaid's `click` directives or HTML labels, so strict costs us
    ;; nothing here.
    (.initialize mermaid #js {:startOnLoad false
                              :theme "default"})
    true))

(def ^:private counter (atom 0))

(defn- next-id []
  (str "drawl-mermaid-" (swap! counter inc)))

(defn render
  "src (string) -> Promise<SVGElement>.

  mermaid.render returns {svg, bindFunctions}. We stuff the SVG string
  into a throwaway `<div>` via innerHTML so the caller can grab the
  `<svg>` child — DOMParser strips namespaced attributes that mermaid
  emits, which silently kills layout for several diagram types.

  We force a white background on the SVG: mermaid C4's palette is
  hard-coded to light-mode colours, so a transparent background under
  the dark drawl theme washes the diagram out. Picking white in both
  themes keeps the diagram legible at the cost of theme mismatch."
  [src]
  (-> (.render mermaid (next-id) src)
      (.then (fn [^js result]
               (let [svg (.-svg result)
                     div (.createElement js/document "div")]
                 (set! (.-innerHTML div) svg)
                 (let [^js el (.querySelector div "svg")]
                   (.setAttribute el "style"
                                  (str (or (.getAttribute el "style") "")
                                       ";background:#ffffff;"))
                   el))))))
