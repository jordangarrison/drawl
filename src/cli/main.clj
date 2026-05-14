(ns cli.main
  "drawl CLI entry. Dispatches subcommands to handlers that return integer
  exit codes. -main calls System/exit only when invoked as the process
  entrypoint; tests call -main directly and just consume the return value."
  (:require [babashka.cli :as cli]
            [clojure.java.io :as io]
            [drawl.compiler :as drawl]))

(def ^:private backend-aliases
  {"dot" :dot "mermaid" :mermaid "excalidraw" :excalidraw})

(def ^:private cli-spec
  {:input   {:alias :i :desc "Path to .drawl source (omit for stdin)"}
   :output  {:alias :o :desc "Path to write output (omit for stdout)"}
   :backend {:alias :b :desc "dot|mermaid|excalidraw" :default "dot"
             :coerce :string}})

(defn- read-source [^String input]
  (if (and input (not= "-" input))
    (slurp input)
    (slurp *in*)))

(defn- write-output [^String output ^String s]
  (if (and output (not= "-" output))
    (spit output s)
    (do (.write *out* s) (.flush *out*))))

(defn- parse-backend [^String s]
  (or (backend-aliases s)
      (throw (ex-info (str "Unknown backend: " s)
                      {:type :cli-error :backend s}))))

(defn- print-error [^Throwable t]
  (let [{:keys [type message position]} (merge {:message (ex-message t)}
                                               (ex-data t))]
    (binding [*out* *err*]
      (println (str (or type "error") ": " message
                    (when position (str " at " position)))))))

(defn compile-cmd [{:keys [input output backend]}]
  (try
    (let [src (read-source input)
          out (drawl/compile src (parse-backend backend))]
      (write-output output out)
      0)
    (catch clojure.lang.ExceptionInfo e (print-error e) 1)
    (catch java.io.IOException e
      (binding [*out* *err*] (println (str "io-error: " (.getMessage e))))
      1)))

(defn lint-cmd [{:keys [input]}]
  (try
    (let [src    (read-source input)
          errors (drawl/validate src)]
      (if (nil? errors)
        0
        (do (doseq [e errors]
              (binding [*out* *err*]
                (println (str (:type e) ": " (:message e)
                              (when-let [p (:position e)] (str " at " p))))))
            1)))
    (catch clojure.lang.ExceptionInfo e (print-error e) 1)
    (catch java.io.IOException e
      (binding [*out* *err*] (println (str "io-error: " (.getMessage e))))
      1)))

(defn watch-cmd [_opts]
  ;; Placeholder — wired in Task 6.
  (binding [*out* *err*] (println "watch: not yet implemented"))
  2)

(def ^:private usage
  "Usage: drawl <subcommand> [options]

  compile  -i FILE [-b BACKEND] [-o FILE]   Emit backend output (default backend: dot)
  lint     -i FILE                          Validate without emitting
  watch    -i FILE [-b BACKEND] [-o FILE]   Recompile on file change

  Backends: dot (default), mermaid, excalidraw")

(defn -main [& args]
  (let [[sub & rest-args] args]
    (case sub
      "compile" (compile-cmd (cli/parse-opts rest-args {:spec cli-spec}))
      "lint"    (lint-cmd    (cli/parse-opts rest-args {:spec cli-spec}))
      "watch"   (watch-cmd   (cli/parse-opts rest-args {:spec cli-spec}))
      (do (binding [*out* *err*] (println usage))
          2))))
