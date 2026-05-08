(ns app.editor
  "CodeMirror 6 + nextjournal/clojure-mode wrapper.

  `mount` builds an EditorView on the given parent element, invokes
  `on-change` with the doc string after every mutation, and returns

      {:view view, :set-theme! (fn [:light|:dark])}

  The Compartment + view live in the returned closure rather than in
  module-level state, so multiple editors are possible and the caller
  owns the lifecycle. Extension list mirrors the upstream demo at
  github.com/nextjournal/clojure-mode/blob/main/demo/src/.../demo.cljs
  minus editing helpers we don't need (eval-region, sci, livedoc)."
  (:require ["@codemirror/commands" :refer [history historyKeymap]]
            ["@codemirror/language" :refer [foldGutter
                                            syntaxHighlighting
                                            HighlightStyle]]
            ["@codemirror/state" :refer [EditorState Compartment]]
            ["@codemirror/view" :as view :refer [EditorView]]
            ["@lezer/highlight" :refer [tags]]
            [nextjournal.clojure-mode :as cm-clj]))

(defn- palette [mode]
  (case mode
    :dark  {:base "#191724" :surface "#1f1d2e" :overlay "#26233a"
            :text "#e0def4" :muted "#6e6a86" :subtle "#908caa"
            :pine "#31748f" :foam "#9ccfd8" :iris "#c4a7e7"
            :love "#eb6f92" :gold "#f6c177" :rose "#ebbcba"
            :hl-med "#403d52"}
    :light {:base "#faf4ed" :surface "#fffaf3" :overlay "#f2e9e1"
            :text "#575279" :muted "#9893a5" :subtle "#797593"
            :pine "#286983" :foam "#56949f" :iris "#907aa9"
            :love "#b4637a" :gold "#ea9d34" :rose "#d7827e"
            :hl-med "#dfdad9"}))

(defn- theme-bundle
  "EditorView.theme + Rosé Pine HighlightStyle, paired together so the
  Compartment swap stays atomic and palette destructuring happens once."
  [mode]
  (let [{:keys [base surface overlay text subtle pine foam iris
                love gold rose muted hl-med]} (palette mode)]
    #js [(.theme EditorView
                 #js {"&"                       #js {:backgroundColor base
                                                     :color           text}
                      ".cm-content"             #js {:caretColor pine}
                      ".cm-cursor, .cm-dropCursor" #js {:borderLeftColor pine}
                      ".cm-gutters"             #js {:backgroundColor surface
                                                     :color           subtle
                                                     :border          "0"}
                      ".cm-activeLine"          #js {:backgroundColor overlay}
                      ".cm-activeLineGutter"    #js {:backgroundColor overlay}
                      "&.cm-focused .cm-selectionBackground, ::selection, .cm-selectionBackground"
                                                #js {:backgroundColor hl-med}
                      ".cm-matchingBracket, &.cm-focused .cm-matchingBracket"
                                                #js {:backgroundColor "transparent"
                                                     :outline         (str "1px solid " iris)
                                                     :color           iris}}
                 ;; CodeMirror's own dark-mode signal — drives selection
                 ;; contrast heuristics, not duplicated by `mode`.
                 #js {:dark (= mode :dark)})
         (syntaxHighlighting
          (.define HighlightStyle
                   #js [#js {:tag (.-keyword tags)        :color love}
                        #js {:tag (.-string tags)         :color gold}
                        #js {:tag (.-comment tags)        :color muted
                                                          :fontStyle "italic"}
                        #js {:tag (.-number tags)         :color rose}
                        #js {:tag (.-bool tags)           :color rose}
                        #js {:tag (.-atom tags)           :color rose}
                        #js {:tag (.-operator tags)       :color foam}
                        #js {:tag (.-punctuation tags)    :color muted}
                        #js {:tag (.-bracket tags)        :color muted}
                        #js {:tag ((.-function tags)
                                   (.-variableName tags)) :color pine}
                        #js {:tag (.-variableName tags)   :color text}
                        #js {:tag (.-meta tags)           :color iris}]))]))

(defn- change-listener [on-change]
  (.. EditorView
      -updateListener
      (of (fn [^js update]
            (when (.-docChanged update)
              (on-change (.. update -state -doc (toString))))))))

(defn- extensions [on-change theme-compartment initial-mode]
  #js [(history)
       (.of theme-compartment (theme-bundle initial-mode))
       (view/drawSelection)
       (foldGutter)
       (.. EditorState -allowMultipleSelections (of true))
       cm-clj/default-extensions
       (.of view/keymap cm-clj/complete-keymap)
       (.of view/keymap historyKeymap)
       (change-listener on-change)])

(defn mount
  "Mount a CodeMirror view into `parent`. Returns
  `{:view ..., :set-theme! (fn [:light|:dark])}`."
  [^js parent initial-doc on-change initial-mode]
  (let [theme-compartment (Compartment.)
        view (EditorView.
              #js {:parent parent
                   :state  (.create EditorState
                                    #js {:doc        initial-doc
                                         :extensions (extensions on-change
                                                                 theme-compartment
                                                                 initial-mode)})})]
    {:view       view
     :set-theme! (fn [mode]
                   (.dispatch view
                              #js {:effects (.reconfigure theme-compartment
                                                          (theme-bundle mode))}))}))

(defn value [^js view]
  (.. view -state -doc (toString)))
