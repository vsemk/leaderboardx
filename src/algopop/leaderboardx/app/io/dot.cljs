(ns algopop.leaderboardx.app.io.dot
  (:require [algopop.leaderboardx.app.io.common :as common]
            [algopop.leaderboardx.app.logging :as log]
            [algopop.leaderboardx.graph.graph :as graph]
            [clojure.string :as string]
            [clojure.walk :as walk]
            [instaparse.core :as insta]
            [clojure.set :as set]
            [cljs.tools.reader.edn :as edn]))

;; TODO: make an edn graph reader/writer

(def dot-gramma
  "see https://www.graphviz.org/doc/info/lang.html"
  "graph : <ws> [<'strict'> <ws>] ('graph' | 'digraph') <ws> [id <ws>] <'{'> stmt_list <'}'> <ws>
<stmt_list> : <ws> ( stmt <ws> [<';'> <ws>] )*
<stmt> : node | edge | attr | eq | subgraph
eq : id <ws> <'='> <ws> id
attr : <('graph' | 'node' | 'edge')> <ws> attr_list
<attr_list> : ( <'['> <ws> [a_list <ws>] <']'> <ws> )+
<a_list> : a ( <( ';' | ',' )> <ws> a )*
<a> : id <ws> <'='> <ws> value <ws>
edge : (node_id | subgraph) <ws> edge_RHS [<ws> attr_list]
<edge_RHS> : ( <edge_op> <ws> ( node_id | subgraph ) <ws> )+
edge_op : '->' | '--'
node : node_id [<ws> attr_list]
<node_id> : id [<ws> port]
port  : <':'> <ws> id [<ws> <':'> <ws> compass_pt] | <':'> <ws> compass_pt
compass_pt  : 'n' | 'ne' | 'e' | 'se' | 's' | 'sw' | 'w' | 'nw' | 'c' | '_'
subgraph  : ['subgraph' [<ws> id] <ws>] <'{'> <ws> stmt_list <ws> <'}'>
ws : #'\\s*'
<id> : literal | number | quoted | html
<value> : string | number | bool
<literal> : #'[a-zA-Z\\200-\\377][a-zA-Z\\200-\\377\\_\\d]*'
<quoted> : <'\"'> #'(?:[^\"\\\\]|\\\\.)*' <'\"'>
<number> : #'-?([\\.]\\d+|\\d+(\\.\\d*)?)'
<html> : #'<[^>]*>'
<string> : #'\"(?:[^\"\\\\]|\\\\.)*\"'
<bool> : 'true' | 'false'")

(def parse-dot
  (insta/parser dot-gramma))

(def flat-prefix
  {:node-types "n"
   :edge-types "e"})

(def prefix-flat
  (set/map-invert flat-prefix))

(def qualifier
  {"n" "node"
   "e" "edge"})

(defn qualify-keyword [q k]
  (keyword q (string/replace (name k) #"_" "-")))

(defn qualify-keywords [m q]
  (into {}
        (for [[k v] m]
          [(qualify-keyword q k)
           (edn/read-string v)])))

(defn nest-attrs [m]
  (reduce
    (fn nest-flat-kvs [acc [k v]]
      (let [[_ prefix k1 k2 :as match] (re-matches #"(.+)__(.+)__(.+)" k)
            category (prefix-flat prefix)]
        (when match
          (assoc-in acc [category k1 (qualify-keyword (qualifier prefix) k2)]
                    (edn/read-string v)))))
    {}
    m))

(defn as-map [attrs-body]
  (nest-attrs (apply hash-map attrs-body)))

(defn collect-statement [graph [statement-type & statement-body]]
  (condp = statement-type
    :node (let [[id & attrs] statement-body
                attr-map (qualify-keywords (apply hash-map attrs) "node")]
            (graph/add-node graph id attr-map))
    :edge (let [[from to & attrs] statement-body
                attr-map (qualify-keywords (apply hash-map attrs) "edge")]
            (graph/add-edge graph [from to] attr-map))
    :attr (merge graph (as-map statement-body))
    :eq graph
    :subgraph graph
    graph))

(defn read-graph [s]
  (let [ast (parse-dot s)]
    (if (insta/failure? ast)
      (log/error ast "Failed to parse dot file")
      (let [[_ _ & statements] ast
            [title] statements
            statements (if (string? title)
                         (rest statements)
                         statements)
            graph (cond-> (graph/create)
                          (string? title) (assoc :title title))]
        (reduce collect-statement graph statements)))))

;; TODO: pretty print
(defn maybe-attrs [label attrs]
  (when (seq (remove nil? (vals attrs)))
    (str label " ["
         (string/join ", " (for [[k v] attrs
                                 :when (not (nil? v))]
                            (str (pr-str (name k)) " = " (pr-str v))))
         "];")))

(defn edges [g]
  (for [[[from to] attrs] (sort (walk/stringify-keys (graph/edges g)))]
    (str (common/quote-escape from)
         " -> "
         (common/quote-escape to)
         (maybe-attrs "" attrs))))

(defn nodes [g]
  (for [[k attrs] (sort (walk/stringify-keys (graph/nodes g)))]
    (str (common/quote-escape k) (maybe-attrs "" attrs))))

(defn flat-attrs [g entity-type]
  (let [types (get g entity-type)
        label (flat-prefix entity-type)]
    (for [[t m] types
          [k v] m]
      [(str label "__" t "__" (string/replace (name k) #"-" "_")) v])))

;; TODO: why do sometimes ranks exist, sometimes not? not merging?
(defn write-graph [g]
  (str "digraph " (common/quote-escape (:title g "untitled")) " {" \newline
       (string/join \newline
         (concat
           ;; TODO: save :show-pageranks?
           [(maybe-attrs
              "graph"
              (concat
                (flat-attrs g :node-types)
                (flat-attrs g :edge-types)))]
           (nodes g)
           (edges g)))
       \newline "}"))
