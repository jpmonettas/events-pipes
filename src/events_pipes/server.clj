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
            [clojure.tools.nrepl.server :as nrepl]
            [clojure.pprint :as pp]
            [cider.nrepl :refer [cider-nrepl-handler]]
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :refer (sente-web-server-adapter)])
  (:import [java.util Date])
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

(def nrepl-server nil)

;; Events commint from outside the system arrives here
(def input-channel (chan (sliding-buffer 100) (map #(assoc % :tap-id "/input"))))

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


(defmacro add-ch [from-id name transducer-form]
  `(add-channel ~from-id ~name ~transducer-form (quote ~transducer-form)))

(defn add-channel [from-id name transducer transducer-form]
  (let [new-id (str (get-in @channels [from-id :id]) "/" (str/lower-case name))
        from-mult (get-in @channels [from-id :mult])
        ch (chan (sliding-buffer 100) (comp
                                       (map #(assoc % :tap-id new-id))
                                       transducer))
        ch-mult (mult ch)
        mix-ch (chan (sliding-buffer 100))]
    (swap! channels assoc new-id {:id new-id
                                  :from-id from-id
                                  :transducer-form transducer-form
                                  :mix-channel mix-ch
                                  :mult ch-mult
                                  :name name
                                  :muted? true})
    (tap from-mult ch)
    (tap ch-mult mix-ch)
    (toggle output-mix {mix-ch {:mute true}})
    (notify-taps-change)
    new-id))


(defn post-event [remote-addr recived-event]
  (let [event-id (str (java.util.UUID/randomUUID))]
    (>!! input-channel (-> recived-event
                          (assoc :event-id event-id)
                          (assoc :remote-addr (if (or (= remote-addr "127.0.0.1")
                                                     (= remote-addr "0:0:0:0:0:0:0:1"))
                                                "localhost"
                                                remote-addr))
                          (assoc :timestamp (Date.)))) 
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

(defn start-nrepl-server []
  (alter-var-root #'nrepl-server
                  (fn [s] (nrepl/start-server :handler cider-nrepl-handler :port 7778))))

(defn start-router! []
  (stop-router!)
  (reset! router_ (sente/start-chsk-router! ch-chsk event-msg-handler*)))

(defn reset-channels []
  (reset! channels (let [ch-mult (mult input-channel)]
                     {"/input"
                      {:id "/input"
                       :mix-channel (tap ch-mult (chan (sliding-buffer 10)))
                       :mult ch-mult
                       :name "input"
                       :muted? true}}))

  (toggle output-mix {(get-in @channels ["/input" :mix-channel]) {:mute true}}))

(defn start []
  
  (reset-channels)
  
  (http-server/run-server #'my-app {:port 7777 :join? false})

  (start-router!)
  
  (go-loop []
    (chsk-send! :sente/all-users-without-uid [:general/reporting (<! output-channel)])
    (recur)))

(defn ls-ch []
  (pp/print-table [:id :transducer-form] (sort-by :id (vals @channels))))

(defn dump-taps [file]
  (spit file
        (with-out-str
          (->> 
           @channels
           vals
           (map #(select-keys % [:id :from-id :name :transducer-form]))
           pp/pprint))))

(defn load-taps
  "This will only work if your channels are empty (not intended for reload)"
  [file]
  (doseq [t (->> file
               slurp
               read-string
               (remove #(= (:id %) "/input"))
               (sort-by :id))]
    
    (add-channel (:from-id t)
                 (:name t)
                 (eval (:transducer-form t))
                 (:transducer-form t))))

(defn -main
  [& [taps-file & _]]
  (start-nrepl-server)
  
  (start)

  (when taps-file
    (load-taps taps-file))
  
  ;; Setup hardcoded channels
  #_(add-ch "/input"
            "errors"
            (filter (fn [{:keys [role]}] (= role "error"))))

  #_(add-ch "/input/errors"
            "super"
            (filter (fn [{:keys [summary]}] (re-matches #".*super.*" summary))))
  
  
  
  (println "Done! Point your browser to http://this-box:7777/index.html")
  (println "You also have an nrepl server at 7778"))
