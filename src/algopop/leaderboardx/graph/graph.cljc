(ns algopop.leaderboardx.graph.graph
  (:require
    [clojure.set :as set]
    [algopop.leaderboardx.graph.pagerank :as pagerank]
    [loom.graph :as lg]
    [loom.attr :as la]))

;; TODO: defaults for things like edge/color regardless of type

(defonce edge-types
  {"likes" {:edge/color "#9ecae1"
            :edge/label ""
            :edge/dasharray ""
            :edge/distance 50
            :edge/weight 1
            :edge/negate false}
   "dislikes" {:edge/color "red"
               :edge/label ""
               :edge/dasharray ""
               :edge/distance 100
               :edge/weight 1
               :edge/negate true}})

(defonce node-types
  {"person" {:node/shape "circle"
             :node/text ""
             :node/tags ""
             :node/size 1
             :node/name-size 1
             :node/color "white"}})

(defn add-weight [g [a b] w]
  (assoc-in g [:adj a b] w))

(defn add-attr [g id k v]
  (if (= k :edge/weight)
    (let [[a b] id]
      (add-weight g [a b] v))
    (la/add-attr g id k v)))

(defn update-attr [g id k f & args]
  (la/add-attr g id k (apply f (la/attr g id k) args)))

(defn remove-attr [g id k]
  (la/remove-attr g id k))

(defn weight [g edge]
  (lg/weight g edge))

(defn add-attrs [g [id attrs]]
  (reduce
    (fn [acc2 [k v]]
      (add-attr acc2 id k v))
    g
    attrs))

(defn add-node [g id attrs]
  (add-attrs (lg/add-nodes g id) [id attrs]))

(defn add-edge [g id attrs]
  (add-attrs (lg/add-edges g id) [id attrs]))

(defn add-many-attrs [g entities]
  (reduce add-attrs g entities))

;; TODO: with-ranks
(defn create
  ([] (assoc (lg/weighted-digraph)
        :title "untitled"
        ;; TODO: not always appropriate when loading from a file (might have been removed)
        :node-types node-types
        :edge-types edge-types))
  ([nodes edges]
   (-> (create)
       (lg/add-nodes* (keys nodes))
       (add-many-attrs nodes)
       (lg/add-edges* (keys edges))
       (add-many-attrs edges))))

(defn nodes [g]
  (into {}
        (for [node-id (lg/nodes g)]
          [node-id (or (la/attrs g node-id) {})])))

(defn attrs-with-weight [g edge]
  (assoc (la/attrs g edge)
    :edge/weight (lg/weight g edge)))

(defn edges-collapsed
  "Returns the edges of g, but with reciprocal edges collapsed into a single edge."
  [g]
  (loop [[edge & more-edges] (lg/edges g)
         bis #{}
         result {}]
    (if edge
      (let [attrs (attrs-with-weight g edge)
            bi? (and
                  (apply lg/has-edge? g (reverse edge))
                  (= attrs
                     (attrs-with-weight g (vec (reverse edge)))))]
        (recur
          more-edges
          (if bi?
            (conj bis (set edge))
            bis)
          (if (contains? bis (set edge))
            result
            (assoc result edge (cond-> attrs bi? (assoc :edge/reciprocated? true))))))
      result)))

(defn edges [g]
  (into {}
        (for [edge (lg/edges g)]
          [edge (attrs-with-weight g edge)])))

;; TODO: might be better to rely on nodes/edges, need to get everything pointing to the same stuff maybe
(defn entity [g id]
  (let [m (la/attrs g id)]
    (if (lg/has-node? g id)
      (-> (get-in g [:node-types (:node/type m "person")])
          (merge m))
      (-> (get-in g [:edge-types (:edge/type m "likes")])
          (merge m)
          (assoc :edge/weight (lg/weight g id))))))

;; TODO: kind of expect to be able to pass a key here to get outs of a node hmmm.
(defn out-edges
  ([g]
   (out-edges g (constantly true)))
  ([g filter-fn]
   (reduce (fn [acc [[from to] v]]
             (if (filter-fn v)
               (assoc-in acc [from to] v)
               acc))
           {}
           (edges g))))

(defn in-edges
  ([g]
   (in-edges g (constantly true)))
  ([g filter-fn]
   (reduce (fn [acc [[from to] v]]
             (if (filter-fn v)
               (update-in acc [to from] v)
               acc))
           {}
           (edges g))))

(defn with-ranks [g]
  (when-let [node-ids (seq (lg/nodes g))]
    (let [ranks (pagerank/ranks node-ids (lg/edges g))]
      (reduce
        (fn [acc km]
          (add-attrs acc km))
        g
        (for [[k pr r] ranks]
          [k {:node/pagerank pr
              :node/rank r}])))))

(defn update-edge [g edge-id k v]
  (-> g
      (la/add-attr edge-id k v)
      (with-ranks)))

(defn with-edge [g from to edge-type]
  (-> g
      (lg/add-edges [from to])
      (la/add-attr [from to] :edge/type edge-type)
      (with-ranks)))

(defn without-node [g id]
  (-> g
      (lg/remove-nodes g id)
      (with-ranks)))

(defn without-edge [g from to]
  (->
    (lg/remove-edges g [from to])
    (with-ranks)))

(defn ^:private without-out-edges [g node-id outs]
  (lg/remove-edges* g (for [to outs]
                        [node-id to])))

(defn ^:private without-in-edges [g node-id ins]
  (lg/remove-edges* g (for [from ins]
                        [from node-id])))

(defn with-successors [g node-id outs edge-type]
  (reduce (fn collect-ins [acc out]
            (with-edge acc node-id out edge-type))
          g
          outs))

(defn with-predecessors [g node-id ins edge-type]
  (reduce (fn collect-ins [acc in]
            (with-edge acc in node-id edge-type))
          g
          ins))

(defn ^:private replace-out-edges [g outs node-id edge-type]
  (let [old-outs (set (filter #(= edge-type (la/attr g [node-id %] :edge/type))
                              (lg/successors* g node-id)))
        removals (set/difference old-outs outs)
        g (without-out-edges g node-id removals)]
    ;; TODO: looks like they are added without a node-type???
    (with-successors g node-id outs edge-type)))

(defn ^:private replace-in-edges [g ins node-id edge-type]
  (let [old-ins (set (filter #(= edge-type (la/attr g [% node-id] :edge/type)) (lg/predecessors* g node-id)))
        removals (set/difference old-ins ins)
        g (without-in-edges g node-id removals)]
    (with-predecessors g node-id ins edge-type)))

(defn replace-edges [g node-id node-type edge-type outs ins]
  (let [node-ids (set (concat [node-id] outs ins))
        new-nodes (set/difference node-ids (lg/nodes g))]
    (-> g
        (lg/add-nodes* new-nodes)
        (la/add-attr-to-nodes :node/type node-type new-nodes)
        (replace-in-edges ins node-id edge-type)
        (replace-out-edges outs node-id edge-type)
        (with-ranks))))

(defn rename-node [g node-id new-node-id]
  (if (= node-id new-node-id)
    g
    ;; TODO: edge attrs
    (let [node-attrs (la/attrs g node-id)
          outs (lg/successors g node-id)
          ins (lg/predecessors g node-id)
          ;; TODO: fix me
          edge-type "likes"]
      (-> g
          (lg/remove-nodes node-id)
          (lg/add-nodes new-node-id)
          (add-attrs [new-node-id node-attrs])
          (with-successors new-node-id outs edge-type)
          (with-predecessors new-node-id ins edge-type)
          (with-ranks)))))

;; TODO: fix io
;; TODO: when renaming nodes, the edge attrs might be left over?
