(ns events-pipes.server.core
  (:require [clojure.core.async :refer [chan sliding-buffer go go-loop <! >! <!! >!! mix toggle mult tap admix]]
            [clojure.string :as str]
            [clojure.walk :refer [keywordize-keys]]
            [compojure.core :refer :all]
            [compojure.route :as cr]
            [compojure.route :as route]
            
            [clojure.tools.nrepl.server :as nrepl]
            [clojure.pprint :as pp]
            [aleph.udp :as udp]
            [manifold.stream :as ms]
            [byte-streams :as bs]
            
            [com.stuartsierra.component :as comp])
  (:import [java.util Date])
  (:gen-class))



(defn toggle-mix [taps ch-id]
  (swap! (:branches taps) (fn [chans]
                            ;; toggle the mute, then set in state and mix
                            (let [toggled-mute? (not (get-in chans [ch-id :muted?]))]
                              (toggle (:output-mix taps) {(get-in chans [ch-id :tap-channel]) {:mute toggled-mute?}})
                              (assoc-in chans [ch-id :muted?] toggled-mute?)))))


(defn add-channel [taps from-id name transducer transducer-form]
  (let [new-id (str (get-in @(:branches taps) [from-id :id]) "/" (str/lower-case name))
        from-mult (get-in @(:branches taps) [from-id :mult])
        ch (chan (sliding-buffer 100) (comp
                                       (map #(assoc % :tap-id new-id))
                                       transducer))
        ch-mult (mult ch)
        mix-ch (chan (sliding-buffer 100))]
    (swap! (:branches taps) assoc new-id {:id new-id
                                              :from-id from-id
                                              :transducer-form transducer-form
                                              :tap-channel mix-ch
                                              :mult ch-mult
                                              :name name
                                              :muted? true})
    (tap from-mult ch)
    (tap ch-mult mix-ch)
    (toggle (:output-mix taps) {mix-ch {:mute true}})
    new-id))


(defn post-event! [taps remote-addr recived-event]
  (let [event-id (str (java.util.UUID/randomUUID))]
    (>!! (:input-ch taps) (-> recived-event
                             (assoc :event-id event-id)
                             (assoc :remote-addr (if (or (= remote-addr "127.0.0.1")
                                                        (= remote-addr "0:0:0:0:0:0:0:1"))
                                                   "localhost"
                                                   remote-addr))
                             (assoc :timestamp (Date.)))) 
    event-id))


(defn load-taps
  "This will only work if your channels are empty (not intended for reload)"
  [taps file]
  (doseq [t (->> file
               slurp
               read-string
               (remove #(= (:id %) "/input"))
               (sort-by :id))]
    
    (add-channel taps
                 (:from-id t)
                 (:name t)
                 (eval (:transducer-form t))
                 (:transducer-form t))))

(defrecord Taps [input-ch output-ch output-mix branches]
  comp/Lifecycle
  (start [this]
    (println "Starting Taps component...")
    (let [input-ch (chan (sliding-buffer 100) (map #(assoc % :tap-id "/input")))
          output-ch (chan (sliding-buffer 100))
          input-mult (mult input-ch)
          output-mix (mix output-ch)
          input-tap (tap input-mult (chan (sliding-buffer 10)))
          initialized-this (-> this
                              (assoc :input-ch input-ch)
                              (assoc :output-ch output-ch)
                              (assoc :output-mix output-mix)
                              (assoc :branches (atom {"/input"
                                                      {:id "/input"
                                                       :tap-channel input-tap
                                                       :mult input-mult
                                                       :name "input"
                                                       :muted? true}})))]
      (toggle output-mix {input-tap {:mute true}})
      initialized-this))
  
  (stop [this]
    (println "Stoping Taps component...")
    this))


