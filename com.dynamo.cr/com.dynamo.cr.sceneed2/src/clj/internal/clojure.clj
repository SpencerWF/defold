(ns internal.clojure
  (:require [clojure.java.io :as io]
            [clojure.tools.namespace.file :refer [read-file-ns-decl]]
            [plumbing.core :refer [defnk]]
            [dynamo.file :as file]
            [dynamo.node :as n]
            [dynamo.system :as ds]
            [dynamo.types :as t]
            [internal.query :as iq]
            [eclipse.markers :as markers]
            [service.log :as log])
  (:import [org.eclipse.core.resources IFile IResource]
           [clojure.lang LineNumberingPushbackReader]))

(defn clojure-source?
  [^IResource resource]
  (and (instance? IFile resource)
       (.endsWith (.getName resource) ".clj")))

(defrecord UnloadableNamespace [ns-decl]
  t/IDisposable
  (dispose [this]
    (when (list? ns-decl)
      (remove-ns (second ns-decl)))))

(defn compile-at-load
  [graph self transaction]
  (when (some #{self} (:nodes-added transaction))
    (let [resource    (:resource self)
          ns-decl     (read-file-ns-decl resource)
          source-file (file/eclipse-file resource)]
      (markers/remove-markers source-file)
      (try
        (ds/in (iq/node-consuming graph self :self)
          (Compiler/load (io/reader resource) (file/local-path resource) (.getName source-file))
          (ds/set-property self :namespace (UnloadableNamespace. ns-decl)))
        (catch clojure.lang.Compiler$CompilerException compile-error
          (markers/compile-error source-file compile-error)
          {:compile-error (.getMessage (.getCause compile-error))})))))

(n/defnode ClojureSourceNode
  (property resource IFile)
  (property namespace UnloadableNamespace)

  (property triggers {:default [#'compile-at-load]}))

(defn on-load-code
  [resource _]
  (ds/add (make-clojure-source-node :resource resource)))
