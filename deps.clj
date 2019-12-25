#!/usr/bin/env bb --verbose

(require '[clojure.string :as str]
         '[clojure.java.io :as io])

(import '[java.lang ProcessBuilder$Redirect])

(set! *warn-on-reflection* true)

(defn shell-command
  "Executes shell command. Exits script when the shell-command has a non-zero exit code, propagating it.

  Accepts the following options:

  `:input`: instead of reading from stdin, read from this string.
  `:to-string?`: instead of writing to stdoud, write to a string and
  return it."
  ([args] (shell-command args nil))
  ([args {:keys [:input :to-string?]}]
   (let [args (mapv str args)
         pb (cond-> (-> (ProcessBuilder. ^java.util.List args)
                        (.redirectError ProcessBuilder$Redirect/INHERIT))
              (not to-string?) (.redirectOutput ProcessBuilder$Redirect/INHERIT)
              (not input) (.redirectInput ProcessBuilder$Redirect/INHERIT))
         proc (.start pb)]
     (when input
       (with-open [w (io/writer (.getOutputStream proc))]
         (binding [*out* w]
           (print input)
           (flush))))
     (let [string-out
           (when to-string?
             (let [sw (java.io.StringWriter.)]
               (with-open [w (io/reader (.getInputStream proc))]
                 (io/copy w sw))
               (str sw)))
           exit-code (.waitFor proc)]
       (when-not (zero? exit-code)
         (System/exit exit-code))
       string-out))))

(def project-version "0.0.1-SNAPSHOT")

(def help-text (str/trim "
Usage: clojure [dep-opt*] [init-opt*] [main-opt] [arg*]
       clj     [dep-opt*] [init-opt*] [main-opt] [arg*]

The clojure script is a runner for Clojure. clj is a wrapper
for interactive repl use. These scripts ultimately construct and
invoke a command-line of the form:

java [java-opt*] -cp classpath clojure.main [init-opt*] [main-opt] [arg*]

The dep-opts are used to build the java-opts and classpath:
 -Jopt          Pass opt through in java_opts, ex: -J-Xmx512m
 -Oalias...     Concatenated jvm option aliases, ex: -O:mem
 -Ralias...     Concatenated resolve-deps aliases, ex: -R:bench:1.9
 -Calias...     Concatenated make-classpath aliases, ex: -C:dev
 -Malias...     Concatenated main option aliases, ex: -M:test
 -Aalias...     Concatenated aliases of any kind, ex: -A:dev:mem
 -Sdeps EDN     Deps data to use as the last deps file to be merged
 -Spath         Compute classpath and echo to stdout only
 -Scp CP        Do NOT compute or cache classpath, use this one instead
 -Srepro        Ignore the ~/.clojure/deps.edn config file
 -Sforce        Force recomputation of the classpath (don't use the cache)
 -Spom          Generate (or update existing) pom.xml with deps and paths
 -Stree         Print dependency tree
 -Sresolve-tags Resolve git coordinate tags to shas and update deps.edn
 -Sverbose      Print important path info to console
 -Sdescribe     Print environment and command parsing info as data
 -Strace        Write a trace.edn file that traces deps expansion

The following non-standard options are added:

 -Sdeps-file    Use this file instead of deps.edn
 -Scommand      A custom command that will be invoked. Substitutions: {{classpath}}, {{main-opts}}.

init-opt:
 -i, --init path     Load a file or resource
 -e, --eval string   Eval exprs in string; print non-nil values
 --report target     Report uncaught exception to \"file\" (default), \"stderr\", or \"none\",
                     overrides System property clojure.main.report

main-opt:
 -m, --main ns-name  Call the -main function from namespace w/args
 -r, --repl          Run a repl
 path                Run a script from a file or resource
 -                   Run a script from standard input
 -h, -?, --help      Print this help message and exit

For more info, see:
 https://clojure.org/guides/deps_and_cli
 https://clojure.org/reference/repl_and_main
"))

(def parse-opts->keyword
  {"-J" :jvm-opts
   "-R" :resolve-aliases
   "-C" :classpath-aliases
   "-O" :jvm-aliases
   "-M" :main-aliases
   "-A" :all-aliases})

(def bool-opts->keyword
  {"-Spath" :print-classpath
   "-Sverbose" :verbose
   "-Strace" :trace
   "-Sdescribe" :describe
   "-Sforce" :force
   "-Srepro" :repro
   "-Stree" :tree
   "-Spom" :pom
   "-Sresolve-tags" :resolve-tags})

(def string-opts->keyword
  {"-Sdeps" :deps-data
   "-Scp" :force-cp
   "-Sdeps-file" :deps-file
   "-Scommand" :command})

(def args "the parsed arguments"
  (loop [command-line-args (seq *command-line-args*)
         acc {}]
    (if command-line-args
      (let [arg (first command-line-args)
            bool-opt-keyword (get bool-opts->keyword arg)
            string-opt-keyword (get string-opts->keyword arg)]
        (cond (some #(str/starts-with? arg %) ["-J" "-R" "-C" "-O" "-M" "-A"])
              (recur (next command-line-args)
                     (update acc (get parse-opts->keyword (subs arg 0 2))
                             str (subs arg 2)))
              bool-opt-keyword (recur
                            (next command-line-args)
                            (assoc acc bool-opt-keyword true))
              string-opt-keyword (recur
                                  (nnext command-line-args)
                                  (assoc acc string-opt-keyword
                                         (second command-line-args)))
              (str/starts-with? arg "-S") (binding [*out* *err*]
                                            (println "Invalid option:" arg)
                                            (System/exit 1))
              (and
               (not (some acc [:main-aliases :all-aliases]))
               (or (= "-h" arg)
                   (= "--help" arg))) (assoc acc :help true)
              :else (assoc acc :args command-line-args)))
      acc)))

(when (:help args)
  (println help-text)
  (System/exit 0))

(def java-cmd "the java executable"
  (let [java-cmd (str/trim (shell-command ["type" "-p" "java"] {:to-string? true}))]
    (if (str/blank? java-cmd)
      (let [java-home (System/getenv "JAVA_HOME")]
        (if-not (str/blank? java-home)
          (let [f (io/file java-home "bin" "java")]
            (if (and (.exists f)
                     (.canExecute f))
              (.getCanonicalPath f)
              (throw (Exception. "Couldn't find 'java'. Please set JAVA_HOME."))))
          (throw (Exception. "Couldn't find 'java'. Please set JAVA_HOME."))))
      java-cmd)))

(def install-dir
  (let [clojure-on-path (str/trim (shell-command ["type" "-p" "clojure"] {:to-string? true}))
        f (io/file clojure-on-path)
        f (io/file (.getCanonicalPath f))
        parent (.getParent f)
        parent (.getParent (io/file parent))]
    parent))

(def tools-cp
  (let [files (.listFiles (io/file install-dir "libexec"))
        jar (some #(let [name (.getName %)]
                     (when (and (str/starts-with? name "clojure-tools")
                                (str/ends-with? name ".jar"))
                       %))
                  files)]
    (.getCanonicalPath jar)))

(def deps-edn (or (:deps-file args)
                  "deps.edn"))

(when (:resolve-tags args)
  (let [f (io/file deps-edn)]
    (if (.exists f)
      (do (shell-command [java-cmd "-Xms256m" "-classpath" tools-cp
                          "clojure.main" "-m" "clojure.tools.deps.alpha.script.resolve-tags"
                          "--deps-file=deps.edn"])
          (System/exit 0))
      (binding [*out* *err*]
        (println "deps.edn does not exist")
        (System/exit 1)))))

(def config-dir
  (or (System/getenv "CLJ_CONFIG")
      (when-let [xdg-config-home (System/getenv "XDG_CONFIG_HOME")]
        (.getPath (io/file xdg-config-home "clojure")))
      (.getPath (io/file (System/getProperty "user.home") ".clojure"))))

;; If user config directory does not exist, create it
(when-not (.exists (io/file config-dir))
  (.mkdirs config-dir))

(let [config-deps-edn (io/file config-dir "deps.edn")]
  (when-not (.exists config-deps-edn)
    (io/copy (io/file install-dir "example-deps.edn")
             config-deps-edn)))

;; Determine user cache directory
(def user-cache-dir
  (or (System/getenv "CLJ_CACHE")
      (when-let [xdg-config-home (System/getenv "XDG_CACHE_HOME")]
        (.getPath (io/file xdg-config-home "clojure")))
      (.getPath (io/file config-dir ".cpcache"))))

;; Chain deps.edn in config paths. repro=skip config dir
(def config-user
  (when-not (:repro args)
    (.getPath (io/file config-dir "deps.edn"))))

(def config-project deps-edn)
(def config-paths
  (if (:repro args)
    [(.getPath (io/file install-dir "deps.edn")) deps-edn]
    [(.getPath (io/file install-dir "deps.edn"))
     (.getPath (io/file config-dir "deps.edn"))
     deps-edn]))

(def config-str (str/join "," config-paths))

;; Determine whether to use user or project cache
(def cache-dir
  (if (.exists (io/file "deps.edn"))
    ".cpcache"
    user-cache-dir))

;; Construct location of cached classpath file
(def val*
  (str/join "|"
            (concat [(:resolve-aliases args)
                     (:classpath-aliases args)
                     (:all-aliases args)
                     (:jvm-aliases args)
                     (:main-aliases args)
                     (:deps-data args)]
                    (map (fn [config-path]
                           (if (.exists (io/file config-path))
                             config-path
                             "NIL"))
                         config-paths))))

(def ck (-> (shell-command ["cksum"] {:input val*
                                      :to-string? true})
            (str/split #" ")
            first))

(def libs-file (.getPath (io/file cache-dir (str ck ".libs"))))
(def cp-file (.getPath (io/file cache-dir (str ck ".cp"))))
(def jvm-file (.getPath (io/file cache-dir (str ck ".jvm"))))
(def main-file (.getPath (io/file cache-dir (str ck ".main"))))

(when (:verbose args)
  (println "version      =" project-version)
  (println "install_dir  =" install-dir)
  (println "config_dir   =" config-dir)
  (println "config_paths =" (str/join " " config-paths))
  (println "cache_dir    =" cache-dir)
  (println "cp_file      =" cp-file)
  (println))

(def stale "true if classpath file is stale"
  (or (:force args)
      (:trace args)
      (not (.exists (io/file cp-file)))
      (let [cp-file (io/file cp-file)]
        (some (fn [config-path]
                (let [f (io/file config-path)]
                  (or (not (.exists f))
                      (> (.lastModified f)
                         (.lastModified cp-file))))) config-paths))))

(def tools-args
  (when (or stale (:pom args))
    (cond-> []
      (not (str/blank? (:deps-data args)))
      (conj "--config-data" (:deps-data args))
      (:resolve-aliases args)
      (conj (str "-R" (:resolve-aliases args)))
      (:classpath-aliases args)
      (conj (str "-C" (:classpath-aliases args)))
      (:jvm-aliases args)
      (conj (str "-J" (:jvm-aliases args)))
      (:main-aliases args)
      (conj (str "-M" (:main-aliases args)))
      (:all-aliases args)
      (conj (str "-A" (:all-aliases args)))
      (:force-cp args)
      (conj "--skip-cp")
      (:trace args)
      (conj "--trace"))))

;;  If stale, run make-classpath to refresh cached classpath
(when (and stale (not (:describe args)))
  (when (:verbose args)
    (println "Refreshing classpath"))
  (shell-command (into [java-cmd "-Xms256m"
                        "-classpath" tools-cp
                        "clojure.main" "-m" "clojure.tools.deps.alpha.script.make-classpath2"
                        "--config-user" config-user
                        "--config-project" config-project
                        "--libs-file" libs-file
                        "--cp-file" cp-file
                        "--jvm-file" jvm-file
                        "--main-file" main-file]
                       tools-args)))

(def cp
  (cond (:describe args) nil
        (not (str/blank? (:force-cp args))) (:force-cp args)
        :else (slurp cp-file)))

(defn describe-line [[kw val]]
  (pr kw val ))

(defn describe [lines]
  (let [[first-line & lines] lines]
    (print "{") (describe-line first-line)
    (doseq [line lines]
      (print "\n ") (describe-line line))
    (println "}")))

(cond (:pom args)
      (shell-command [java-cmd "-Xms256m"
                      "-classpath" tools-cp
                      "clojure.main" "-m" "clojure.tools.deps.alpha.script.generate-manifest2"
                      "--config-user" config-user
                      "--config-project" config-project
                      "--gen=pom" (str/join " " tools-args)])
      (:print-classpath args)
      (println cp)
      (:describe args)
      (describe [[:version project-version]
                 [:config-files (filterv #(.exists (io/file %)) config-paths)]
                 [:config-user config-user]
                 [:config-project config-project]
                 [:install-dir install-dir]
                 [:cache-dir cache-dir]
                 [:force (str (:force args))]
                 [:repro (str (:repro args))]
                 [:resolve-aliases (str (:resolve-aliases args))]
                 [:classpath-aliases (str (:claspath-aliases args))]
                 [:jvm-aliases (str (:jvm-aliases args))]
                 [:main-aliases (str (:main-aliases args))]
                 [:all-aliases (str (:all-aliases args))]])
      (:tree args)
      (println (str/trim (shell-command [java-cmd "-Xms256m"
                                         "-classpath" tools-cp
                                         "clojure.main" "-m" "clojure.tools.deps.alpha.script.print-tree"
                                         "--libs-file" libs-file]
                                        {:to-string? true})))
      (:trace args)
      (println "Writing trace.edn")
      (:command args)
      (let [command (str/replace (:command args) "{{classpath}}" (str cp))
            main-cache-opts (when (.exists (io/file main-file))
                              (slurp main-file))
            command (str/replace command "{{main-opts}}" (str main-cache-opts))
            command (str/split command #"\s+")]
        (shell-command command))
      :else
      (let [jvm-cache-opts (when (.exists (io/file jvm-file))
                             (slurp jvm-file))
            main-cache-opts (when (.exists (io/file main-file))
                              (slurp main-file))
            main-cache-opts (when main-cache-opts (str/split main-cache-opts #"\s"))
            main-args (into (vector java-cmd jvm-cache-opts (:jvm-opts args)
                                    (str "-Dclojure.libfile=" libs-file) "-classpath" cp "clojure.main") main-cache-opts)
            main-args (filterv some? main-args)
            main-args (into main-args (:args args))]
        (shell-command main-args)))