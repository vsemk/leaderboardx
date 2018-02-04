(ns algopop.leaderboardx.graph.graph-table
  (:require
    [algopop.leaderboardx.graph.graph :as graph]
    [algopop.leaderboardx.app.views.common :as common]
    [goog.string :as gstring]
    [clojure.string :as string]
    [reagent.core :as reagent]
    [reagent.ratom :as ratom :refer-macros [reaction]]))

;; TODO: only show video and discuss on about and discuss tabs

(def delimiter #"[,;]")

(defn split [s]
  (filter seq (map string/trim (string/split s delimiter))))

(defn replace-edges [graph selected-id source node-type edge-type outs ins]
  (when-let [source (first (split source))]
    (swap! graph
           graph/replace-edges
           source
           node-type
           edge-type
           (set (split outs))
           (set (split ins)))
    (reset! selected-id source)))

(defn add-node [g outs ins selected-id selected-node-type selected-edge-type search-term]
  (reagent/with-let
    [the-outs (reagent/atom "")
     the-ins (reagent/atom "")
     node-name-input (reagent/atom nil)
     reset-inputs (fn a-reset-inputs [id]
                    (when (not= @node-name-input js/document.activeElement)
                      (reset! search-term (or id "")))
                    (reset! the-outs (string/join ", " (keys (@outs id))))
                    (reset! the-ins (string/join ", " (keys (@ins id)))))
     watch (reagent/track!
             (fn []
               (if (vector? @selected-id)
                 (let [[from to] @selected-id]
                   (reset-inputs from))
                 (reset-inputs @selected-id))))
     ok (fn add-node-click [e]
          (replace-edges g selected-id @search-term @selected-node-type @selected-edge-type @the-outs @the-ins)
          (reset! search-term "")
          (reset! the-outs "")
          (reset! the-ins "")
          (reset! selected-id nil)
          (.focus @node-name-input))
     input-key-down
     (fn input-key-down [e]
       (case (common/key-code-name (.-keyCode e))
         "ESC" (common/blur-active-input)
         "ENTER" (do
                   (.preventDefault e)
                   (ok e))
         nil))]
    [:tr
     [:td
      [:input.form-control
       {:name "name"
        :value @search-term
        :ref
        (fn [this]
          (when this
            (reset! node-name-input this)))
        :on-key-down input-key-down
        :on-change
        (fn search-term-change [e]
          (let [s (first (split (.. e -target -value)))]
            (when (not= s @search-term)
              (reset! search-term s)
              (if (seq @search-term)
                (if (get (graph/nodes @g) @search-term)
                  (when (not= @selected-id @search-term)
                    (reset! selected-id @search-term))
                  (when @selected-id
                    (reset! selected-id nil)))
                (when @selected-id
                  (reset! selected-id nil))))))}]]
     [:td
      [:input.form-control
       {:name "outs"
        :value @the-outs
        :on-key-down input-key-down
        :on-change
        (fn outs-change [e]
          (reset! the-outs (.. e -target -value)))}]]
     [:td
      [:input.form-control
       {:name "ins"
        :value @the-ins
        :on-key-down input-key-down
        :on-change
        (fn ins-change [e]
          (reset! the-ins (.. e -target -value)))}]]
     [:td
      [:button.form-control
       {:on-click ok}
       "Add"]]]
    (finally
      (reagent/dispose! watch))))

(defn select-type [types selected]
  [:th
   (into
     [:select
      {:on-change
       (fn selection [e]
         (when-let [v (.. e -target -value)]
           (reset! selected v)
           (common/blur-active-input)))}]
     (for [type (keys types)]
       [:option
        {:value type}
        (string/capitalize type)]))])

(defn humanize-id [id entity]
  (if (vector? id)
    (str (first id) " " (:edge/type entity) " " (second id))
    (str id)))

(defn attribute-editor [g id]
  (let [entity (graph/entity @g id)]
    [:div
     [:i (humanize-id id entity)]
     [common/single-entity-editor id entity
      #(swap! g graph/add-attr %1 %2 %3)
      #(swap! g graph/remove-attr %1 %2)]]))

(defn table [g selected-id node-types edge-types selected-node-type selected-edge-type
             {:keys [shift-click-node]}]
  (let [nodes-by-rank (reaction
                        ;; TODO: maybe share the reaction with graph-view?
                        (sort-by #(vector (key %) (:node/rank (val %)))
                                 (graph/nodes @g)))
        search-term (reagent/atom "")
        ;; TODO: don't need to be reactions, just put in body?
        filter-fn (fn [{:keys [edge/type]}]
                    (= type @selected-edge-type))
        outs (reaction (graph/out-edges @g filter-fn))
        ins (reaction (graph/in-edges @g filter-fn))]
    (fn a-table [g selected-id node-types edge-types selected-node-type selected-edge-type]
      [:div
       [:table.table.table-responsive
        [:thead
         [:tr
          ;;[select-type @node-types selected-node-type]
          [:th "Person"]
          [select-type @edge-types selected-edge-type]
          [:th "Reciprocated by"]
          [:th "Rank"]]]
        (into
         [:tbody
          [add-node g outs ins selected-id selected-node-type selected-edge-type search-term]]
         (for [[node-id {:keys [node/rank node/name]}] @nodes-by-rank
               :let [selected? (= node-id @selected-id)
                     match? (and (seq @search-term)
                                 (gstring/startsWith (or name node-id) @search-term))
                     out-ids (keys (@outs node-id))
                     in-ids (keys (@ins node-id))
                     outs-string (string/join ", " out-ids)
                     ins-string (string/join ", " in-ids)]]
           [:tr
            {:class (cond selected? "info"
                          match? "warning")
             :style {:cursor "pointer"}
             :on-click
             (fn table-row-click [e]
               (when (and shift-click-node (.-shiftKey e) @selected-id node-id (not= @selected-id node-id))
                 ;; TODO: don't care about shape here
                 (shift-click-node @selected-id node-id nil nil))
               (reset! selected-id node-id))}
            [:td [common/editable-string (or name node-id)
                  (fn update-node-name [v]
                    (let [new-name (string/trim v)]
                      (when (and (seq new-name)
                                 (not= new-name (or name node-id)))
                        (swap! g graph/rename-node node-id new-name)
                        (reset! selected-id new-name))))]]
            ;; TODO: names vs ids omg
            [:td [common/editable-string outs-string
                  (fn update-out-edges [v]
                    ;; TODO: make edge type selection make sense
                    (replace-edges g selected-id (or name node-id) @selected-node-type @selected-edge-type v ins-string)
                    (common/blur-active-input))]]
            [:td [common/editable-string ins-string
                  (fn update-in-edges [v]
                    (replace-edges g selected-id (or name node-id) @selected-node-type @selected-edge-type outs-string v)
                    (common/blur-active-input))]]
            [:td rank]]))]])))
