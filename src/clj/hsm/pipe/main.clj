(ns hsm.pipe.main
  (:use [clj-kafka.core :only (with-resource)])
  (:require 
    [clojure.tools.logging :as log]
    [clj-kafka.zk :as zk]
    [clj-kafka.consumer.zk :as consumer.zk]
    [clj-kafka.producer :as kfk.prod]
    [clojure.core.async :as async :refer :all]
    [environ.core :refer [env]]
    ))


(def config {"zookeeper.connect" (env :zookeeper)
     "group.id" "clj-kafka.consumer"
     "auto.offset.reset" "smallest"
     "auto.commit.enable" "true"})

(defn get-broker-list 
  []
  (zk/broker-list (zk/brokers {"zookeeper.connect" (env :zookeeper)})))

(defn send! 
  [producer topic msg]
  (kfk.prod/send-message producer
              (kfk.prod/message topic (.getBytes msg))))

(defn init
  [channel topic]
  (let [producer-config {"metadata.broker.list" (get-broker-list)
     "serializer.class" "kafka.serializer.DefaultEncoder"
     "partitioner.class" "kafka.producer.DefaultPartitioner"}
     producer (kfk.prod/producer producer-config)]
     (go (while true
        (let [[v ch] (alts! [channel])]
          (send! producer topic v)
          ; (log/warn "Read" v "from" ch)
          )))))

(def outputter (comp :value println))

(defn receive!
  [topic action]
  (map #(apply str (map char %))
      (with-resource [c (consumer.zk/consumer config)]
        consumer.zk/shutdown
        (mapv :value (take 2 (consumer.zk/messages c topic))))))
