(ns ^:figwheel-always events-pipes.client.core
    (:require-macros [cljs.core.async.macros :as asyncm :refer (go go-loop)])
    (:require [clojure.string :as str]
              [reagent.core :as reagent]
              [taoensso.sente  :as sente :refer (cb-success?)]
              [clojure.set :as set]
              [cljs.pprint :refer [pprint]]
              [cljs.core.async :as async :refer (<! >! put! chan timeout)]
              [amalloy.ring-buffer :refer [ring-buffer]]))

;; So we can use (println) and it ends up in the js/console
(enable-console-print!)

(declare event-msg-handler*)

;; action {:type :add-event :data {}}

(defmulti action-reducer (fn [state action] (:type action)))

(defmethod action-reducer :add-event
  [state {:keys [data]}]
  (let [{:keys [event-id tap-id timestamp role remote-addr sumary detail] :as ev} data
        colors #{"#028482" "#7ABA7A" "#B76EB8" "#6BCAE2" "#51A5BA" "#41924B" "#AFEAAA" "#87E293" "#FE8402"}
        role-colors (:role-colors state)
        color (or (get role-colors role)
                  (first (set/difference colors (into #{} (vals role-colors))))
                  "black")]
    (-> state
        (update-in [:events tap-id] (fn [tap-evs]
                                      (conj (or tap-evs (ring-buffer 1000))
                                            (assoc ev :color color)))) 
        (assoc-in [:role-colors role]  color))))

(defmethod action-reducer :taps-refresh

  [state {new-taps :data}]

  (-> state
      (assoc :taps new-taps)))


(defmethod action-reducer :connect

  [state {server :data}]

  (let [stop-router! (fn [] (when-let [stop-f @(-> state :ws :router)] (stop-f)))
        start-router! (fn [ch] 
                        (stop-router!)
                        (reset! (-> state :ws :router)
                                (sente/start-chsk-router! ch event-msg-handler*)))
        socket (sente/make-channel-socket! "/chsk" ; Note the same path as before
                                    {:type :ws ; e/o #{:auto :ajax :ws}
                                     :chsk-url-fn (constantly (str "ws://" server "/chsk"))})
        chsk (:chsk socket)
        ch-recv (:ch-recv socket)
        send-fn (:send-fn socket)
        ws-state (:state socket)]

    (stop-router!)
    
    (start-router! ch-recv)

    ;; Wait some time for the channels to connect, then ask for a refresh
    (go (<! (timeout 2000))
        (send-fn [:taps/please-refresh]))

    (-> state
        (assoc-in [:ws :ws-state] ws-state)
        (assoc-in [:ws :send-fn] send-fn))))

(defmethod action-reducer :select-event

  [state {ev :data}]

  (-> state
      (assoc :selected ev)
      (assoc :modal-open? true)))

(defmethod action-reducer :close-modal

  [state _]

  (-> state
      (assoc :modal-open? false)))

(defmethod action-reducer :toggle-tap

  [state {tap-id :data}]
  (let [chsk-send! (-> state :ws :send-fn)]
    (chsk-send! [:taps/tap-toggled tap-id])
    state))

(defmethod action-reducer :clear-events

  [state _]

  (-> state
      (merge {:selected 0 :role-colors {} :events {}})))

(defmethod action-reducer :select-tab

  [state {tab-id :data}]

  (-> state
      (assoc :selected-tab tab-id)))

(defmethod action-reducer :remove-tab

  [state {tab-id :data}]

  (-> state
      (update-in [:events] dissoc tab-id)))

(defmethod action-reducer :toggle-taps-panel

  [state _]
  (-> state
      (update-in [:taps-panel-open?] #(not %))))

(defmethod action-reducer :search-changed

  [state {pattern :data}]

  (-> state
      (assoc :ev-pattern (re-pattern (str ".*" pattern ".*")))))

(defmethod action-reducer :default
  [_ a]
  (println "Action " a " not implemented"))

(defprotocol Dispatcher
  (dispatch [this action]))

(defprotocol Query
  (get-state [this]))

(defrecord AppState [state]
  Dispatcher
  (dispatch [this action]
    (swap! state action-reducer action))

  Query
  (get-state [this] state))

(defn build-app-state []
  (->AppState (reagent/atom {:ws {:ws-state nil
                                  :send-fn nil
                                  :router (atom nil)}
                             :taps []
                             :ev-pattern #".*"
                             :modal-open? false
                             :taps-panel-open? true
                             :chsk nil
                             :selected nil
                             :selected-tab "/input"
                             :role-colors {}
                             :events {}})))

(defonce app-state (build-app-state))


(defn taps-tree
  "Given a taps vector return a taps tree using taps id to figure out hierarchy"
  [taps]
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

;; # Incomming events handling from the socket
;; Here we handle all the events that are comming from the server

(defmulti event-msg-handler (fn [[ev-type ev-data]] ev-type))

(defmethod event-msg-handler :general/reporting
  [[_ ev-data]]
  (dispatch app-state {:type :add-event :data ev-data}))

(defmethod event-msg-handler :taps/refreshed
  [[_ new-taps]]
  (dispatch app-state {:type :taps-refresh :data new-taps}))

(defmethod event-msg-handler :default
  [e]
  (println "Don't know how to handle " e))


(defn event-msg-handler* [{:keys [id ?data event]}]
  (println "EVENT! " event)
  (when (= id :chsk/recv)
    (event-msg-handler (second event))
    (let [[ev-type ev-data] (second event)])))





