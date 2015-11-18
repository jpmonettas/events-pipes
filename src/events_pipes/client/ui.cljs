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


 
(defn dispatch
  ([action] (dispatch action nil))
  ([action data]
   (core/dispatch core/app-state {:type action :data data})))


(defn search-changed
  "User changed the content of the search box"
  [e]
  (let [text (-> e .-target .-value)]
    (dispatch :search-changed text))) 

(defn tab-click [ev t]
  (case (-> ev .-button) 
    0 (dispatch :select-tab t)
    1 (dispatch :remove-tab t)))

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
       [:button.btn.btn-info {:on-click #(dispatch :connect @server-address)} [:span.glyphicon.glyphicon-link]]
       [:button.btn.btn-danger.clear {:on-click #(dispatch :clear-events)} [:span.glyphicon.glyphicon-trash]]
       
       [:div.input-group.search
        [:span.glyphicon.glyphicon-search.input-group-addon]
        [:input.search.form-control {:type :text
                                     :on-change #(dispatch :search-changed (-> % .-target .-value))}]]])))

(defn events
  "React component for events. Draws a tabs panel each with it's events"
  [{:keys [events selected-tab ev-pattern]}]
  [:div#events
   [:ul.nav.nav-tabs
    (doall (map-indexed
            (fn [idx t]
              [:li (merge
                    {:key idx}
                    (when (= selected-tab t) {:class :active}))
               [:a {:on-click #(tab-click % t)} t]])
            (keys events)))]
    [:div.tab-content
     [:ul (doall
           (map-indexed
            (fn [idx {:keys [timestamp remote-addr role color summary detail] :as ev}]
              [:li {:key idx
                    :on-click #(dispatch :select-event ev)}
               [:span.timestamp {:style {:background-color color}} (try
                                                                     (->> (.getTime timestamp)
                                                                          tc/from-long
                                                                          (tf/unparse (tf/formatter "HH:mm:ss.SSS") ))
                                                                     (catch js/Error e
                                                                         "FIX ME"))] 
               [:span.ip {:style {:background-color color}} remote-addr]
               [:span.role {:style {:background-color color}} role]
               [:span.content {:style {:background-color color}} (when detail
                                                                   [:span.glyphicon.glyphicon-fullscreen]) (str summary)]])

            ;; every time we are going to render the events filter only the ones that matches
            ;; the regexp in the search box
            (filter #(re-matches ev-pattern (apply str %))
                    (get events selected-tab))))]]])


(defn taps-panel
  "React component. Draws the taps panel where we draw the tree"
  [taps-panel-open?]
  [:div#taps-panel {:class (when taps-panel-open? "taps-panel-open")}
   [:span.glyphicon {:class (if taps-panel-open?
                              "glyphicon-menu-left"
                              "glyphicon-menu-right")
                     :on-click #(dispatch :toggle-taps-panel)} ""]
   ;; We are not rendering this with react, since we are doing this
   ;; with D3 library
   [:div#taps-tree]]) 

(defn ui
  "React component. Main UI component"
  []
  (let [store @(core/get-state core/app-state)
        {:keys [modal-open? selected taps-panel-open?]} store]
   [:div 
    [:div#header
     [header]
     [taps-panel taps-panel-open?]
     (when modal-open?
       [:div.last-event
        [:a.close-detail
         [:span.glyphicon.glyphicon-remove {:on-click #(when modal-open? (dispatch :close-modal))}]]
        [:div.summary {} (:summary selected)]
        (when (:detail selected)
          [:pre.code 
           [selected-event selected]])])]
    [events store]]))  

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
                       (.on "click" (fn [d] (dispatch :toggle-tap (.-id d))))
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

(add-watch (core/get-state core/app-state) :taps-watcher
           (fn [_ _ {old-taps :taps} {new-taps :taps}]
             (when (and (not (= old-taps new-taps))
                        (not (empty? new-taps)))
               (set! (.-innerHTML (.getElementById js/document "taps-tree")) "")
               (draw-taps-tree (-> new-taps
                                  core/taps-tree
                                  clj->js)))))
