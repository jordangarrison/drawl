(ns app.core
  "CodeMirror 6 editor -> drawl.compiler -> render pane.

  Two backends:
    :dot         -> compiled to dot source, rendered to SVG inline via viz.js
    :excalidraw  -> compiled to Excalidraw scene JSON, shown raw + downloadable
                    (no inline renderer; user opens the file in excalidraw.com)

  The cheatsheet overlay (keyboard + drawl syntax) toggles via the ?
  button or Ctrl+/."
  (:require [app.editor :as editor]
            [app.theme :as theme]
            [drawl.compiler :as drawl]
            ["@viz-js/viz" :as viz]))

(def ^:private initial-doc
  "(diagram \"Hello drawl\"
  (system foo \"Foo system\"))
")

(def ^:private default-tab "drawl")

(defn- by-id [id] (.getElementById js/document id))

(defonce ^:private viz-instance (.instance viz))

(defonce ^:private state (atom {:src initial-doc :backend :dot}))

(defn- backend-keyword [v]
  (case v "excalidraw" :excalidraw :dot))

(defn- file-info [backend]
  (case backend
    :excalidraw {:ext "excalidraw" :mime "application/json"  :summary "excalidraw json"}
    :dot        {:ext "dot"        :mime "text/vnd.graphviz" :summary "dot source"}))

(defn- show-error! [err-el msg]
  (set! (.-textContent err-el) msg))

(defn- render-svg-via-viz! [out-el err-el dot-source]
  (-> viz-instance
      (.then (fn [^js v]
               (.replaceChildren out-el (.renderSVGElement v dot-source))))
      (.catch (fn [e]
                (show-error! err-el (str "render: " (or (ex-message e) e)))))))

(defn- render! [src backend]
  (let [out-el  (by-id "out")
        err-el  (by-id "err")
        raw-el  (by-id "raw-output")
        ph-el   (by-id "out-placeholder")
        sum-el  (by-id "raw-summary")
        {:keys [summary]} (file-info backend)]
    (try
      (let [output (drawl/compile src backend)]
        (set! (.-textContent raw-el) output)
        (set! (.-textContent sum-el) summary)
        (show-error! err-el "")
        (case backend
          :dot        (do (set! (.-hidden ph-el)  true)
                          (set! (.-hidden out-el) false)
                          (render-svg-via-viz! out-el err-el output))
          :excalidraw (do (.replaceChildren out-el)
                          (set! (.-hidden out-el) true)
                          (set! (.-hidden ph-el)  false))))
      (catch :default e
        (show-error! err-el (or (ex-message e) (str e)))))))

(defn- run-current! []
  (let [{:keys [src backend]} @state]
    (render! src backend)))

(defn- update-src! [src]
  (swap! state assoc :src src)
  (run-current!))

(defn- update-backend! [backend]
  (swap! state assoc :backend backend)
  (run-current!))

(defn- trigger-download! []
  (let [{:keys [src backend]} @state
        {:keys [ext mime]}    (file-info backend)
        err-el                (by-id "err")]
    (try
      (let [content (drawl/compile src backend)
            blob    (js/Blob. #js [content] #js {:type mime})
            url     (.createObjectURL js/URL blob)
            a       (.createElement js/document "a")]
        (set! (.-href a) url)
        (set! (.-download a) (str "diagram." ext))
        (.appendChild (.-body js/document) a)
        (.click a)
        (.remove a)
        (js/setTimeout #(.revokeObjectURL js/URL url) 1000))
      (catch :default e
        (show-error! err-el (or (ex-message e) (str e)))))))

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
  (let [backend-el  (by-id "backend")
        download-el (by-id "download")
        {:keys [set-theme!]} (editor/mount (by-id "editor")
                                           initial-doc
                                           update-src!
                                           (theme/current-active-mode))]
    (theme/init! {:on-change set-theme!})
    (.addEventListener backend-el "change"
                       #(update-backend! (backend-keyword (.-value backend-el))))
    (.addEventListener download-el "click" trigger-download!)
    (run-current!)
    (wire-cheatsheet!)
    (register-service-worker!)))
