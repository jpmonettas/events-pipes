;; This namespace implements all monior ui functionality
;; From rendering the react components thru managing the user events
(ns ^:figwheel-always events-pipes.client.ui
  (:require-macros [cljs.core.async.macros :as asyncm :refer (go go-loop)])
  (:require [reagent.core :as reagent :refer [atom]]
            [cljs.core.async :as async :refer (<! >! put! chan timeout)]
            [cljs-time.format :as tf]
            [cljs-time.coerce :as tc]
            [cljsjs.highlight]
            [cljsjs.d3] 
            [cljsjs.highlight.langs.clojure]
            [events-pipes.client.core :as core]
            [cljs.pprint :refer [pprint]]))


 
;; # User events handling

(defn select-event
  "User clicked an event line"
  [ev]
  (swap! core/app-state (fn [s] (-> s
                               (assoc :selected ev)
                               (assoc :modal-open? true)))))

(defn close-modal
  "User wants to close the detail modal"
  []
  (swap! core/app-state assoc :modal-open? false))

(defn toggle-tap
  "User clicked on a tree tap to toggle it on/off"
  [tap-id]
  (let [chsk-send! (:send-fn @core/app-state)]
    (chsk-send! [:taps/tap-toggled tap-id])))

(defn connect
  "User clicked the connect button"
  [server]
  (core/connect server))
 
(defn clear-events
  "User clicked the clear events button"
  []
  (swap! core/app-state merge {:selected 0 :role-colors {} :events {}}))

(defn select-tab [t]
  (swap! core/app-state assoc :selected-tab t))

(defn remove-tab [t]
  (swap! core/app-state update-in [:events] dissoc t))

(defn tab-click [ev t]
  (case (-> ev .-button) 
    0 (select-tab t)
    1 (remove-tab t)))

