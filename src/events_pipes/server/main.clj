;; This namespace deals with starting and shutting down the whole system
;; It will also start an nrepl server, so we can connect from outside to add
;; taps
(ns events-pipes.server.main
  (:require [events-pipes.server.core :as core]
            [events-pipes.server.web-server :as web-server]
            [events-pipes.server.udp-server :as udp-server]
            [events-pipes.server.database :as database]
            [cider.nrepl :refer [cider-nrepl-handler]]
            [com.stuartsierra.component :as comp]
            [clojure.tools.nrepl.server :as nrepl])
  (:gen-class))

(def server-system nil)
(def nrepl-server nil)

(defn start-nrepl-server [nrepl-port]
  (alter-var-root #'nrepl-server
                  (fn [s] (nrepl/start-server :handler cider-nrepl-handler :port nrepl-port))))

(defn create-server-system [port]
  (comp/system-map
   :taps (core/map->Taps {})
   :udp-server (comp/using (udp-server/map->UdpServer {:port port})
                           [:taps])
   :web-server (comp/using (web-server/map->WebServer {:port port})
                           [:taps])
   :database (comp/using (database/map->Database {})
                           [:taps])))

 
(defn start-server-system [port]
  (println "Starting server system")
  (alter-var-root #'server-system (fn [s] (comp/start (create-server-system port)))))

(defn stop-server-system []
  (println "Stopping server system")
  (alter-var-root #'server-system
                  (fn [s] (when s (comp/stop s)))))

(defn restart
  ([] (restart nil))
  ([taps-file]
   (when server-system (stop-server-system))
   (start-server-system 7777)))

(defn -main
  [& [port nrepl-port taps-file & _]]

  (when (or (nil? port) (nil? nrepl-port))
    (println "Usage: java -jar events.jar port nrepl-port [taps-file]")
    (System/exit 1))
  
  (start-nrepl-server (read-string nrepl-port))
  
  (start-server-system (read-string port))

  (when taps-file
    (core/load-taps (:taps server-system) taps-file))
  
  (web-server/notify-taps-change (:taps server-system) (-> server-system :web-server :sente-ch-socket))

  (println (format "Receiving events on POST to http://this-box:%s/event or UDP datagram on %s" port port)) 
  (println (format "You can read events connecting to ws://this-box:%s/chsk" port)) 
  (println (format "Done! Point your browser to http://this-box:%s/index.html" port))
  (println (format "You also have an nrepl server at %s" nrepl-port)))
