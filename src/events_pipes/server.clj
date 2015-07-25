(ns events-pipes.server
  (:require [clojure.core.async :refer [chan sliding-buffer go go-loop <! >! <!! >!! mix toggle mult tap admix]]
            [clojure.string :as str]
            [clojure.walk :refer [keywordize-keys]]
            [compojure.core :refer :all]
            [compojure.route :as cr]
            [compojure.route :as route]
            [org.httpkit.server :as http-server]
            [ring.middleware.json :refer [wrap-json-response wrap-json-body]]
            [ring.middleware.keyword-params :refer [wrap-keyword-params]]
            [ring.middleware.params :refer [wrap-params]]
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :refer (sente-web-server-adapter)])
  (:gen-class))


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

(def channels (atom nil))

(defonce router_ (atom nil))

(defn notify-taps-change []
  (chsk-send! :sente/all-users-without-uid [:taps/refreshed (map #(select-keys % [:id :name :muted?])(vals @channels))]))

(defn toggle-mix [ch-id]
  (swap! channels (fn [chans]
                    ;; toggle the mute, then set in state and mix
                    (let [toggled-mute? (not (get-in chans [ch-id :muted?]))]
                      (toggle output-mix {(get-in chans [ch-id :mix-channel]) {:mute toggled-mute?}})
                      (assoc-in chans [ch-id :muted?] toggled-mute?))))
  (notify-taps-change))



 
(defn add-channel [ch from-id name]
  (let [new-id (str (get-in @channels [from-id :id]) "/" (str/lower-case name))
        from-mult (get-in @channels [from-id :mult])
        ch-mult (mult ch)
        mix-ch (chan (sliding-buffer 100))]
    (swap! channels assoc new-id {:id new-id
                                  :mix-channel mix-ch
                                  :mult ch-mult
                                  :name name
                                  :muted? false})
    (tap from-mult ch)
    (tap ch-mult mix-ch)
    (admix output-mix mix-ch)
    (notify-taps-change)
    new-id))


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
      (wrap-json-response))
   (cr/resources "/")))

;; (def ^:dynamic *repl-out* *out*)
;; (binding [*out* *repl-out*]
;;   (println " EVENT " event))

(defmulti event-msg-handler (fn [[ev-type ev-data]] ev-type))


(defmethod event-msg-handler :taps/tap-toggled
  [[_ tap-id]]
  (toggle-mix tap-id))

(defmethod event-msg-handler :taps/please-refresh
  [_]
  (notify-taps-change))

(defmethod event-msg-handler :default [e] nil)

(defn event-msg-handler* [{:keys [id ?data event]}]
  (event-msg-handler event))

(defn  stop-router! [] (when-let [stop-f @router_] (stop-f)))

(defn start-router! []
  (stop-router!)
  (reset! router_ (sente/start-chsk-router! ch-chsk event-msg-handler*)))

(defn start []
  (reset! channels (let [ch-mult (mult input-channel)]
                     {"/input"
                      {:id "/input"
                       :mix-channel (tap ch-mult (chan (sliding-buffer 10)))
                       :mult ch-mult
                       :name "input"
                       :muted? false}}))

  (admix output-mix (get-in @channels ["/input" :mix-channel]))
  
  (http-server/run-server #'my-app {:port 7777 :join? false})

  (start-router!)
  
  (go-loop []
    (chsk-send! :sente/all-users-without-uid (<! output-channel))
    (recur)))

(defn -main
  [& args]
  ;; Load system configuration using any environment method like
  ;; java -jar kiosko.jar /home/config.clj
  
  (start)

  ;; Setup hardcoded channels
  #_(add-channel (chan (sliding-buffer 100)
                     (filter (fn [[_ {:keys [role]}]] (= role "error"))))
               "/input"
               "errors")

  #_(add-channel (chan (sliding-buffer 100)
                       (filter (fn [[_ {:keys [role content]}]] (re-matches #".*super.*" content))))
               "/input/errors"
               "super")
  
  (println "Done! Point your browser to http://localhost:7777/index.html"))
