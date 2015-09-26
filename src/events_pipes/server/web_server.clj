;; This namespace deals with :

;; - Running a web server for
;;   - exposing an api for publishing events via POST
;;   - exposing a web socket for recieving events
;; - Running a process tu push every recieved event thru Taps
;; - Routing events recieved thru the web socket
(ns events-pipes.server.web-server
  (:require [events-pipes.server.core :as core]
            [clojure.walk :refer [keywordize-keys]]
            [compojure.core :refer :all]
            [clojure.core.async :refer [chan sliding-buffer go go-loop <! >! <!! >!! mix toggle mult tap admix]]
            [org.httpkit.server :as http-server]
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :refer [sente-web-server-adapter]]
            [compojure.route :as cr]
            [compojure.route :as route]
            [com.stuartsierra.component :as comp]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.cors :refer [wrap-cors]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]))


(defn wrap-components
  "Inject all components in the request, so we can do it without global defs"
  [handler taps sente-ch-socket]
  (fn [request]
    (let [injected-request (-> request
                              (assoc :taps taps))]
      (handler injected-request))))

(defn wrap-sente-ch-socket
  "Inject sente-ch-socket in the request , so we can do it without global defs"
  [handler sente-ch-socket]
  (fn [request]
    (let [injected-request (assoc request :sente-ch-socket sente-ch-socket)]
      (handler injected-request))))

;; API for publishing events
(defroutes api-routes-handler
  (POST "/event" req {:status 200
                      :body {:success true
                             :event-id (core/post-event! (:taps req)
                                                         (:remote-addr req)
                                                         (-> req keywordize-keys :body))}} ))

;; Web socket
(defroutes sente-routes-handler
  (GET  "/chsk" req (let [ring-ajax-get-or-ws-handshake (-> req :sente-ch-socket :ajax-get-or-ws-handshake-fn)]
                      (ring-ajax-get-or-ws-handshake req)))
  (POST "/chsk" req (let [ring-ajax-post (-> req :sente-ch-socket :ajax-post-fn)]
                      (ring-ajax-post req))))

(defn create-web-server-handler [taps sente-ch-socket]
  (routes
   (-> #'sente-routes-handler
      ring.middleware.keyword-params/wrap-keyword-params
      ring.middleware.params/wrap-params
      (wrap-sente-ch-socket sente-ch-socket))
   
   (-> #'api-routes-handler
      (wrap-cors :access-control-allow-origin [#".*"]
                 :access-control-allow-methods [:post])
      (wrap-json-body)
      (wrap-json-response)
      (wrap-components taps sente-ch-socket))
   (cr/resources "/")))

(defn notify-taps-change
  "Send taps status thru the web sockets, so monitors can update their UI
  if something changes.
  It will send [:taps/refreshed {:id :name :muted}]"
  [taps {:keys [send-fn]}]
  (send-fn  :sente/all-users-without-uid
            [:taps/refreshed (map #(select-keys % [:id :name :muted?])
                                  (vals @(:branches taps)))]))

;; Every recieved event will have taps, socket and event type and data
;; We will dispatch on event type
(defmulti event-msg-handler (fn [_ _ [ev-type ev-data]] ev-type))

;; Toggle the tap on/off
(defmethod event-msg-handler :taps/tap-toggled
  [taps sente-ch-socket [_ tap-id]]
  (core/toggle-mix taps tap-id)
  (notify-taps-change taps sente-ch-socket))

;; Web socket is asking for taps info refresh
(defmethod event-msg-handler :taps/please-refresh
  [taps sente-ch-socket _]
  (notify-taps-change taps sente-ch-socket))

(defmethod event-msg-handler :default [_ _ _] nil)

(defn event-msg-handler* [taps sente-ch-socket {:keys [id ?data event]}]
  (event-msg-handler taps sente-ch-socket event))

;; The web server component is responsible for starting and shooting down :

;; - A web server
;; - A process that will inject every recieved event thru the taps tree
;; - A router that will route incomming messages from the web socket
(defrecord WebServer [server router taps port sente-ch-socket]
  
  comp/Lifecycle

  (start [this]
    (println "Starting WebServer component...")
    (let [sente-ch-socket (sente/make-channel-socket! sente-web-server-adapter {})
          send-fn! (:send-fn sente-ch-socket)
          ch-recv (:ch-recv sente-ch-socket)
          output-channel (:output-ch taps)
          server (http-server/run-server (create-web-server-handler taps sente-ch-socket)
                                         {:port port :join? false})]

      ;; If the channel gets closed the proccess will end
      (go-loop []
        (let [ev (<! output-channel)]
          (send-fn! :sente/all-users-without-uid [:general/reporting ev])
          (when ev (recur))))
      
      (-> this
         (assoc :server server)
         (assoc :sente-ch-socket sente-ch-socket)
         (assoc :router (sente/start-chsk-router! ch-recv (partial event-msg-handler* taps sente-ch-socket))))))
  
  (stop [this]
    (println "Stoping WebServer component...")
    ;; Stop the server by just calling the funciton
    (server)
    ;; Stop the router by just calling the function
    (router)
    ;; TODO We need a way to stop the go-loop process
    (dissoc this :server)))
