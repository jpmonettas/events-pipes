(ns events-pipes.server
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [clojure.walk :refer [keywordize-keys]]
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :refer (sente-web-server-adapter)]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [clojure.core.async :refer [chan sliding-buffer go go-loop <! >! <!! >!! mix toggle]]
            [org.httpkit.server :as http-server]))


(let [{:keys [ch-recv send-fn ajax-post-fn ajax-get-or-ws-handshake-fn
              connected-uids]}
      (sente/make-channel-socket! sente-web-server-adapter {})]
  (def ring-ajax-post                ajax-post-fn)
  (def ring-ajax-get-or-ws-handshake ajax-get-or-ws-handshake-fn)
  (def ch-chsk                       ch-recv) ; ChannelSocket's receive channel
  (def chsk-send!                    send-fn) ; ChannelSocket's send API fn
  (def connected-uids                connected-uids) ; Watchable, read-only atom
  )

;; Events commint from outside the system arrives here
(def input-channel (chan (sliding-buffer 100)))

;; Every event put here will end up in the browser
(def output-channel (chan (sliding-buffer 100)))

;; This is the output-channel mixer
;; add with toggle to the mix channels you want in the output
(def output-mix (mix output-channel))


(defn post-event [remote-addr recived-event]
  (let [event-id (str (java.util.UUID/randomUUID))]
    (>!! input-channel [:general/reporting (-> recived-event
                                              (assoc :event-id event-id)
                                              (assoc :remote-addr remote-addr))]) 
                       {:status 200
                        :body {:success true
                               :event-id event-id}}))

(defroutes api-routes
  (POST "/event" req (post-event (:remote-addr req) (-> req keywordize-keys :body))))

(defroutes sente-routes
  (GET  "/chsk" req (ring-ajax-get-or-ws-handshake req))
  (POST "/chsk" req (ring-ajax-post req)))

(def my-app
  (routes
   (-> sente-routes
      ring.middleware.keyword-params/wrap-keyword-params
      ring.middleware.params/wrap-params)
   (-> api-routes
      (wrap-json-body)
      (wrap-json-response))))



(defn start []
  (http-server/run-server #'my-app {:port 7777 :join? false})
  
  (go-loop []
    (chsk-send! :sente/all-users-without-uid (<! output-channel))
    (recur)))
