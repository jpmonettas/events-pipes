(defproject events-pipes "0.12.0"
  :description "Event processing server and monitor"
  :url ""
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}

  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/clojurescript "0.0-3297"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [cljsjs/highlight "8.4-0"]
                 [http-kit "2.1.18"]
                 [reagent "0.5.0"]
                 [com.andrewmcveigh/cljs-time "0.3.10"]
                 [com.taoensso/sente "1.5.0"]
                 [compojure "1.4.0"]
                 [ring/ring-json "0.3.1"]

                 ;; To embeed a nrepl server in the app
                 [org.clojure/tools.nrepl "0.2.7"]
                 [cider/cider-nrepl "0.10.0-SNAPSHOT"]

                 [cljsjs/d3 "3.5.5-3"]
                 
                 [aleph "0.4.0"]
                 [manifold "0.1.0"]
                 [amalloy/ring-buffer "1.2"]
                 [byte-streams "0.2.0"]
                 [clj-json "0.5.3"]
                 [ring-cors "0.1.7"]
                 [com.stuartsierra/component "0.3.0"]]

  :plugins [[lein-cljsbuild "1.0.5"]
            [lein-figwheel "0.3.5"]
            [michaelblume/lein-marginalia "0.9.0"]]

  :main ^:skip-aot events-pipes.server.main
  
  :source-paths ["src"]

  ;; This is commented out because it's breaking the uberjar (not including the js)
  ;; try rm resources/public/js/compiled -rf, the cljsbuild once, and then uberjar
  ;;:clean-targets ^{:protect false} ["resources/public/js/compiled" "target"]

  :cljsbuild {
    :builds [{:id "dev"
              :source-paths ["src"]

              :figwheel { :on-jsload "events-pipes.client/on-js-reload" }

              :compiler {:main events-pipes.client
                         :asset-path "js/compiled/out"
                         :output-to "resources/public/js/compiled/events_pipes.js"
                         :output-dir "resources/public/js/compiled/out"
                         :source-map-timestamp true }}
             {:id "min"
              :source-paths ["src"]
              :compiler {:output-to "resources/public/js/compiled/events_pipes.js"
                         :main events-pipes.client
                         :optimizations :advanced
                         :pretty-print false}}]}

  :figwheel {
             ;; :http-server-root "public" ;; default and assumes "resources" 
             ;; :server-port 3449 ;; default
             ;; :server-ip "127.0.0.1" 

             :css-dirs ["resources/public/css"] ;; watch and update CSS

             ;; Start an nREPL server into the running figwheel process
             :nrepl-port 7888

             ;; Server Ring Handler (optional)
             ;; if you want to embed a ring handler into the figwheel http-kit
             ;; server, this is for simple ring servers, if this
             ;; doesn't work for you just run your own server :)y
             ;; :ring-handler events-pipes.server/my-app

             ;; To be able to open files in your editor from the heads up display
             ;; you will need to put a script on your path.
             ;; that script will have to take a file path and a line number
             ;; ie. in  ~/bin/myfile-opener
             ;; #! /bin/sh
             ;; emacsclient -n +$2 $1
             ;;
             ;; :open-file-command "myfile-opener"

             ;; if you want to disable the REPL
             ;; :repl false

             ;; to configure a different figwheel logfile path
             ;; :server-logfile "tmp/logs/figwheel-logfile.log" 
             }
  :profiles {:uberjar {:aot :all}})
