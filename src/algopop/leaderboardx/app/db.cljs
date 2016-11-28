(ns algopop.leaderboardx.app.db
  (:require
    [algopop.leaderboardx.app.pagerank :as pagerank]
    [datascript.core :as d]
    [devcards.core :as dc :refer-macros [defcard deftest]]
    [posh.reagent :refer [q posh! transact!] :as posh]
    [reagent.core :as reagent]
    [reagent.ratom :as ratom :include-macros]
    [datascript.db :as db]))

(defonce schema
  {:assessment-type/name {:db/index true},
   :assessment/assessor {:db/valueType :db.type/ref},
   :assessment/date {},
   :assessee/group {:db/cardinality :db.cardinality/many, :db/valueType :db.type/ref},
   :edge/name {},
   :group/name {},
   :user/password {},
   :assessee/name {:db/index true},
   :assessment/type {:db/valueType :db.type/ref},
   :user/status {:db/valueType :db.type/ref},
   :group/organization {:db/valueType :db.type/ref},
   :organization/administrator {:db/cardinality :db.cardinality/many, :db/valueType :db.type/ref},
   :assessment/status {:db/valueType :db.type/ref},
   :assessee/tag {:db/cardinality :db.cardinality/many, :db/valueType :db.type/ref},
   :user/email {:db/index true},
   :tag/name {},
   :assessment/duration-minutes {},
   :assessment/assessee {:db/valueType :db.type/ref},
   :assessment-type/attribute {:db/cardinality :db.cardinality/many, :db/valueType :db.type/ref},
   :organization/name {:db/index true}

   ;; stuff
   :dom/child {:db/cardinality :db.cardinality/many, :db/valueType :db.type/ref, :db/isComponent true}

   :from {:db/valueType :db.type/ref}
   :to {:db/valueType :db.type/ref}})

(defonce conn
  (doto
    (d/create-conn schema)
    (posh!)))

(defn add-assessment [coach player attrs]
  (transact!
    conn
    [{:coach coach
      :player player
      :attrs attrs}]))

(defonce seed
  (do
    ;; TODO: just make one map?
    (transact!
      conn
      [{:name "William"
        :somedata "something about William"}])
    (add-assessment "Coach" "William" {:producivity 7})))

(def q-player
  '[:find ?s ?attrs (pull ?e [*])
    :where [?a :coach "Coach"]
    [?a :player "William"]
    [?a :attrs ?attrs]
    [?e :name "William"]
    [?e :somedata ?s]])

(defn player []
  (q q-player conn))

(def q-ac
  '[:find (pull ?e [*])
    :in $ ?template
    :where
    [$ ?e :assessment-template/name ?template]])

(defn assessment-components [name]
  (q q-ac conn name))

(def q-ac2
  '[:find (pull ?e [*])
    :in $ ?template
    :where
    [$ ?e :dom/value ?template]
    [$ ?e :dom/tag "template"]])

(defn ac2 [template]
  (q q-ac2 conn template))

(defcard ac2
  @(ac2 "player-assessment"))

(defn assess [ol]
  (for [i (range (count ol))]
    {:order i
     :line (ol i)}))

(defn rank-entities [ranks]
  (for [[id rank pagerank] ranks]
    {:db/id id
     :rank rank
     :pagerank pagerank}))

(def node-q
  '[:find [?e ...]
    :in $ ?name
    :where [?e :node/name ?name]])

(defn get-node
  ([name]
   (first (d/q node-q @conn name)))
  ([name default]
   (or (get-node name) default)))

(defn p [id]
  (posh/pull conn '[*] id))

(def nodes-q
  '[:find [?e ...]
    :where
    [?e :node/name ?name]])

(def edges-q
  '[:find [?e ...]
    :where
    [?e :from ?from]
    [?e :to ?to]])

(defn set-ranks! []
  (let [node-ids (d/q nodes-q @conn)
        es (d/pull-many @conn '[*] (d/q edges-q @conn))]
    (transact!
      conn
      (rank-entities (pagerank/ranks node-ids es)))))

(defn add-edge [name]
  (transact!
    conn
    [{:edge/name name}])
  (set-ranks!))

(defn add-node [name]
  (transact!
    conn
    [{:node/name name}])
  (set-ranks!))

(defn pull-q
  ([conn query]
   (pull-q conn '[*] query))
  ([conn pattern query & args]
   (ratom/reaction
     (doall
       (for [e @(apply q query conn args)]
         @(posh/pull conn pattern e))))))

(defn watch-nodes []
  (pull-q conn nodes-q))

(defn watch-edges []
  (pull-q conn edges-q))

(defn update-nodes [nodes]
  (transact! conn nodes))

(defn get-out-edges [id]
  @(q '[:find ?edge
        :in $ ?node
        :where [?edge :from ?node]]
      conn
      id))

(defn get-in-edges [id]
  @(q '[:find ?edge
        :in $ ?node
        :where [?edge :to ?node]]
      conn
      id))

(defn rename-node [id new-name]
  (let [{:keys [node/name] :as node} (p id)]
    (when (not= new-name name)
      (if-let [existing (get-node new-name)]
        (let [outs (get-out-edges id)
              ins (get-in-edges id)]
          (update-nodes
            (concat
              [[:db.fn/retractEntity id]
               (assoc node :db/id existing)]
              (for [[out] outs]
                {:db/id out
                 :from existing})
              (for [[in] ins]
                {:db/id in
                 :to existing}))))
        (update-nodes
          [{:db/id id
            :node/name new-name}])))))

;; TODO: if node already exists ^^
(defn replace-edges-entities [k outs ins]
  (let [node-id (get-node k -1)
        out-count (count outs)
        in-count (count ins)
        outs-start -2
        out-ids (map get-node outs (iterate dec outs-start))
        ins-start (- outs-start out-count)
        in-ids (map get-node ins (iterate dec ins-start))
        out-edges-start (- ins-start in-count)
        out-edge-ids (iterate dec out-edges-start)
        in-edges-start (- out-edges-start out-count)
        in-edge-ids (iterate dec in-edges-start)]
    ;; TODO: is this just concat?
    (->
      ;; node entity
      [{:db/id node-id
        :node/name k}]
      ;; related out and in target node entities
      (into
        (for [[id name] (map vector (concat out-ids in-ids) (concat outs ins))]
          {:db/id id
           :node/name name}))
      ;; out edge entities
      (into
        (for [[out-id edge-id name] (map vector out-ids out-edge-ids outs)]
          {:db/id edge-id
           :edge/name (str k " to " name)
           :from node-id
           :to out-id}))
      ;; in edge entities
      (into
        (for [[in-id edge-id name] (map vector in-ids in-edge-ids ins)]
          {:db/id edge-id
           :edge/name (str name " to " k)
           :from in-id
           :to node-id})))))

(defn replace-edges [k outs ins]
  (transact! conn (replace-edges-entities k outs ins))
  (set-ranks!))

(defn nodes-for-table []
  (ratom/reaction (sort-by :rank @(watch-nodes))))

(def outs-q
  '[:find [?name ...]
    :in $ ?node
    :where
    [?edge :from ?node]
    [?edge :to ?out]
    [?out :node/name ?name]])

(defn outs [id]
  (q outs-q conn id))

(def ins-q
  '[:find [?name ...]
    :in $ ?node
    :where
    [?edge :to ?node]
    [?edge :from ?in]
    [?in :node/name ?name]])

(defn ins [id]
  (q ins-q conn id))
