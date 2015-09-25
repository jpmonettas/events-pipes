(ns events-pipes.server.repl-api
  (:require [events-pipes.server.core :as core]
            [events-pipes.server.web-server :as web-server]
            [clojure.pprint :as pp]
            [events-pipes.server.main :as m]))

(defn ls-ch []
  (pp/print-table [:id :transducer-form] (sort-by :id (vals @(:branches (:taps m/server-system))))))

(defn dump-taps [file]
  (spit file
        (with-out-str
          (->> 
           @(:branches (:taps m/server-system))
           vals
           (map #(select-keys % [:id :from-id :name :transducer-form]))
           pp/pprint))))

(defn load-taps
  "This will only work if your channels are empty (not intended for reload)"
  [file]
  (core/load-taps (:taps m/server-system) file)
  (web-server/notify-taps-change (:taps m/server-system)
                                 (-> m/server-system :web-server :sente-ch-socket)))


(defmacro add-ch [from-id name transducer-form]
  `(do
     (core/add-channel (:taps m/server-system) ~from-id ~name ~transducer-form (quote ~transducer-form))
     (web-server/notify-taps-change (:taps m/server-system)
                                    (-> m/server-system :web-server :sente-ch-socket))))


;; # For working with the repl
(comment


  (add-ch "/input"
          "errors"
          (filter (fn [{:keys [role]}] (= role "error"))))



  )
