(ns app.editor
  "CodeMirror 6 + nextjournal/clojure-mode wrapper.

  Single entry point: `mount` builds an EditorView on the given element
  and invokes `on-change` with the current doc string after every edit
  that mutates the document. Extension list mirrors the upstream demo
  at github.com/nextjournal/clojure-mode/blob/main/demo/src/.../demo.cljs
  minus the editing helpers we don't need (eval-region, sci, livedoc)."
  (:require ["@codemirror/commands" :refer [history historyKeymap]]
            ["@codemirror/language" :refer [foldGutter
                                            syntaxHighlighting
                                            defaultHighlightStyle]]
            ["@codemirror/state" :refer [EditorState]]
            ["@codemirror/view" :as view :refer [EditorView]]
            [nextjournal.clojure-mode :as cm-clj]))

(defn- change-listener [on-change]
  (.. EditorView
      -updateListener
      (of (fn [^js update]
            (when (.-docChanged update)
              (on-change (.. update -state -doc (toString))))))))

(defn extensions [on-change]
  #js [(history)
       (syntaxHighlighting defaultHighlightStyle)
       (view/drawSelection)
       (foldGutter)
       (.. EditorState -allowMultipleSelections (of true))
       cm-clj/default-extensions
       (.of view/keymap cm-clj/complete-keymap)
       (.of view/keymap historyKeymap)
       (change-listener on-change)])

(defn mount
  "Mount a CodeMirror view into `parent` (DOM element).
  Calls `(on-change doc-string)` after every doc mutation.
  Returns the EditorView so the caller can read state or destroy it."
  [^js parent initial-doc on-change]
  (EditorView.
   #js {:parent parent
        :state  (.create EditorState
                         #js {:doc        initial-doc
                              :extensions (extensions on-change)})}))

(defn value [^js view]
  (.. view -state -doc (toString)))
