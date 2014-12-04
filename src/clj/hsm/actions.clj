(ns hsm.actions
  (:require 
    [clojure.tools.logging :as log]
    [schema.core :as s]
    [clojurewerkz.cassaforte.cql  :as cql]
    [clojurewerkz.cassaforte.query :as dbq]
    [qbits.hayt.dsl.statement :as hs]
    [hsm.utils :refer [id-generate now->ep]]))

;; USER

(defn follow-user
    [userA userB])

(defn unfollow-user
    [userA userB])

(defn load-user
  [db user-id]
  (let [conn (:connection db)]
      (cql/select conn :user 
        (dbq/where [[= :nick user-id]]))))

(def User {:email s/Str :name s/Str :nick s/Str })

; (defmacro defndb)

(defn create-user
  [db user-data]
  (s/validate User user-data)
  (let [conn (:connection db)
        dt (now->ep)
        additional {:id (id-generate) :password "" :roles #{"user"} :created_at dt  :registered_at dt }
        user-info (merge additional user-data)]
    (cql/insert conn :user user-info)))

(defn load-user-activity [user-id])
(defn load-user-followers [user-id])
(defn load-user-following [user-id])

(defn get-profile
    [user visitor])

(defn get-profile-by-nick
  [nick visitor]
  (let [user nick]
    (get-profile user)))

;; DISCUSS
(def Discussion {:title s/Str :post s/Str})

(defn create-discussion
  [db platform user data]
  (s/validate Discussion data)  
  (let [conn (:connection db)
        post (:post data)
        post-info {:id (id-generate) :user_id user :text post}
        additional {:id (id-generate) :published_at (now->ep) 
                    :user_id user :platform_id platform
                    :post_id (:id post-info)}
        
        discussion-info (merge additional (dissoc data :post))]
    (cql/atomic-batch conn 
      (dbq/queries
        (hs/insert :post (dbq/values post-info))
        (hs/insert :discussion (dbq/values discussion-info))))
    additional))

(def Post {:text s/Str})

(defn new-discussion-post
  [db user disc-id post]
  (let [conn (:connection db)
        post-id (id-generate)
        post-data (merge post {:id post-id :user_id user })]
        (log/warn post post-data)
    (cql/atomic-batch conn 
      (dbq/queries
        (hs/insert :post (dbq/values post-data))
        (hs/insert :discussion_post 
          (dbq/values { :post_id post-id
                        :user_id user
                        :disc_id disc-id }))))
    post-id))

(defn load-post [db post-id]
  (let [conn (:connection db)]
    (first (or (cql/select conn :post (dbq/where [[= :id post-id]])) []))))

(defn load-discussion [db disc-id]
  (let [conn (:connection db)]
    (when-let [discussion (first (cql/select conn :discussion 
                    (dbq/where [[= :id disc-id] [= :platform_id 1]])))]
    (log/warn discussion)
    (let [post-id (:post_id discussion)]
      (assoc discussion :post (load-post db post-id))))))

(defn follow-discussion [db disc-id user-id]
  (let [conn (:connection db)]
    (cql/atomic-batch conn 
      (dbq/queries
        ; (hs/insert :post (dbq/values post-data))
        (hs/insert :discussion_follower 
          (dbq/values { :created_at (now->ep)
                        :user_id user-id
                        :disc_id disc-id }))))
    ))

(defn unfollow-discussion [db disc-id user-id]
  (let [conn (:connection db)]
    (cql/delete conn 
      :discussion_follower 
      {:user_id user-id
        :disc_id disc-id } )))

(defn load-discussion-posts [db disc-id]
  (let [conn (:connection db)]
    (when-let [post-ids (mapv :post_id (cql/select conn :discussion_post 
      (dbq/where [[= :disc_id disc-id]])))]
    (log/warn "Found " post-ids)
      (cql/select conn :post (dbq/where [[:in :id post-ids]]))
    )))

(defn delete-discussion [id])

;; POST Related 



(defn new-post [data])

(defn edit-post [data])

(defn upvote-post [post user])

(defn delete-post [post user])