(ns drawl.parser
  "Source string -> Clojure forms.

  Wraps source in implicit (do ...) so multiple top-level forms are legal
  (per SPEC §2.1). Returns the seq of top-level forms inside the do."
  #?(:clj  (:require [clojure.edn :as edn])
     :cljs (:require [cljs.reader :as reader])))

(defn- read-do-wrapped [source]
  (let [wrapped (str "(do " source "\n)")]
    #?(:clj  (edn/read-string wrapped)
       :cljs (reader/read-string wrapped))))

(defn parse-forms
  "Parse source string into a seq of top-level forms.

  Throws ex-info with :type :parse-error on reader failure."
  [source]
  (try
    (rest (read-do-wrapped source))
    (catch #?(:clj Exception :cljs :default) e
      (throw (ex-info (str "Parse error: "
                           #?(:clj (.getMessage e) :cljs (ex-message e)))
                      {:type :parse-error :source source}
                      e)))))
