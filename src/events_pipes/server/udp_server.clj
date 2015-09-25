(ns events-pipes.server.udp-server
  (:require [com.stuartsierra.component :as comp]
            [byte-streams :as bs]
            [manifold.stream :as ms]
            [aleph.udp :as udp]
            [events-pipes.server.core :as core]
            [clojure.walk :refer [keywordize-keys]]
            [clj-json.core :as json]
            [clojure.core.async :refer [chan sliding-buffer go go-loop <! >! <!! >!! mix toggle mult tap admix]]))

(defrecord UdpServer [server taps port]
  comp/Lifecycle
  (start [this]
    (println "Starting UdpServer component...")
    (let [udp-socket @(udp/socket {:port port})]
     (go-loop []
      (let [{:keys [host message]} @(ms/take! udp-socket)
            ev (try
                 (-> (bs/convert message String)
                   json/parse-string
                   keywordize-keys)
                 (catch Exception e
                   {:role "MALFORMED-UDP-INPUT-EVENT"
                    :summary (str "MALFORMED UDP INPUT EVENT from " host)
                    :detail (bs/convert message String)}))]
        (core/post-event! taps host ev)
        (recur)))
     (-> this
        (assoc :server udp-socket))))
  
  (stop [this]
    (println "Stoping UdpServer component...")
    ;; TODO We need a way of stopping the go-loop and shutting
    ;; down the UDP server
    this))
