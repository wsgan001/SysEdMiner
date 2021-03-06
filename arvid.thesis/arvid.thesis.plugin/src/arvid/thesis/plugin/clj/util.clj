(ns arvid.thesis.plugin.clj.util
  (:require [damp.ekeko.jdt.astnode :as astnode])
  (:require [damp.ekeko.snippets.operatorsrep :as operatorsrep])
  (:require [damp.ekeko.snippets.snippetgroup :as snippetgroup])
  (:require [clojure.java.shell :as sh])
  (:import [java.util List])
  (:import (java.util.concurrent TimeoutException TimeUnit FutureTask))
  (import org.eclipse.jdt.core.JavaCore)
  (import org.eclipse.jdt.core.dom.ASTParser)
  (import org.eclipse.jdt.core.dom.AST))

(defn 
  ekekoConsolePrintln 
  "Print given arguments to the Ekeko console"
  [& args] 
  (.println (.getConsoleStream (arvid.thesis.plugin.ThesisPlugin/getDefault)) (apply str args)))

(defn find-first-index 
  "Get the index of the first element satisfying 'pred' in 'coll', in linear time. nil otherwise." 
  [pred coll] 
  (let [indices (keep-indexed (fn [idx x] (when (pred x) idx)) coll)]
    (if (= (count indices) 1)
        (first indices)
        nil)))

(defn node-to-oneliner
  [node]
  (if (nil? node)
      "nil"
      (let [to-stringed-node (.replace (.toString node) "\n" " ")]
        (str (.substring to-stringed-node 0 (min 130 (.length to-stringed-node)))
             (if (> (.length to-stringed-node) 130) "..." "")))))

(defn
  get-path-between-nodes
  [node container-node]
  (loop [current-path '()
         current node]
    (if (or (nil? current)
            (= container-node current))
        current-path
        (let [parent-of-current (astnode/owner current)
              next-path (if parent-of-current
                            (let [owner-property (astnode/owner-property current)]
                              (if (not (astnode/property-descriptor-list? owner-property))
                                  (cons (astnode/ekeko-keyword-for-property-descriptor owner-property) current-path)
                                  (let [lst (astnode/node-property-value|reified parent-of-current owner-property)
                                         lst-raw (astnode/value-unwrapped lst)]
                                         (cons (astnode/ekeko-keyword-for-property-descriptor owner-property) 
                                               (cons (.indexOf ^List lst-raw current)
                                                     current-path)))))
                            current-path)]
            (recur next-path parent-of-current)))))

