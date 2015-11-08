(ns hsm.server
  (:require
            [clojure.tools.logging :as log]
            [hsm.conf :as conf]
            [hsm.system :as system]
            [com.stuartsierra.component :as component]
            [environ.core :refer [env]])
  (:gen-class))


(defn startup
  [{:keys [conf] :or {conf "app.ini"}} ]
  (let [c (conf/parse-conf conf true)]
        (log/warn "Parsed config")
        (let [sys (system/front-end-system {
                                    :server-port (:port c)
                                    :zookeeper (:zookeeper-host c)
                                    :host (:db-host c)
                                    :port (:db-port c)
                                    :keyspace (:db-keyspace c)
                                    :redis-host (:redis-host c)
                                    :redis-port (:redis-port c)
                                    :else-host (:else-host c)
                                    :else-port (:else-port c)
                                    :else-index (:else-index c)
                                    :conf c})
        app-sys (component/start sys)]
        app-sys
    )))

(defn -main [& args]
    (try
      (log/info "Starting....")
      (startup {})
      (catch Throwable t
        (do
          (log/warn "FAILED!")
          (log/error t)
          (log/warn (.getMessage t))
          (log/warn (.getCause t))))))
