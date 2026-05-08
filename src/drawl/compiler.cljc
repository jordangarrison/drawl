(ns drawl.compiler
  "Public API. Orchestrates parser -> macro registration -> walker -> ir -> emitter."
  (:refer-clojure :exclude [compile])
  (:require [drawl.parser :as parser]
            [drawl.walker :as walker]
            [drawl.macros :as macros]
            [drawl.ir :as ir]
            [drawl.emit.dot :as dot]))

(defn- form-head [f] (when (seq? f) (first f)))

(defn- collect-macros
  "Reduce top-level (defmacro ...) forms into a registry, seeded with builtins.
  Warns on override per drawl.macros/register."
  [forms]
  (reduce (fn [reg form]
            (let [[head template] (macros/parse-defmacro form)]
              (macros/register reg head template)))
          macros/builtins
          (filter #(= 'defmacro (form-head %)) forms)))

(defn- the-diagram
  "Returns the single (diagram ...) form among non-defmacro top-levels.
  Throws ex-info if zero or more than one are present."
  [forms]
  (let [diagrams (filter #(= 'diagram (form-head %)) forms)]
    (when (not= 1 (count diagrams))
      (throw (ex-info (str "Expected exactly one (diagram ...) form, got "
                           (count diagrams))
                      {:type :walk-error :count (count diagrams)})))
    (first diagrams)))

(defn parse
  "Source string -> IR map. Throws ex-info on parse/walk errors."
  [source]
  (let [forms (parser/parse-forms source)
        ctx  {:macros (collect-macros forms)}]
    (-> (the-diagram forms)
        (walker/walk-form ctx)
        ir/with-level
        ir/validate)))

(defn emit
  "IR -> output string for the given backend."
  [ir backend]
  (case backend
    :dot (dot/emit ir)
    (throw (ex-info (str "Unknown backend: " backend)
                    {:type :emit-error :backend backend}))))

(defn compile
  "Source string -> output string for the given backend."
  [source backend]
  (emit (parse source) backend))

(defn validate
  "Returns nil if valid, or a seq of error maps. Does not throw."
  [source]
  (try
    (parse source)
    nil
    (catch #?(:clj Exception :cljs :default) e
      [(merge {:message #?(:clj (.getMessage e) :cljs (ex-message e))}
              (ex-data e))])))
