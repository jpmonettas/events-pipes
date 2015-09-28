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

;; # Application state
;; We are keeping all the app state in this atom
(defonce app-state (reagent/atom {:state nil
                                  :taps []
                                  :ev-pattern #".*"
                                  :modal-open? false
                                  :taps-panel-open? true
                                  :chsk nil
                                  :selected nil
                                  :selected-tab "/input"
                                  :role-colors {}
                                  :events {}}))

;; Incomming channel messages router 
(defonce router_ (atom nil))

(declare event-msg-handler*)

(defn  stop-router! [] 
  (when-let [stop-f @router_] (stop-f)))

(defn start-router! [ch] 
  (stop-router!)
  (reset! router_ (sente/start-chsk-router! ch event-msg-handler*)))

(defn connect
  "Connect to server web socket and start the router"
  [server]
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

(defn add-colored-event
  "Given a state and an event, choose a color for the event and add it to the state.
  We keep track of already choosed colors so we can assign a new color to each new role."
  [state {:keys [event-id tap-id timestamp role remote-addr sumary detail] :as ev}]
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

;; # Incomming events handling
;; Here we handle all the events that are comming from the server

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





