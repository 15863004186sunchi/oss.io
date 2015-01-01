(ns hsm.server
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [cheshire.core :as json]
            [hsm.dev :refer [is-dev? inject-devmode-html browser-repl start-figwheel]]
            [liberator.core :refer [resource defresource]]
            [compojure.core :refer [GET defroutes]]
            [compojure.route :as route :refer [resources]]
            [ring.util.response :as resp]
            [ring.util.codec :as codec]
            [net.cgrand.enlive-html :refer [deftemplate]]
            [cemerick.friend :as friend]
            [hsm.users :as users :refer (users)]
            [hsm.github :as ghub :refer [render-repos-page]]
            [hsm.conf :as conf]
            [hsm.system :as system]
            [clj-http.client :as client]
            [com.stuartsierra.component :as component]
            [hiccup.page :as h]
            [hiccup.element :as e]
            [cheshire.core :refer :all]
            [environ.core :refer [env]]
            ))


(defn startup 
  [{:keys [conf] :or {conf "app.ini"}} ]
  (let [c (conf/parse-conf conf true)]
        (log/warn "Parsed config")
        (let [sys (system/front-end-system {
                                    :server-port (:port c)
                                    :zookeeper (:zookeeper-host c)
                                    :host (:db-host c) 
                                    :port (:db-port c) 
                                    :keyspace (:db-keyspace c)})
        app-sys (component/start sys)]
        app-sys
    )))

(defn -main [& args]
  (startup {}))