(defn parse-node-path 
  "Parses a node path (in String form) to a list of [property index] pairs
   For example, :types 0 :bodyDeclarations 71 becomes [[:types 0] [:bodyDeclarations 1]]
   If a property isn't a list-property, its index is nil."
  [path]
  (loop [p path
         parsed []]
    (let [colon-idx (clojure.string/index-of (subs p 1) ":")
          last-match (nil? colon-idx)
          element (if last-match
                    p
                    (subs p 0 colon-idx))
          
          element-split (clojure.string/split element #" ")
          idx (if (not (nil? (second element-split))) (Integer/parseInt (second element-split)))
          [property idx] [(keyword (subs (first element-split) 1)) idx]
          new-p (conj parsed [property idx])]
      (if last-match 
        new-p (recur (subs p (inc colon-idx)) new-p)))))

(defn node-property-value [node property-key]
  (let [property-name (name property-key)
        all-props (astnode/node-property-descriptors node)
        prop (some (fn [prop] (if (= property-name (.getId prop)) prop))
                   all-props)]
    (.getStructuralProperty node prop)))

(defn follow-node-path
  "Follow a path produced by get-path-between-nodes, starting from 'node'."
  [node path]
;  (println path)
  (let [[property idx] (first path)
        child-tmp (node-property-value node property) ;(astnode/node-propertykeyword-value|reified node property)
;        _ (inspector-jay.core/inspect node)
;        _ (println (.getProperty node "types"))
        child (if (nil? idx)
                child-tmp
                (.get child-tmp idx)
;                (let [raw-lst (astnode/value-unwrapped child-tmp)]
;                  (.get raw-lst idx))
                )]
    (if (empty? (rest path))
      child
      (recur child (rest path)))))

(defn
  tree-dft
  ([node node-processor]
    (tree-dft node node-processor node-processor node-processor node-processor))
  ([node ast-processor list-processor primitive-processor nil-processor]
   (cond (astnode/nilvalue? node)
           (nil-processor node)
	       (astnode/primitivevalue? node)
           (primitive-processor node)
	       (astnode/ast? node)
           (do (doall (map (fn [propval] (tree-dft propval ast-processor list-processor primitive-processor nil-processor)) 
                           (astnode/node-propertyvalues node)))
               (ast-processor node))
	       (astnode/lstvalue? node) 
           (do (doall (map (fn [listitem] (tree-dft listitem ast-processor list-processor primitive-processor nil-processor)) 
                           (astnode/value-unwrapped node)))
               (list-processor node))
         :else
           (throw (Exception. "Woops. What's happening here?" node)))))

(defn
  source-to-ast
  "Parses a String of source code into a Java AST"
  [source]
  (let [parser (ASTParser/newParser AST/JLS8)
        options (JavaCore/getOptions)]
    (JavaCore/setComplianceOptions JavaCore/VERSION_1_5 options)
    (.setKind parser ASTParser/K_COMPILATION_UNIT)
    (.setSource parser (.toCharArray source))
    (.setCompilerOptions parser options)
    (.createAST parser nil)))

(defn time-elapsed 
  "Returns the number of milliseconds that have passed since start-time.
   Note that start-time must be obtained via (.System (nanoTime))"
  [start-time]
  (/ (double (- (. System (nanoTime)) start-time)) 1000000.0))

; Source: https://github.com/flatland/clojail/blob/master/src/clojail/core.clj#L40
(defn thunk-timeout
  "Takes a function and an amount of time to wait for thse function to finish
   executing."
  ([thunk ms]
     (thunk-timeout thunk ms nil))
  ([thunk time tg]
     (let [task (FutureTask. thunk)
           thr (if tg (Thread. tg task) (Thread. task))]
       (try
         (.start thr)
         (.get task time TimeUnit/MILLISECONDS)
         (catch TimeoutException e
           (.cancel task true)
           (.stop thr) 
           (throw (TimeoutException. "Execution timed out.")))
         (catch Exception e
           (.cancel task true)
           (.stop thr) 
           (throw e))))))

(defmacro with-timeout
  "Apply this macro to an expression and an exception is thrown if it takes longer than a given time to evaluate the expression"
  ([time body]
  `(thunk-timeout (fn [] ~body) ~time))
  ([time body tg]
  `(thunk-timeout (fn [] ~body) ~time ~tg)))

(defn open-in-sublime [relative-path directory]
  (sh/sh "open" "-a" "/Applications/Sublime Text 2.app" relative-path :dir directory))

(defn get-file-in-commit 
  "Get the version of a file at a certain commit"
  [repo-path file-path commit]
  (:out
    (sh/sh "git" "show" (str commit ":" file-path) :dir repo-path)))

(defn search-all-commits 
  "Search the entire project history for a certain string that is inserted/deleted
   Returns a list of commits where the string was found"
  [repo-path string]
  (:out 
    (sh/sh "bash" "-c" (str "git log -S\"" string "\" | grep ^commit*") :dir repo-path)))

(defn get-file-diff 
  "Get the version of a file at a certain commit"
  [repo-path file-path commit]
  (:out
    (sh/sh "git" "diff" "-U10" (str commit "^") commit "--" file-path  :dir repo-path)))

;(println (get-file-diff "/Volumes/Disk Image/tpv/tpv-extracted/tpvision/common/app/quicksearchbar" "src/org/droidtv/quicksearchbox/activities/TPVisionSearchActivity.java" "1a3ef09582f78ba0dcd21a91e23e366926fac0f4"))

(defn append
  "Appends a line of text to a file"
  [filepath text]
  (spit filepath (str text "\n") :append true :create true))

(defn average
  [numbers]
  (if (= (count numbers) 0)
    0
    (/ (apply + numbers) (count numbers))))

(defn delete-recursively 
  "Delete a folder recursively"
  [fname]
  (let [func (fn [func f]
               (when (.isDirectory f)
                 (doseq [f2 (.listFiles f)]
                   (func func f2)))
               (clojure.java.io/delete-file f))]
    (func func (clojure.java.io/file fname))))