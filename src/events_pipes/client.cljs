(ns ^:figwheel-always events-pipes.client
    (:require-macros
     [cljs.core.async.macros :as asyncm :refer (go go-loop)])
    (:require [reagent.core :as reagent :refer [atom]]
              [cljs.core.async :as async :refer (<! >! put! chan timeout)]
              [clojure.string :as str]
              [taoensso.sente  :as sente :refer (cb-success?)]
              [clojure.set :as set]
              [cljs-time.format :as tf]
              [cljs-time.coerce :as tc]
              [cljsjs.highlight]
              [cljsjs.d3] 
              [cljsjs.highlight.langs.clojure]
              [cljs.pprint :refer [pprint]]
              [amalloy.ring-buffer :refer [ring-buffer]]))
 
(enable-console-print!)



(defonce app-state (atom {:state nil
                          :taps []
                          :ev-pattern #".*"
                          :modal-open? false
                          :taps-panel-open? true
                          :chsk nil
                          :selected nil
                          :selected-tab "/input"
                          :role-colors {}
                          :events {}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; 
;; Incomming channel messages router ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defonce router_ (atom nil))

(declare event-msg-handler*)

(defn  stop-router! [] 
  (when-let [stop-f @router_] (stop-f)))

(defn start-router! [ch] 
  (stop-router!)
  (reset! router_ (sente/start-chsk-router! ch event-msg-handler*)))
 
(defn on-js-reload [])

;;;;;;;;
;; UI ;;
;;;;;;;;



;; User events handling
;; --------------------
(defn select-event [ev]
  (swap! app-state (fn [s] (-> s
                               (assoc :selected ev)
                               (assoc :modal-open? true)))))

(defn close-modal []
  (swap! app-state assoc :modal-open? false))

(defn toggle-tap [tap-id]
  (let [chsk-send! (:send-fn @app-state)]
    (chsk-send! [:taps/tap-toggled tap-id])))

(defn connect [server]
  (let [{:keys [chsk ch-recv send-fn state]}
        (sente/make-channel-socket! "/chsk" ; Note the same path as before
                                    {:type :ws ; e/o #{:auto :ajax :ws}
                                     :chsk-url-fn (constantly (str "ws://" server "/chsk"))})]

    (stop-router!)
    (swap! app-state #(-> %
                          (assoc :state state)
                          (assoc :send-fn send-fn)))

    (start-router! ch-recv)

    ;; Wait some time for the channels to connect, then ask for a refresh
    (go (<! (timeout 2000))
        (send-fn [:taps/please-refresh]))))
 
(defn clear-events []
  (swap! app-state merge {:selected 0 :role-colors {} :events {}}))

(defn toggle-taps-panel []
  (swap! app-state update-in [:taps-panel-open?] #(not %))) 


;; Components
;; ----------
(def selected-event
  (with-meta
    (fn [{:keys [summary detail] :as ev}]
      [:code {:class "clojure"} (with-out-str (pprint detail))])
    {:component-did-mount
                     (fn [this old-props old-childs]
                       (.highlightBlock js/hljs (reagent/dom-node this)))})) 


(defn search-changed [e]
  (let [text (-> e .-target .-value)]
    (swap! app-state assoc :ev-pattern (re-pattern (str ".*" text ".*")))))



(defn header [connected]
  (let [server-address (atom "localhost:7777")]
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

(defn events []
  [:div#events
   [:ul.nav.nav-tabs
    (doall (for [t (keys (:events @app-state))]
             [:li (merge
                   {:on-click #(swap! app-state assoc :selected-tab t)}
                   (when (= (:selected-tab @app-state) t) {:class :active})) [:a t]]))]
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
            (filter #(re-matches (:ev-pattern @app-state) (apply str %))
                    (get-in @app-state [:events (:selected-tab @app-state)]))))]]])


(defn taps-panel []
  [:div#taps-panel {:class (when (:taps-panel-open? @app-state)
                             "taps-panel-open")}
   [:span.glyphicon {:class (if (:taps-panel-open? @app-state)
                              "glyphicon-menu-left"
                              "glyphicon-menu-right")
                     :on-click toggle-taps-panel} ""]
   [:div#taps-tree]]) 

(defn ui []
  [:div 
   [:div#header
    [header]
    [taps-panel]
    (when (:modal-open? @app-state)
      (let [ev (:selected @app-state)]
          [:div.last-event {:on-click #(when (:modal-open? @app-state) (close-modal))}
           [:div.summary (:summary ev)]
           (when (:detail ev)
             [:pre.code 
              [selected-event ev]])]))]
   [events]])  

(reagent/render-component [ui]
                          (. js/document (getElementById "app"))) 


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Incomming events handling  ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; 

(defn add-colored-event [state {:keys [event-id tap-id timestamp role remote-addr sumary detail] :as ev}]
  (let [colors #{"#028482" "#7ABA7A" "#B76EB8" "#6BCAE2" "#51A5BA" "#41924B" "#AFEAAA" "#87E293" "#FE8402"}
        role-colors (:role-colors state)
        color (or (get role-colors role)
                  (first (set/difference colors (into #{} (vals role-colors))))
                  "black")]
    (-> state
        (update-in [:events tap-id] (fn [tap-evs]
                                      (conj (or tap-evs (ring-buffer 1000))
                                            (assoc ev :color color)))) 
        (assoc-in [:role-colors role]  color))))

(defn taps-tree [taps]
  (let [taps-tr (reduce (fn [tree tap]
                          (let [path (vec (drop 2 (str/split (:id tap) #"/")))]
                            (assoc-in tree (conj path :data)  tap)))
                        {}
                        taps)
        make-tree (fn make-tree [tr]
                    (let [childs (map second (filter (comp string? first) tr))
                          data (second (first (filter (comp keyword? first) tr)))]
                      (if-not (empty? childs)
                        (assoc data :children (mapv make-tree childs))
                        data)))]
    (make-tree (vec taps-tr)))) 

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Crazy stuff to draw the tree using d3 ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn draw-taps-tree [taps-tr]
  (set! (.-innerHTML (.getElementById js/document "taps-tree")) "")
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

 
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Endo of Crazy stuff to draw the tree using d3 ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;



(defmulti event-msg-handler (fn [[ev-type ev-data]] ev-type))

(defmethod event-msg-handler :general/reporting
  [[_ ev-data]]
  (swap! app-state add-colored-event ev-data))

(defmethod event-msg-handler :taps/refreshed
  [[_ new-taps]]
  (swap! app-state assoc :taps new-taps)
  (draw-taps-tree (-> (:taps @app-state)
                      taps-tree
                      clj->js)))

(defmethod event-msg-handler :default
  [e]
  (println "Don't know how to handle " e))


(defn event-msg-handler* [{:keys [id ?data event]}]
  (println "EVENT! " event)
  (when (= id :chsk/recv)
    (event-msg-handler (second event))
    (let [[ev-type ev-data] (second event)])))
 
 


 
