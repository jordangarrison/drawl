(ns drawl.compiler
  "Public API. Orchestrates parser -> walker -> ir -> emitter."
  (:refer-clojure :exclude [compile])
  (:require [drawl.parser :as parser]
            [drawl.walker :as walker]
            [drawl.ir :as ir]
            [drawl.emit.dot :as dot]))

(defn- diagram-form
  "Returns the single (diagram ...) form among parsed top-level forms.
  Throws ex-info if zero or more than one are present."
  [forms]
  (let [diagrams (filter #(and (seq? %) (= 'diagram (first %))) forms)]
    (when (not= 1 (count diagrams))
      (throw (ex-info (str "Expected exactly one (diagram ...) form, got "
                           (count diagrams))
                      {:type :walk-error :count (count diagrams)})))
    (first diagrams)))

(defn parse
  "Source string -> IR map. Throws ex-info on parse/walk errors."
  [source]
  (-> source
      parser/parse-forms
      diagram-form
      (walker/walk-form {})
      ir/with-level
      ir/validate))

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
