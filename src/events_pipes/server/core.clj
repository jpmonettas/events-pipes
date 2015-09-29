;; ![Tap](./server.png)
;; This namespace implements all events tree(Taps) wiring.
;; **Important** :

;; - Every tap has a sliding buffer so if there are more events arriving than we are able to consume, olders will be dropped.
;; - Every tap has a transducer that will change the event `:tap-id` to its id

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


(def taps-buffer-size
  "Every tap has a sliding buffer for back pressure.
  This is it's size."
  100)

(defn toggle-mix
  "Toggles the :mute flag of the output-mix for the tap with tap-id."
  [taps tap-id]
  (swap! (:branches taps) (fn [branches]
                            ;; toggle the mute, then set in state and mix
                            (let [toggled-mute? (not (get-in branches [tap-id :muted?]))]
                              (toggle (:output-mix taps) {(get-in branches [tap-id :tap-channel]) {:mute toggled-mute?}})
                              (assoc-in branches [tap-id :muted?] toggled-mute?)))))

;; ![Tap](./tap.png)
(defn add-tap
  "Given the taps component, the id of the parent(from-id), a name, a transducer
  and a transucer-form(the form that evaluated will generate the transducer) builds a new tap
  and add it to taps branches, making all the wiring needed to be a working tap.
  If a tap with that name is already in taps, doesn't do anything and just returns the id."
  [taps from-id name transducer transducer-form]
  (let [branches (:branches taps)
        new-id (str (get-in @branches [from-id :id]) "/" (str/lower-case name))]
    (when (not (contains? @branches new-id))
      (let [from-mult (get-in @branches [from-id :mult])
            ch (chan (sliding-buffer taps-buffer-size) (comp
                                                        (map #(assoc % :tap-id new-id))
                                                        transducer))
            ch-mult (mult ch)
            mix-ch (chan (sliding-buffer taps-buffer-size))]
        (swap! branches assoc new-id {:id new-id
                                      :from-id from-id
                                      :transducer-form transducer-form
                                      :tap-channel mix-ch
                                      :mult ch-mult
                                      :name name
                                      :muted? true})
        (tap from-mult ch)
        (tap ch-mult mix-ch)
        (toggle (:output-mix taps) {mix-ch {:mute true}})))
    new-id))

(defn normalize-localhost-ip [remote-addr]
  (if (or (= remote-addr "127.0.0.1")
         (= remote-addr "0:0:0:0:0:0:0:1"))
    "localhost"
    remote-addr))

(defn post-event!
  "Adds an event thru the input-ch of taps.
  An event is a map with `:event-id` `:remote-addr` `:timestamp` `:role` `:summary` `:detail` `:thread-id` `:tags`"
  
  [taps remote-addr {:keys [role summary detail thread-id tags]}]
  (let [event-id (str (java.util.UUID/randomUUID))
        ev {:event-id event-id
            :remote-addr (normalize-localhost-ip remote-addr)
            :timestamp (Date.)
            :role role
            :thread-id thread-id
            :summary summary
            :tags tags
            :detail detail}]

    ;; post every event on the input channel and the persistence ch
    (>!! (:input-ch taps) ev) 
    (>!! (:db-ch taps) ev) 
    event-id))


(defn load-taps
  "Given the taps component and a filename, loads all the taps.
  This will only work if your channels are empty (not intended for reload)"
  [taps file]
  (doseq [t (->> file
               slurp
               read-string
               (remove #(= (:id %) "/input"))
               (sort-by :id))]
    
    (add-tap taps
             (:from-id t)
             (:name t)
             (eval (:transducer-form t))
             (:transducer-form t))))

;; The Taps component will initialize and destroy the Taps subsystem

;; **Starting taps component** is about

;; - Creating the input channel
;; - Creating the output channel and it mix
;; - Creating the first input tap(root tap) which reads from the input channel

;; **Stopping taps component** is about removing all references so everything can be
;; garbage collected

(defrecord Taps [input-ch output-ch output-mix branches db-ch]

  comp/Lifecycle

  (start [this]
    (println "Starting Taps component...")
    (let [input-ch (chan (sliding-buffer taps-buffer-size) (map #(assoc % :tap-id "/input")))
          output-ch (chan (sliding-buffer taps-buffer-size))
          db-channel (chan (sliding-buffer taps-buffer-size)) 
          input-mult (mult input-ch)
          output-mix (mix output-ch)
          input-tap (tap input-mult (chan (sliding-buffer 10)))
          initialized-this (-> this
                              (assoc :input-ch input-ch)
                              (assoc :output-ch output-ch)
                              (assoc :output-mix output-mix)
                              (assoc :db-ch db-channel)
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
    (-> this
       (dissoc :input-ch)
       (dissoc :output-ch)
       (dissoc :output-mix)
       (dissoc :branches))))


