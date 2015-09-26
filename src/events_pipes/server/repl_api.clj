;; This namespace deals with functions intended to be used from the repl
;; A small api to work with taps thru the repl

(ns events-pipes.server.repl-api
  (:require [events-pipes.server.core :as core]
            [events-pipes.server.web-server :as web-server]
            [clojure.pprint :as pp]
            [events-pipes.server.main :as m]))

(defn ls-ch
  "List all the taps"
  []
  (pp/print-table [:id :transducer-form] (sort-by :id (vals @(:branches (:taps m/server-system))))))

(defn dump-taps
  "Dump taps to a file so they can be restored with load-taps"
  [file]
  (spit file
        (with-out-str
          (->> 
           @(:branches (:taps m/server-system))
           vals
           (map #(select-keys % [:id :from-id :name :transducer-form]))
           pp/pprint))))

(defn load-taps
  "Load taps from a file created by dump-taps
  This will only work if your channels are empty (not intended for reload)"
  [file]
  (core/load-taps (:taps m/server-system) file)
  (web-server/notify-taps-change (:taps m/server-system)
                                 (-> m/server-system :web-server :sente-ch-socket)))


(defmacro add-tap
  "Add a tap to the system"
  [from-id name transducer-form]
  `(do
     (core/add-tap (:taps m/server-system) ~from-id ~name ~transducer-form (quote ~transducer-form))
     (web-server/notify-taps-change (:taps m/server-system)
                                    (-> m/server-system :web-server :sente-ch-socket))))


;; # For working with the repl
(comment


  (add-ch "/input"
          "errors"
          (filter (fn [{:keys [role]}] (= role "error"))))



  )
