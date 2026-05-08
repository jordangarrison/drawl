(ns drawl.macros
  "Built-in shorthand macros (SPEC §2.6). Each macro re-dispatches walk-form
  on a canonical form, threading user-supplied opts after the macro-injected
  attrs so user keys override.

  Layout: (head ID title? description? :attrs... children...) — same as the
  underlying element. Positional title/description is peeled before the macro
  attrs so the canonical walker still sees title in positional position."
  (:require [drawl.walker :as walker]))

(defn- peel-strings [forms]
  (loop [out [] fs forms]
    (if (and (< (count out) 2) (string? (first fs)))
      (recur (conj out (first fs)) (rest fs))
      [out fs])))

(defn- expand
  "Build the canonical (container ID positionals... attrs-pre... rest...) form."
  [form attrs-pre]
  (let [[_ id & rest]            form
        [positionals tail]       (peel-strings rest)]
    (concat (list 'container id) positionals attrs-pre tail)))

(defmethod walker/walk-form 'phoenix-app [form ctx]
  (walker/walk-form (expand form [:tech "Phoenix"]) ctx))

(defmethod walker/walk-form 'postgres-db [form ctx]
  (walker/walk-form (expand form [:role :database :tech "Postgres"]) ctx))

(defmethod walker/walk-form 'redis-cache [form ctx]
  (walker/walk-form (expand form [:role :cache :tech "Redis"]) ctx))

(defmethod walker/walk-form 'rest-api [form ctx]
  (walker/walk-form (expand form [:tech "REST API"]) ctx))
