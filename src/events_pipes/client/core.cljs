(ns ^:figwheel-always events-pipes.client.core
    (:require-macros [cljs.core.async.macros :as asyncm :refer (go go-loop)])
    (:require [clojure.string :as str]
              [reagent.core :as reagent]
              [taoensso.sente  :as sente :refer (cb-success?)]
              [com.stuartsierra.component :as comp]
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
    {:state (-> state
                (update-in [:events tap-id] (fn [tap-evs]
                                              (conj (or tap-evs (ring-buffer 1000))
                                                    (assoc ev :color color)))) 
                (assoc-in [:role-colors role]  color))}))

(defmethod action-reducer :taps-refresh

  [state {new-taps :data}]

  {:state (assoc state :taps new-taps)})


(defmethod action-reducer :connect

  [state {server :data}]

  {:state state
   :tasks [{:task :ws-connect
            :data server}]})

(defmethod action-reducer :toggle-tap

  [state {tap-id :data}]

  {:state state
   :tasks [{:task :ws-send
            :data [:taps/tap-toggled tap-id]}]})

(defmethod action-reducer :select-event

  [state {ev :data}]

  {:state (-> state
              (assoc :selected ev)
              (assoc :modal-open? true))})

(defmethod action-reducer :close-modal

  [state _]

  {:state (assoc state :modal-open? false)})



(defmethod action-reducer :clear-events

  [state _]

  {:state (merge state {:selected 0 :role-colors {} :events {}})})

(defmethod action-reducer :select-tab

  [state {tab-id :data}]

  {:state (assoc state :selected-tab tab-id)})

(defmethod action-reducer :remove-tab

  [state {tab-id :data}]

  {:state (update-in state [:events] dissoc tab-id)})

(defmethod action-reducer :toggle-taps-panel

  [state _]
  {:state (update-in state [:taps-panel-open?] #(not %))})

(defmethod action-reducer :search-changed

  [state {pattern :data}]

  {:state (assoc state :ev-pattern (re-pattern (str ".*" pattern ".*")))})

(defmethod action-reducer :ws-ready

  [state _]

  {:state (assoc state :connected? true)
   :tasks [{:task :ws-send
            :data [:taps/please-refresh]}]})

(defmethod action-reducer :default
  [_ a]
  (println "Action " a " not implemented"))


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






