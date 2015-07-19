(ns ^:figwheel-always events-pipes.client
    (:require-macros
     [cljs.core.async.macros :as asyncm :refer (go go-loop)])
    (:require [reagent.core :as reagent :refer [atom]]
              [cljs.core.async :as async :refer (<! >! put! chan)]
              [taoensso.sente  :as sente :refer (cb-success?)]
              [clojure.set :as set]
              [cljsjs.highlight :as hl]
              [cljsjs.highlight.langs.clojure :as hlc]
              [cljs.pprint :refer [pprint]]))
 
(enable-console-print!)


(defonce app-state (atom {:state (atom nil)
                          :chsk nil
                          :selected 0
                          :role-colors {}
                          :events []}))

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
  (swap! app-state assoc :selected idx))

(defn connect [server]
  (let [{:keys [chsk ch-recv send-fn state]}
        (sente/make-channel-socket! "/chsk" ; Note the same path as before
                                    {:type :ws ; e/o #{:auto :ajax :ws}
                                     :chsk-url-fn (constantly (str "ws://" server "/chsk"))})]

    (stop-router!)
    (swap! app-state #(-> % (assoc :state state)))

    (start-router! ch-recv)))

;; Components
;; ----------
(def selected-event
  (with-meta
    (fn [ev]
      (when ev [:code {:class "clojure"} (with-out-str (-> ev second pprint))]))
     {:component-did-update
                     (fn [this old-props old-childs]
                       (.highlightBlock js/hljs (reagent/dom-node this)))})) 




(defn connection [connected]
  (let [server-address (atom nil)]
    (fn [props]
      [:div
       [:label "Sever:"]
       [:input {:type :text
                :on-change #(reset! server-address (-> % .-target .-value))}]
       [:button {:on-click #(connect @server-address)} "Connect"]
       [:span (if (-> @app-state :state deref :open?) "Connected" "Not Connected")]])))

(defn ui []
  [:div
   [connection]
   [:div.last-event
    [:pre
     [selected-event (get (:events @app-state) (:selected @app-state))]]]
   [:ul (map-indexed
         (fn [idx [color content]]
           [:li {:key idx
                 :style {:background-color color}
                 :on-click #(select-event idx)} (str content)]) 
         (:events @app-state))]]) 

(reagent/render-component [ui]
                          (. js/document (getElementById "app"))) 


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Incomming events handling  ;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; 

(defn add-colored-event [state {:keys [event-id role content]}]
  (let [colors #{"#028482" "#7ABA7A" "#B76EB8" "#6BCAE2" "#51A5BA" "#41924B" "#AFEAAA" "#87E293" "#FE8402"}
        role-colors (:role-colors state)
        color (or (get role-colors role)
                  (first (set/difference colors (into #{} (vals role-colors))))
                  "black")]
    (-> state
        (update-in [:events] conj [color content ]) 
        (assoc-in [:role-colors role]  color))))

(defn event-msg-handler* [{:keys [id ?data event]}]
  (println "EVENT! " event)
  (when (= id :chsk/recv)
    (let [[ev-type ev-data] (second event)]
      (swap! app-state add-colored-event ev-data))))
 
 


 
