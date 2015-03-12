(ns internal.node
  (:require [camel-snake-kebab :refer [->kebab-case]]
            [clojure.core.match :refer [match]]
            [clojure.set :as set]
            [dynamo.file :as file]
            [dynamo.system :as ds]
            [dynamo.types :as t]
            [dynamo.util :refer :all]
            [inflections.core :refer [plural]]
            [internal.cache :as c]
            [internal.either :as e]
            [internal.graph.dgraph :as dg]
            [internal.graph.lgraph :as lg]
            [internal.metrics :as metrics]
            [internal.property :as ip]
            [plumbing.core :refer [fnk defnk]]
            [plumbing.fnk.pfnk :as pf]))

(defn- resource?
  ([property-type]
    (some-> property-type t/property-tags (->> (some #{:dynamo.property/resource}))))
  ([node label]
    (some-> node t/node-type t/properties' label resource?)))

(defn- pfnk? [f] (contains? (meta f) :schema))

(def ^:private ^java.util.concurrent.atomic.AtomicInteger
     nextid (java.util.concurrent.atomic.AtomicInteger. 1000000))

(defn tempid [] (- (.getAndIncrement nextid)))

;; ---------------------------------------------------------------------------
;; New evaluation
;; ---------------------------------------------------------------------------
(defn abort
  [why _ in-production node-id label & _]
  (throw (ex-info (str why " Trying to produce " node-id ", " label)
                  {:node-id node-id :label label :in-production in-production})))

(def not-found (partial abort "No such property, input or output."))
(def cycle-detected (partial abort "Production cycle detected."))

(defn- chain-eval
  "Start a chain of evaluators"
  [evaluators graph in-production node-id label]
  (let [chain-head    (first evaluators)
        chain-tail    (next evaluators)]
    (chain-head graph in-production node-id label evaluators chain-tail)))

(defn- continue
  "Continue evaluation with the next link in the chain."
  [graph in-production node-id label chain-head chain-next]
  ((first chain-next) graph in-production node-id label chain-head (next chain-next)))

(def ^:private multivalued?  vector?)
(def ^:private exists?       (comp not nil?))
(def ^:private has-property? contains?)
(defn- has-output? [node label] (some-> node t/transforms label boolean))
(def ^:private first-source (comp first lg/sources))
(defn- currently-producing [in-production node-id label] (= (last in-production) [node-id label]))

(defn- evaluate-input-internal
  "Gather an named argument for a production function. Depending on
  the name, this could mean one of five things:

  1. It names an input label. Evaluate the sources to get their values.
  2. It names a property of the node itself. Return the value of the property.
  3. It names another output of the same node. (The node has functions that depend on other outputs of the same node.) Evaluate that output first, then use its value.
  4. It is the special name :this. Use the entire node as an argument.
  5. It is the special name :g. Use the entire graph as an argument."
  [graph in-production node-id label chain-head]
  (let [node             (dg/node graph node-id)
        input-schema     (some-> node t/input-types label)
        output-transform (some-> node t/transforms  label)]
    (cond
      (and (has-output? node label) (not (currently-producing in-production node-id label)))
      (chain-eval chain-head graph in-production node-id label)

      (multivalued? input-schema)
      (reduce
       (fn [input-vals source]
         (e/bind
          (conj
           (e/result input-vals)
           (e/result
            (chain-eval chain-head graph in-production (first source) (second source))))))
       (e/bind [])
       (lg/sources graph node-id label))

      (exists? input-schema)
      (if-let [source (first-source graph node-id label)]
        (chain-eval chain-head graph in-production (first source) (second source))
        (e/bind nil))

      (has-property? node label)
      (e/bind (get node label))

      (= :g label)
      (e/bind graph)

      (= :this label)
      (e/bind node))))

(defn- collect-inputs
  "Return a map of all inputs needed for the input-schema."
  [graph in-production node-id label chain-head input-schema]
  (reduce-kv
   (fn [inputs desired-input-name desired-input-schema]
     (assoc inputs desired-input-name
            (evaluate-input-internal graph in-production node-id desired-input-name chain-head)))
   {}
   (dissoc input-schema t/Keyword)))

(defn- produce-with-schema
  "Helper function: if the production function has schema information,
  use it to collect the required arguments."
  [graph in-production node-id label chain-head production-fn]
  (let [input-schema (pf/input-schema production-fn)]
    (e/bind
     (production-fn
      (map-vals e/result
                (collect-inputs graph in-production node-id label chain-head input-schema))))))

(defn apply-transform-or-substitute
  "Attempt to invoke the production function for an output. If it
  fails, call the transform's substitute value function."
  [graph in-production node-id label chain-head transform]
  (let [producer (:production-fn transform)
        fallback (:substitute-value-fn transform)]
    (e/or-else
     (cond
       (pfnk? producer)
       (produce-with-schema graph in-production node-id label chain-head producer)

       (fn? producer)
       (e/bind (producer (dg/node graph node-id) graph))

       :else
       (e/bind producer))
     (fn [e]
       (cond
         (fn? fallback)
         (fallback {:exception e :node-id node-id :label label :graph graph})

         fallback
         fallback

         :else
         (throw e))))))

(defn mark-in-production
  "This evaluation function checks whether the requested value is
  already being computed. If so, it means there is a cycle in the
  graph somewhere, so we must abort."
  [graph in-production node-id label chain-head chain-next]
  (if (some #{[node-id label]} in-production)
    (cycle-detected graph in-production node-id label)
    (continue graph (conj in-production [node-id label]) node-id label chain-head chain-next)))

(defn evaluate-production-function
  "This evaluation function looks for a production function to create
  the output on the node. If there is a production function, it
  gathers the arguments needed for the production function and invokes
  it."
  [graph in-production node-id label chain-head chain-next]
  (if-let [transform (some->> node-id (dg/node graph) t/transforms label)]
    (apply-transform-or-substitute graph in-production node-id label chain-head transform)
    (continue graph in-production node-id label chain-head chain-next)))

(defn lookup-property
  "This evaluation function looks for a property on the node. If there
  is no such property, evaluation continues with the rest of the
  chain."
  [graph in-production node-id label chain-head chain-next]
  (let [node (dg/node graph node-id)]
    (if (has-property? node label)
      (e/bind (get node label))
      (continue graph in-production node-id label chain-head chain-next))))

(defn read-input
  "This evaluation function looks for an input to the node. If the
  input exists and is multivalued, then all the incoming values are
  conjed together. If the input does not exist, evaluation continues
  with the rest of the chain."
  [graph in-production node-id label chain-head chain-next]
  (let [node             (dg/node graph node-id)
        input-schema     (some-> node t/input-types label)]
    (cond
      (multivalued? input-schema)
      (e/bind
       (reduce
        (fn [input-vals source]
          (conj
           (e/result input-vals)
           (e/result
            (chain-eval chain-head graph in-production (first source) (second source)))))
        (e/bind [])
        (lg/sources graph node-id label)))

      (exists? input-schema)
      (if-let [source (first-source graph node-id label)]
        (chain-eval chain-head graph in-production (first source) (second source))
        (e/bind nil))

      :else
      (continue graph in-production node-id label chain-head chain-next))))

(def world-evaluation-chain
  [mark-in-production
   evaluate-production-function
   lookup-property
   read-input
   not-found])

(defn- delta [deltas node-id label v] (swap! deltas conj [[node-id label] v]) v)
(defn- local [deltas node-id label]   (first (keep (fn [[i l v]] (when (and (= node-id i) (= l label)) v)) @deltas)))

(defn local-deltas
  "Returns an evaluator. The evaluator is an evaluation function that
  looks for any values that have already been produced during this
  computation.

  If no local delta is found, the evaluator continues the
  chain. Whatever the chain returns gets recorded as a local delta for
  possible use during the remainder of the evaluation."
  [deltas graph in-production node-id label chain-head chain-next]
  (if-some [r (local deltas node-id label)]
    r
    (delta deltas node-id label
           (continue graph in-production node-id label chain-head chain-next))))

(defn- hit [hits node-id label v] (swap! hits conj [node-id label]) v)

(defn cache-lookup
  "Returns an evaluation function.

  The evaluator collects records of values that were 'hits' in the
  atom of the same name."
  [snapshot hits graph in-production node-id label chain-head chain-next]
  (if-some [v (get snapshot [node-id label])]
    (hit hits node-id label v)
    (continue graph in-production node-id label chain-head chain-next)))

(defn fork
  "Return a thunk for an evaluation chain. When invoked during evaluation,
  it will call `test` with 6 arguments: the graph, the in-production
  set, the node-id and label, the head of the evaluation chain and the
  rest of the current chain.

  The rest of the current chain should be 2 items
  long. Each one is a nested sequence of evaluators."
  [test]
  (fn [graph in-production node-id label chain-head chain-next]
    (assert (= 2 (count chain-next)))
    (let [[consequent alternate & _] chain-next
          branch?                    (test graph node-id label)]
      (chain-eval (if branch? consequent alternate) graph in-production node-id label))))

(defn- cacheable?
  "Check the node type to see if the given output should be cached once computed."
  [graph node-id label]
  ((t/cached-outputs (dg/node graph node-id)) label))

(def fork-cacheable (fork cacheable?))

(defn node-value
  "Get a value, possibly cached, from a node. This is the entry point to the \"plumbing\".
If the value is cacheable and exists in the cache, then return that value. Otherwise,
produce the value by gathering inputs to call a production function, invoke the function,
maybe cache the value that was produced, and return it."
  [graph cache node-id label]
  (let [hits (atom [])
        deltas (atom [])
        evaluators            [fork-cacheable
                               (list* (partial local-deltas deltas)
                                      (partial cache-lookup (c/cache-snapshot cache) hits)
                                      world-evaluation-chain)
                               world-evaluation-chain]
        result                (chain-eval evaluators graph [] node-id label)]
    (do
      (c/cache-hit cache @hits)
      (c/cache-encache cache @deltas))
    (e/result result)))

(defn get-inputs
  [graph node label]
  (map #(dg/node graph (first %)) (lg/sources graph (:_id node) label)))

;; ---------------------------------------------------------------------------
;; Definition handling
;; ---------------------------------------------------------------------------
(defrecord NodeTypeImpl
  [name supertypes interfaces protocols method-impls triggers transforms transform-types properties inputs injectable-inputs cached-outputs event-handlers output-dependencies]

  t/NodeType
  (supertypes           [_] supertypes)
  (interfaces           [_] interfaces)
  (protocols            [_] protocols)
  (method-impls         [_] method-impls)
  (triggers             [_] triggers)
  (transforms'          [_] transforms)
  (transform-types'     [_] transform-types)
  (properties'          [_] properties)
  (inputs'              [_] (map-vals #(if (satisfies? t/PropertyType %) (t/property-value-type %) %) inputs))
  (injectable-inputs'   [_] injectable-inputs)
  (outputs'             [_] (set (keys transforms)))
  (cached-outputs'      [_] cached-outputs)
  (event-handlers'      [_] event-handlers)
  (output-dependencies' [_] output-dependencies))

(defmethod print-method NodeTypeImpl
  [^NodeTypeImpl v ^java.io.Writer w]
  (.write w (str "<NodeTypeImpl{:name " (:name v) ", :supertypes " (mapv :name (:supertypes v)) "}>")))

(defn- from-supertypes [local op]                (map op (:supertypes local)))
(defn- combine-with    [local op zero into-coll] (op (reduce op zero into-coll) local))

(defn- invert-map
  [m]
  (apply merge-with into
         (for [[k vs] m
               v vs]
           {v #{k}})))

(defn inputs-for
  [transform]
  (let [production-fn (-> transform :production-fn)]
    (if (pfnk? production-fn)
      (into #{} (keys (dissoc (pf/input-schema production-fn) t/Keyword :this :g)))
      #{})))

(defn dependency-seq
  ([desc inputs]
    (dependency-seq desc #{} inputs))
  ([desc seen inputs]
    (mapcat
      (fn [x]
        (if (not (seen x))
          (if-let [recursive (get-in desc [:transforms x])]
            (dependency-seq desc (conj seen x) (inputs-for recursive))
            #{x})
          seen))
      inputs)))

(defn description->output-dependencies
   [{:keys [transforms properties] :as description}]
   (let [outs (dissoc transforms :self)
         outs (zipmap (keys outs) (map #(dependency-seq description (inputs-for %)) (vals outs)))
         outs (assoc outs :properties (set (keys properties)))]
     (invert-map outs)))

(defn attach-output-dependencies
  [description]
  (assoc description :output-dependencies (description->output-dependencies description)))

(def ^:private map-merge (partial merge-with merge))

(defn make-node-type
  "Create a node type object from a maplike description of the node.
This is really meant to be used during macro expansion of `defnode`,
not called directly."
  [description]
  (-> description
    (update-in [:inputs]              combine-with merge      {} (from-supertypes description t/inputs'))
    (update-in [:injectable-inputs]   combine-with set/union #{} (from-supertypes description t/injectable-inputs'))
    (update-in [:properties]          combine-with merge      {} (from-supertypes description t/properties'))
    (update-in [:transforms]          combine-with merge      {} (from-supertypes description t/transforms'))
    (update-in [:transform-types]     combine-with merge      {} (from-supertypes description t/transform-types'))
    (update-in [:cached-outputs]      combine-with set/union #{} (from-supertypes description t/cached-outputs'))
    (update-in [:event-handlers]      combine-with set/union #{} (from-supertypes description t/event-handlers'))
    (update-in [:interfaces]          combine-with set/union #{} (from-supertypes description t/interfaces))
    (update-in [:protocols]           combine-with set/union #{} (from-supertypes description t/protocols))
    (update-in [:method-impls]        combine-with merge      {} (from-supertypes description t/method-impls))
    (update-in [:triggers]            combine-with map-merge  {} (from-supertypes description t/triggers))
    attach-output-dependencies
    map->NodeTypeImpl))

(defn attach-supertype
  "Update the node type description with the given supertype."
  [description supertype]
  (assoc description :supertypes (conj (:supertypes description []) supertype)))

(defn attach-input
  "Update the node type description with the given input."
  [description label schema flags]
  (cond->
    (assoc-in description [:inputs label] schema)

    (some #{:inject} flags)
    (update-in [:injectable-inputs] #(conj (or % #{}) label))))

(defn- abstract-function
  [label type]
  (fn [this g]
    (throw (AssertionError.
             (format "Node %d does not supply a production function for the abstract '%s' output. Add (output %s %s your-function) to the definition of %s"
               (:_id this) label
               label type this)))))

(defn attach-output
  "Update the node type description with the given output."
  [description label schema properties options & [args]]
  (cond-> (update-in description [:transform-types] assoc label schema)

    (:substitute-value options)
    (update-in [:transforms] assoc-in [label :substitute-value-fn] (:substitute-value options))

    (:cached properties)
    (update-in [:cached-outputs] #(conj (or % #{}) label))

    (:abstract properties)
    (update-in [:transforms] assoc-in [label :production-fn] (abstract-function label schema))

    (not (:abstract properties))
    (update-in [:transforms] assoc-in [label :production-fn] args)))

(defn attach-property
  "Update the node type description with the given property."
  [description label property-type passthrough]
  (cond-> (update-in description [:properties] assoc label property-type)

    (resource? property-type)
    (assoc-in [:inputs label] property-type)

    true
    (update-in [:transforms] assoc-in [label :production-fn] passthrough)

    true
    (update-in [:transform-types] assoc label (:value-type property-type))))

(defn attach-event-handler
  "Update the node type description with the given event handler."
  [description label handler]
  (assoc-in description [:event-handlers label] handler))

(defn attach-trigger
  "Update the node type description with the given trigger."
  [description label kinds action]
  (reduce
    (fn [description kind] (assoc-in description [:triggers kind label] action))
    description
    kinds))

(defn attach-interface
  "Update the node type description with the given interface."
  [description interface]
  (update-in description [:interfaces] #(conj (or % #{}) interface)))

(defn attach-protocol
  "Update the node type description with the given protocol."
  [description protocol]
  (update-in description [:protocols] #(conj (or % #{}) protocol)))

(defn attach-method-implementation
  "Update the node type description with the given function, which
must be part of a protocol or interface attached to the description."
  [description sym argv fn-def]
  (assoc-in description [:method-impls sym] [argv fn-def]))

(def ^:private property-flags #{:cached :abstract})
(def ^:private option-flags #{:substitute-value})

(defn parse-output-options [args]
  (loop [properties #{}
         options {}
         args args]
    (if-let [[arg & remainder] (seq args)]
      (cond
        (contains? property-flags arg) (recur (conj properties arg) options remainder)
        (contains? option-flags arg)   (do (assert remainder (str "Expected value for option " arg))
                                         (recur properties (assoc options arg (first remainder)) (rest remainder)))
        :else [properties options args])
      [properties options args])))

(defn classname
  [^Class c]
  (.getName c))

(defn fqsymbol
  [s]
  (assert (symbol? s))
  (let [{:keys [ns name]} (meta (resolve s))]
    (symbol (str ns) (str name))))

(def ^:private valid-trigger-kinds #{:added :deleted :property-touched :input-connections})

(defn- node-type-form
  "Translate the sugared `defnode` forms into function calls that
build the node type description (map). These are emitted where you invoked
`defnode` so that symbols and vars resolve correctly."
  [form]
  (match [form]
    [(['inherits supertype] :seq)]
    `(attach-supertype ~supertype)

    [(['input label schema & flags] :seq)]
    `(attach-input ~(keyword label) ~schema #{~@flags})

    [(['output label schema & remainder] :seq)]
    (let [[properties options args] (parse-output-options remainder)]
      `(attach-output ~(keyword label) ~schema ~properties ~options ~@args))

    [(['property label tp & options] :seq)]
    `(attach-property ~(keyword label) ~(ip/property-type-descriptor label tp options) (fnk [~label] ~label))

    [(['on label & fn-body] :seq)]
    `(attach-event-handler ~(keyword label) (fn [~'self ~'event] (dynamo.graph/transactional ~@fn-body)))

    [(['trigger label & rest] :seq)]
    (let [kinds (vec (take-while keyword? rest))
          action (drop-while keyword? rest)]
      (assert (every? valid-trigger-kinds kinds) (apply str "Invalid trigger kind. Valid trigger kinds are: " (interpose ", " valid-trigger-kinds)))
      `(attach-trigger ~(keyword label) ~kinds ~@action))

    ;; Interface or protocol function
    [([nm [& argvec] & remainder] :seq)]
    `(attach-method-implementation '~nm '~argvec (fn ~argvec ~@remainder))

    [impl :guard symbol?]
    `(cond->
        (class? ~impl)
        (attach-interface (symbol (classname ~impl)))

        (not (class? ~impl))
        (attach-protocol (fqsymbol '~impl)))))

(defn node-type-sexps
  "Given all the forms in a defnode macro, emit the forms that will build the node type description."
  [symb forms]
  (list* `-> {:name (str symb)}
    (map node-type-form forms)))

(defn defaults
  "Return a map of default values for the node type."
  [node-type]
  (map-vals t/property-default-value (t/properties' node-type)))

(defn classname-for [prefix] (symbol (str prefix "__")))

(defn- state-vector
  [node-type]
  (mapv (comp symbol name) (keys (t/properties' node-type))))

(defn- message-processor
  [node-type-name node-type]
  (when (not-empty (t/event-handlers' node-type))
    `[t/MessageTarget
      (dynamo.types/process-one-event
       [~'self ~'event]
       (case (:type ~'event)
         ~@(mapcat (fn [e] [e `((get (t/event-handlers' ~node-type-name) ~e) ~'self ~'event)]) (keys (t/event-handlers' node-type)))
         nil))]))

(defn- node-record-sexps
  [record-name node-type-name node-type]
  `(defrecord ~record-name ~(state-vector node-type)
     t/Node
     (node-type           [_]    ~node-type-name)
     (inputs              [_]    (set (keys (t/inputs' ~node-type-name))))
     (input-types         [_]    (t/inputs' ~node-type-name))
     (injectable-inputs   [_]    (t/injectable-inputs' ~node-type-name))
     (outputs             [_]    (t/outputs' ~node-type-name))
     (transforms          [_]    (t/transforms' ~node-type-name))
     (transform-types     [_]    (t/transform-types' ~node-type-name))
     (cached-outputs      [_]    (t/cached-outputs' ~node-type-name))
     (properties          [_]    (t/properties' ~node-type-name))
     (output-dependencies [_]    (t/output-dependencies' ~node-type-name))
     ~@(t/interfaces node-type)
     ~@(t/protocols node-type)
     ~@(map (fn [[fname [argv _]]] `(~fname ~argv ((second (get (t/method-impls ~node-type-name) '~fname)) ~@argv))) (t/method-impls node-type))
     ~@(message-processor node-type-name node-type)))

(defn define-node-record
  "Create a new class for the node type. This builds a defrecord with
the node's properties as fields. The record will implement all the interfaces
and protocols that the node type requires."
  [record-name node-type-name node-type]
  (eval (node-record-sexps record-name node-type-name node-type)))

(defn- interpose-every
  [n elt coll]
  (mapcat (fn [l r] (conj l r)) (partition-all n coll) (repeat elt)))

(defn- print-method-sexps
  [record-name node-type-name node-type]
  (let [node (vary-meta 'node assoc :tag (resolve record-name))]
    `(defmethod print-method ~record-name
       [~node w#]
       (.write
         ^java.io.Writer w#
         (str "#" '~node-type-name "{:_id " (:_id ~node)
           ~@(interpose-every 3 ", " (mapcat (fn [prop] `[~prop " " (pr-str (get ~node ~prop))]) (keys (t/properties' node-type))))
           "}")))))

(defn define-print-method
  "Create a nice print method for a node type. This avoids infinitely recursive output in the REPL."
  [record-name node-type-name node-type]
  (eval (print-method-sexps record-name node-type-name node-type)))

;; ---------------------------------------------------------------------------
;; Dependency Injection
;; ---------------------------------------------------------------------------

(defn compatible?
  [[out-node out-label out-type in-node in-label in-type]]
  (cond
   (and (= out-label in-label) (t/compatible? out-type in-type false))
   [out-node out-label in-node in-label]

   (and (= (plural out-label) in-label) (t/compatible? out-type in-type true))
   [out-node out-label in-node in-label]))

(defn injection-candidates
  [targets nodes]
  (into #{}
     (keep compatible?
        (for [target  targets
              i       (t/injectable-inputs target)
              :let    [i-l (get (t/input-types target) i)]
              node    nodes
              [o o-l] (t/transform-types node)]
            [node o o-l target i i-l]))))

;; ---------------------------------------------------------------------------
;; Intrinsics
;; ---------------------------------------------------------------------------
(defn- gather-property [this prop]
  (let [type     (-> this t/properties prop)
        value    (get this prop)
        problems (t/property-validate type value)]
    {:node-id             (:_id this)
     :value               value
     :type                type
     :validation-problems problems}))

(defnk gather-properties :- t/Properties
  "Production function that delivers the definition and value
for all properties of this node."
  [this]
  (let [property-names (-> this t/properties keys)]
    (zipmap property-names (map (partial gather-property this) property-names))))

(defn- ->vec [x] (if (coll? x) (vec x) (if (nil? x) [] [x])))

(defn- resources-connected
  [transaction self prop]
  (let [graph (ds/in-transaction-graph transaction)]
    (vec (lg/sources graph (:_id self) prop))))

(defn lookup-node-for-filename
  [transaction parent self filename]
  (or
    (get-in transaction [:filename-index filename])
    (if-let [added-this-txn (first (filter #(= filename (:filename %)) (ds/transaction-added-nodes transaction)))]
      added-this-txn
      (t/lookup parent filename))))

(defn decide-resource-handling
  [transaction parent self surplus-connections prop project-path]
  (if-let [existing-node (lookup-node-for-filename transaction parent self project-path)]
    (if (some #{[(:_id existing-node) :content]} surplus-connections)
      [:existing-connection existing-node]
      [:new-connection existing-node])
    [:new-node nil]))

(defn remove-vestigial-connections
  [transaction self prop surplus-connections]
  (doseq [[n l] surplus-connections]
    (ds/disconnect {:_id n} l self prop))
  transaction)

(defn- ensure-resources-connected
  [transaction parent self prop]
  (loop [transaction         transaction
         project-paths       (map #(file/make-project-path parent %) (->vec (get self prop)))
         surplus-connections (resources-connected transaction self prop)]
    (if-let [project-path (first project-paths)]
      (let [[handling existing-node] (decide-resource-handling transaction parent self surplus-connections prop project-path)]
        (cond
          (= :new-node handling)
          (let [new-node (ds/in parent (ds/add (t/node-for-path parent project-path)))]
            (ds/connect new-node :content self prop)
            (recur
              (update-in transaction [:filename-index] assoc project-path new-node)
              (next project-paths)
              surplus-connections))

          (= :new-connection handling)
          (do
            (ds/connect existing-node :content self prop)
            (recur transaction (next project-paths) surplus-connections))

          (= :existing-connection handling)
          (recur transaction (next project-paths) (remove #{[(:_id existing-node) :content]} surplus-connections))))
      (remove-vestigial-connections transaction self prop surplus-connections))))

(defn connect-resource
  [transaction graph self label kind properties-affected]
  (let [parent (ds/parent graph self)]
    (reduce
      (fn [transaction prop]
        (when (resource? self prop)
          (ensure-resources-connected transaction parent self prop))
        transaction)
      transaction
      properties-affected)))

(def node-intrinsics
  [(list 'output 'self `t/Any `(fnk [~'this] ~'this))
   (list 'output 'properties `t/Properties `gather-properties)])
