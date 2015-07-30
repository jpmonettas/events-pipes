(ns ^:figwheel-always events-pipes.client
    (:require-macros
     [cljs.core.async.macros :as asyncm :refer (go go-loop)])
    (:require [reagent.core :as reagent :refer [atom]]
              [cljs.core.async :as async :refer (<! >! put! chan timeout)]
              [taoensso.sente  :as sente :refer (cb-success?)]
              [clojure.set :as set]
              [cljs-time.format :as tf]
              [cljs-time.coerce :as tc]
              [cljsjs.highlight :as hl]
              [cljsjs.highlight.langs.clojure :as hlc]
              [cljs.pprint :refer [pprint]]))
 
(enable-console-print!)


(defonce app-state (atom {:state nil
                          :taps []
                          :ev-pattern #".*"
                          :modal-open? false
                          :chsk nil
                          :selected 0
                          :role-colors {}
                          :events ()}))

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
(defn select-event [idx]
  (swap! app-state (fn [s] (-> s
                               (assoc :selected idx)
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
  (swap! app-state merge {:selected 0 :role-colors {} :events []})) 



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

(defn taps [ts]
  [:div.taps
   (for [t ts]
     (if (:muted? t)
       [:button.btn.btn-danger {:key (:id t) :on-click #(toggle-tap (:id t))}
        [:span.glyphicon.glyphicon-eye-close] 
        (:id t)]
       [:button.btn.btn-success {:key (:id t) :on-click #(toggle-tap (:id t))}
        [:span.glyphicon.glyphicon-eye-open]
        (:id t)]))])

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
                                     :on-change #(search-changed %)}]]
       [taps (:taps @app-state)]])))


  
(defn ui []
  [:div 
   [:div#header
    [header]
    (when (:modal-open? @app-state)
      (let [ev (nth (:events @app-state) (:selected @app-state))]
          [:div.last-event {:on-click #(when (:modal-open? @app-state) (close-modal))}
           [:div.summary (:summary ev)]
           (when (:detail ev)
             [:pre.code 
              [selected-event ev]])]))]
   [:div#events
    [:ul (doall
          (map-indexed
           (fn [idx {:keys [timestamp remote-addr role color summary detail]}]
             [:li {:key idx
                   :style {:display (if (re-matches (:ev-pattern @app-state) (str role remote-addr summary detail))
                                      :block
                                      :none)} 
                   :on-click #(select-event idx)}
              [:span.timestamp {:style {:background-color color}} (tf/unparse (tf/formatter "HH:mm:ss.SSS")
                                                                              (tc/from-long (.getTime timestamp)))] 
              [:span.ip {:style {:background-color color}} remote-addr]
              [:span.role {:style {:background-color color}} role]
              [:span.content {:style {:background-color color}} (str summary)]])  
           (:events @app-state)))]]])  

(reagent/render-component [ui]
                          (. js/document (getElementById "app"))) 


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Incomming events handling  ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; 

(defn add-colored-event [state {:keys [event-id timestamp role remote-addr sumary detail] :as ev}]
  (let [colors #{"#028482" "#7ABA7A" "#B76EB8" "#6BCAE2" "#51A5BA" "#41924B" "#AFEAAA" "#87E293" "#FE8402"}
        role-colors (:role-colors state)
        color (or (get role-colors role)
                  (first (set/difference colors (into #{} (vals role-colors))))
                  "black")]
    (-> state
        (update-in [:events] conj (assoc ev :color color)) 
        (assoc-in [:role-colors role]  color))))

(defmulti event-msg-handler (fn [[ev-type ev-data]] ev-type))

(defmethod event-msg-handler :general/reporting
  [[_ ev-data]]
  (swap! app-state add-colored-event ev-data))

(defmethod event-msg-handler :taps/refreshed
  [[_ new-taps]]
  (swap! app-state assoc :taps new-taps))

(defmethod event-msg-handler :default
  [e]
  (println "Don't know how to handle " e))


(defn event-msg-handler* [{:keys [id ?data event]}]
  (println "EVENT! " event)
  (when (= id :chsk/recv)
    (event-msg-handler (second event))
    (let [[ev-type ev-data] (second event)])))
 
 


 
