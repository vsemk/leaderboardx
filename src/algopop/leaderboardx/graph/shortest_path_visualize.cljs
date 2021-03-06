(ns algopop.leaderboardx.graph.shortest-path-visualize
  (:require [reagent.core :as reagent]
            [clojure.set :as set]
            [clojure.string :as string]
            [algopop.leaderboardx.graph.graph :as graph]
            [cljs.pprint :as pprint]))

(defonce vis
  (reagent/atom nil))

;; TODO: add a class to nodes?
(def colors
  {:red "#c82829"
   :orange "#f5871f"
   :yellow "#eab700"
   :green "#718c00"
   :aqua "#3e999f"
   :aqua2 "#6ec9cf"
   :blue "#4271ae"
   :purple "#8959a8"})

(defn visualize-start [g start-node target-node]
  (reset! vis {:status (str "Initialize search from " start-node " to " target-node)
               :currently-visiting nil
               :cost 0
               :candidates {}
               :visited {}})
  ;; TODO: switch to loom
  (doseq [node-id (keys (graph/nodes @g))]
    (swap! g graph/add-attr node-id :node/color
           (cond
             (= node-id start-node) (:green colors)
             (= node-id target-node) (:blue colors)
             :else nil)))
  (doseq [edge-id (keys (graph/edges @g))]
    (swap! g graph/add-attr edge-id :edge/color nil)))

(defn visualize-expand [g visited candidates expanded-candidates]
  (let [new-candidates (set/difference
                         (set (keys expanded-candidates))
                         (set (keys candidates)))]
    (swap! vis assoc
           :status (str "Expand to new candidates: "
                        (if (seq new-candidates)
                          (string/join ", " (sort new-candidates))
                          "none"))
           :candidates expanded-candidates
           :visited visited)
    (doseq [node-id (keys expanded-candidates)]
      (swap! g graph/add-attr node-id :node/color
             (if (new-candidates node-id)
               (:aqua2 colors)
               (:aqua colors))))
    (doseq [[to {:keys [edge/from]}] expanded-candidates]
      (swap! g graph/add-attr [from to] :edge/color
             (if (new-candidates to)
               (:aqua2 colors)
               (:aqua colors))))))

(defn visualize-visit [g closest-node predecessor visited candidates cost]
  (swap! vis assoc
         :status (str "Visit closest candidate: " closest-node " via " predecessor)
         :currently-visiting closest-node
         :candidates candidates
         :cost cost)
  (doseq [[to from] visited
          :when from]
    (swap! g graph/add-attr to :node/color
           (if (= to closest-node)
             (:orange colors)
             (:yellow colors)))
    (swap! g graph/add-attr [from to] :edge/color
           (if (= to closest-node)
             (:orange colors)
             (:yellow colors)))))

(defn backtrack [g visited [to :as path]]
  (if-let [from (visited to)]
    (do
      (swap! vis assoc
             :status (str "Backtrack from " from " to " to ". Path is: "
                          (string/join ", " path)))
      (swap! g graph/add-attr from :node/color (:blue colors))
      (swap! g graph/add-attr [from to] :edge/color (:blue colors))
      #(backtrack g visited (cons from path)))
    (swap! vis assoc
           :status (str "Found path: "
                        (string/join " → " path)))))

(defn visualize-solution [g visited target-node cost]
  (swap! vis assoc
         :status (str "Found target node " target-node)
         :cost cost
         :visited visited)
  (swap! g assoc-in [:nodes target-node :node/color] (:blue colors))
  #(backtrack g visited (list target-node)))

(defn visualize-fail [g]
  (swap! vis assoc
         :status "No path found"))

(defn slow-trampoline
  ([t searching? f]
   (when @searching?
     (let [result (f)]
       (if (fn? result)
         (js/setTimeout #(slow-trampoline t searching? result) t)
         (do (reset! searching? false)
             result)))))
  ([t searching? f & args]
   (slow-trampoline t searching? #(apply f args))))

(defn inspect []
  [:div
   (doall
     (for [[k v] @vis]
       ^{:key k}
       [:div
        [:h4 k]
        ;; TODO: don't use pprint
        [:pre
         [:code
          (if (string? v)
            v
            (with-out-str (pprint/pprint v)))]]]))])