(defn toggle-taps-panel
  "User clicked the arrow to toggle show/hide the pas panel"
  []
  (swap! core/app-state update-in [:taps-panel-open?] #(not %))) 

(defn search-changed
  "User changed the content of the search box"
  [e]
  (let [text (-> e .-target .-value)]
    (swap! core/app-state assoc :ev-pattern (re-pattern (str ".*" text ".*")))))


;; # React components

(def selected-event
  "React component for rendering the event detail with code higlight"
  (with-meta
    
    (fn [{:keys [summary detail] :as ev}]
      [:code {:class "clojure"} (with-out-str (pprint detail))])

    ;; In reagent we can hook on react lifecycle with component metadata
    ;; After component mounts we execute the highlightBlock function
    {:component-did-mount
     (fn [this old-props old-childs]
       (.highlightBlock js/hljs (reagent/dom-node this)))})) 



(defn header
  "React component for header of the UI with the connect box"
  [connected]
  (let [server-address (atom (str (-> js/window .-location .-host)))]
    (fn [props]
      [:div.form-inline.server-container 
       [:div.input-group.server
        [:span.input-group-addon "Sever:"]
        [:input.form-control {:type :text
                              :on-change #(reset! server-address (-> % .-target .-value))
                              :value @server-address}]]
       [:button.btn.btn-info {:on-click #(connect @server-address)} [:span.glyphicon.glyphicon-link]]
       [:button.btn.btn-danger.clear {:on-click #(clear-events)} [:span.glyphicon.glyphicon-trash]]
       
       [:div.input-group.search
        [:span.glyphicon.glyphicon-search.input-group-addon]
        [:input.search.form-control {:type :text
                                     :on-change #(search-changed %)}]]])))

(defn events
  "React component for events. Draws a tabs panel each with it's events"
  []
  [:div#events
   [:ul.nav.nav-tabs
    (doall (for [t (keys (:events @core/app-state))]
             [:li (when (= (:selected-tab @core/app-state) t) {:class :active})
              [:a {:on-click #(tab-click % t)} t]]))]
    [:div.tab-content
     [:ul (doall
           (map-indexed
            (fn [idx {:keys [timestamp remote-addr role color summary detail] :as ev}]
              [:li {:key idx
                    :on-click #(select-event ev)}
               [:span.timestamp {:style {:background-color color}} (tf/unparse (tf/formatter "HH:mm:ss.SSS")
                                                                               (tc/from-long (.getTime timestamp)))] 
               [:span.ip {:style {:background-color color}} remote-addr]
               [:span.role {:style {:background-color color}} role]
               [:span.content {:style {:background-color color}} (when detail [:span.glyphicon.glyphicon-fullscreen]) (str summary)]])

            ;; every time we are going to render the events filter only the ones that matches
            ;; the regexp in the search box
            (filter #(re-matches (:ev-pattern @core/app-state) (apply str %))
                    (get-in @core/app-state [:events (:selected-tab @core/app-state)]))))]]])


(defn taps-panel
  "React component. Draws the taps panel where we draw the tree"
  []
  [:div#taps-panel {:class (when (:taps-panel-open? @core/app-state)
                             "taps-panel-open")}
   [:span.glyphicon {:class (if (:taps-panel-open? @core/app-state)
                              "glyphicon-menu-left"
                              "glyphicon-menu-right")
                     :on-click toggle-taps-panel} ""]
   ;; We are not rendering this with react, since we are doing this
   ;; with D3 library
   [:div#taps-tree]]) 

(defn ui
  "React component. Main UI component"
  []
  [:div 
   [:div#header
    [header]
    [taps-panel]
    (when (:modal-open? @core/app-state)
      (let [ev (:selected @core/app-state)]
        [:div.last-event
         [:a.close-detail
          [:span.glyphicon.glyphicon-remove {:on-click #(when (:modal-open? @core/app-state) (close-modal))}]]
         [:div.summary {} (:summary ev)]
           (when (:detail ev)
             [:pre.code 
              [selected-event ev]])]))]
   [events]])  

(reagent/render-component [ui]
                          (. js/document (getElementById "app"))) 


;; ## Tree drawing
;; Crazy stuff to draw the tree using d3 
;; ** Important ** from here on we are outside react

(defn draw-taps-tree [taps-tr]
  (let [margin {:top 20 :right 120 :bottom 20 :left 120}
        width (- 960 (:right margin) (:left margin))
        height (- 500 (:top margin) (:bottom margin))

        svg (-> js/d3 
                (.select "div#taps-tree")
                (.append "svg")
                (.attr "width" (+ width (:right margin) (:left margin)))
                (.attr "height" (+ height (:top margin) (:bottom margin)))
                (.append "g")
                (.attr "transform" (str "translate(" (:left margin) "," (:top margin) ")")))

 
 
        tree (-> js/d3
                 .-layout 
                 .tree
                 (.size (clj->js [height width])))

        diagonal (-> js/d3
                     .-svg
                     .diagonal
                     (.projection (fn [d]
                                    #js [(.-y d) (.-x d)])))

        nodes (-> tree
                  (.nodes taps-tr)
                  .reverse)
        _ (.forEach nodes
                    (fn [d] (set! (.-y d) (* (.-depth d) 200)))) 
        links (.links tree nodes)
        node (-> svg
                 (.selectAll "g.node")
                 (.data nodes (fn [d] (.-id d))))
        link (-> svg 
                 (.selectAll "path.link")
                 (.data links (fn [d] (-> d
                                          .-target
                                          .-id))))
        node-enter (-> (.enter node)
                       (.append "g")
                       (.on "click" (fn [d] (toggle-tap (.-id d))))
                       (.attr "class" "node")
                       (.attr "transform" (fn [d]
                                            (str "translate(" (.-y d) "," (.-x d) ")"))))]
    
    (-> node-enter
        (.append "circle")
        (.attr "r" 10)
        (.style "fill" (fn [d] (if (aget d "muted?")
                                 "#ff0000"
                                 "#00ff00"))))

    (-> node-enter
        (.append "text")
        (.attr "x" 13)
        (.attr "dy" ".35em") 
        (.attr "text-anchor" "start")
        (.text (fn [d] (.-name d))) 
        (.style "fill-opacity" 1))

    (-> (.enter link)
        (.insert "path" "g")
        (.attr "class" "link")
        (.attr "d" diagonal))))

;; To make it feel like react we are watching the state
;; and if something changes repain the whole tree

(add-watch core/app-state :taps-watcher
           (fn [_ _ {old-taps :taps} {new-taps :taps}]
             (when (and (not (= old-taps new-taps))
                        (not (empty? new-taps)))
               (set! (.-innerHTML (.getElementById js/document "taps-tree")) "")
               (draw-taps-tree (-> new-taps
                                  core/taps-tree
                                  clj->js)))))
