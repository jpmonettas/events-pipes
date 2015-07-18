(ns events-pipes.server
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [clojure.walk :refer [keywordize-keys]]
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :refer (sente-web-server-adapter)]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
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



(defroutes api-routes
  (POST "/event" req (let [event-id (str (java.util.UUID/randomUUID))]
                       (chsk-send! :sente/all-users-without-uid [:general/reporting (assoc (-> req keywordize-keys :body)
                                                                                           :event-id event-id)] )
                       {:status 200
                        :body {:success true
                               :event-id event-id}}))
  )

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


(defn start-server []
  (http-server/run-server #'my-app {:port 7777 :join? false}))
