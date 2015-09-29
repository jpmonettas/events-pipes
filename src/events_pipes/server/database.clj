;; This namespace deals with :

;; - Connecting to a elasticsearch db
;; - Running a process that will read events from taps :db-ch and publish them en ES
(ns events-pipes.server.database
  (:require [clojurewerkz.elastisch.native :as es]
            [clojurewerkz.elastisch.native.document :as esdoc]
            [com.stuartsierra.component :as comp]
            [clojure.core.async :refer [chan sliding-buffer go-loop <! tap]]
            [clj-time.format :as tf]
            [clj-json.core :as json]
            [clj-time.coerce :as tc]))


(defrecord Database [conn taps]
  
  comp/Lifecycle

  (start [this]
    (println "Starting Database component...")
    (let [connection (es/connect  [["127.0.0.1" 9300]]
                                  {"cluster.name" "elasticsearch"})
          dtf (tf/formatters :date-time)
          events-ch (:db-ch taps)]

      ;; If the connection get closed, it will throw an exception and
      ;; the process will die
      (go-loop []
        (let [ev (<! events-ch)]
          (esdoc/create connection "events" "event" (-> ev
                                                       (update-in [:timestamp] #(tf/unparse dtf (tc/from-date %)))
                                                       (update-in [:detail] json/generate-string)))          
          (when ev (recur))))
      
      (-> this
         (assoc :conn connection))))
  
  (stop [this]
    (println "Stoping Database component...")
    (dissoc this :conn)))
