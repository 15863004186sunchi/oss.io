  (ns hsm.actions
  (:require
    [clojure.core.memoize :as memo]
    [clojure.tools.logging :as log]
    [clojure.string :as str]
    [schema.core :as s]
    [clojurewerkz.cassaforte.cql  :as cql]
    [clojurewerkz.cassaforte.query :as dbq]
    [qbits.hayt :as hayt]
    [qbits.hayt.dsl.statement :as hs]
    [clojurewerkz.elastisch.rest :as esr]
    [clojurewerkz.elastisch.rest.index :as esi]
    [clojurewerkz.elastisch.rest.document :as esd]
    [clojurewerkz.elastisch.rest.response :as esrsp]
    [clojurewerkz.elastisch.query :as q]
    [hsm.utils :refer :all]
    [hsm.cache :as cache]))

(declare list-top-proj**)
(declare load-projects-by-int-id)

;; USER
(defn follow-user
  [db user current-user]
  (let [conn (:connection db)]
    (cql/atomic-batch conn
          (dbq/queries
            (hs/insert :user_follower 
                (dbq/values {:user_id user 
                             :follower_id current-user 
                             :created_at (now->ep)}))
            (hs/insert :user_following 
                (dbq/values {:user_id current-user 
                             :following_id user 
                             :created_at (now->ep)}))))))

(defn unfollow-user
  [db user current-user]
  (let [conn (:connection db)]
    (cql/atomic-batch conn
          (dbq/queries
            (hs/delete :user_follower
                (dbq/values {:user_id user
                             :follower_id current-user }))
            (hs/delete :user_following
                (dbq/values {:user_id current-user
                             :following_id user}))))))

(defn load-user
  [db user-id]
  (let [conn (:connection db)]
      (cql/select conn :user
        (dbq/where [[= :nick user-id]]))))

(def User {:email s/Str :name s/Str :nick s/Str })

(defn create-user
  [db user-data]
  (s/validate User user-data)
  (let [conn (:connection db)
        dt (now->ep)
        additional {:id (id-generate) :password ""
                    :roles #{"user"} :created_at dt  :registered_at dt }
        user-info (merge additional user-data)]
    (cql/insert conn :user user-info)))

(defn load-user-activity
  [db user-id])

(defn load-user-followers
  [db user-id]
  (let [conn (:connection db)]
    (cql/select conn :user_follower
      (dbq/columns :follower_id)
      (dbq/where [[= :user_id user-id]]))))

(defn load-user-following
  [db user-id]
  (let [conn (:connection db)]
    (map :following_id (cql/select conn :user_following
      (dbq/columns :following_id)
      (dbq/where [[= :user_id user-id]])))))

(defn get-profile
  [db user visitor])

(defn get-profile-by-nick
  [nick visitor]
  (let [user nick]
    (get-profile user)))

;; DISCUSS
(def Discussion {:title s/Str :post s/Str})

(defn create-discussion
  [db platform user data]
  ; (log/warn "[CREATE-DISC]" data)
  (try 
    (s/validate Discussion data)
    ; this is stupid. Must not fail here!!
    ; #<IllegalArgumentException java.lang.IllegalArgumentException: 
    ; Don't know how to create ISeq from: schema.utils$result_builder$conjer__777>
    (catch Throwable t (log/error "[CREATE-DISC-VAL]" t)))

  (let [conn (:connection db)
        post (:post data)

        post-info {:id (id-generate) :user_id user :text post}
        additional {:id (id-generate) 
                    :published_at (now->ep)
                    :user_id user 
                    :platform_id platform
                    :post_id (:id post-info)}
        discussion-info (merge additional (dissoc data :post))]
    (log/warn "[CREATE-DISC]" post-info)
    (log/warn "[CREATE-DISC]" discussion-info)
    (cql/atomic-batch conn
      (dbq/queries
        (hs/insert :post (dbq/values post-info))
        (hs/insert :discussion (dbq/values discussion-info))))
    ;; COUNTER COLUMN OPs are not supported in Batch; so CANNOT 
    ;; add this update into the atomic-batch.
    (cql/update conn :post_counter 
       {
        :karma (dbq/increment-by 1)
        :up_votes (dbq/increment-by 1)
        :views (dbq/increment-by 1)}
      (dbq/where {:id (:id post-info)}))
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

    (cql/update conn :post_counter 
      {:karma (dbq/increment-by 1)
       :up_votes (dbq/increment-by 1)
       :views (dbq/increment-by 1)}
      (dbq/where [[:= :id post-id]]))
    post-id))

(defn load-post
  [db post-id]
  (let [conn (:connection db)]
    (first (or (cql/select conn :post 
      (dbq/where [[= :id post-id]])) []))))

(defn load-posts-by-ids
  [{:keys [connection]} post-id-list]
  (cql/select connection :post 
    (dbq/where [[:in :id post-id-list]])))

(defn load-discussions
  [db]
  (let [conn (:connection db)]
    (let [discussions (cql/select conn :discussion)
          posts (load-posts-by-ids db (map :post_id discussions))]
      (map #(assoc % :post (first (filter (fn[x] (= (:id x) (:post_id %))) posts)))  
        discussions))))

(defn load-discussion
  [db disc-id]
  (let [conn (:connection db)]
    (when-let [discussion (first (cql/select conn :discussion
                    (dbq/where [[= :id disc-id] [= :platform_id 1]])))]
      (log/warn discussion)
      (let [post-id (:post_id discussion)]
        (assoc discussion :post (load-post db post-id))))))

(defn follow-discussion
  [db disc-id user-id]
  (let [conn (:connection db)]
    (cql/atomic-batch conn
      (dbq/queries
        ;; TODO: Add UserDiscussion table
        (hs/insert :discussion_follower
          (dbq/values { :created_at (now->ep)
                        :user_id user-id
                        :disc_id disc-id }))))))

(defn unfollow-discussion
  [db disc-id user-id]
  (let [conn (:connection db)]
    (cql/delete conn
      :discussion_follower
      {:user_id user-id
        :disc_id disc-id})))

(defn load-discussion-posts
  [db disc-id]
  (let [conn (:connection db)]
    (when-let [post-ids (mapv :post_id
                          (cql/select conn :discussion_post
                            (dbq/where [[= :disc_id disc-id]])))]
    (log/warn "Found " post-ids)
      (cql/select conn :post (dbq/where [[:in :id post-ids]])))))

(defn delete-discussion [id])

;; POST Related

(defn update-post
  [{:keys [connection]} post-id data]
  (cql/update connection :post
    data
    (dbq/where [[:= :id post-id]])))

(defn create-post
  [db post user]
  (s/validate Post post)
  (let [conn (:connection db)
        post-id (id-generate)
        post-data (merge post {:id post-id 
                               :user_id user 
                               :created_at (now->ep) })]
      ;; counter column updates cannot be batched with normal statements
      ;; we have to execute 2 queries to satisfy our req.
      ;; might throw the counter update task into a Worker Queue
      (cql/insert conn :post post-data)
      (cql/update conn :post_counter
         {:karma (dbq/increment-by 1)
          :up_votes (dbq/increment-by 1)
          :views (dbq/increment-by 1)}
        (dbq/where {:id post-id}))
    post-id
    ))

(defn edit-post [db data])

(defn upvote-post [db post user]
  (let [conn (:connection db)]
    (cql/insert conn :post_vote {
      :post_id post
      :user_id user
      :created_at (now->ep)
      :positive true})))

(defn delete-post 
  [{:keys [connection]} post-id]
  (cql/delete connection :post 
    (dbq/where [[:= :id post-id]])
    )
  )

(defn create-link
  [db link-data user]
  (let [conn (:connection db)]
    (cql/insert conn :link
      (merge link-data {
        :id (id-generate)
        :submit_by user
        :created_at (now->ep)}))))

(defn get-link
  [db link-id user]
    (let [conn (:connection db)]
      (when-let [link (first
                        (cql/select conn :link 
                          (dbq/where [[= :id link-id]])))]
        (when-not (empty? link)
          (merge link
            (first (cql/select conn :post_counter
                      (dbq/where [[= :id link-id]]))))))))
(defn list-links 
  [db time-filter user]
  (let [conn (:connection db)]
    (cql/select conn :link)))

(defn stringify-id
  [dict]
  (assoc dict :id (str (:id dict))))



(defn load-projects*
  [db platform limit-by]
  (log/info "[LIST-PROJ] Fetching " platform limit-by)
  (let [conn (:connection db)
        limit-by (if (> limit-by 100) 100 limit-by)]
    (when-let [projects (cql/select conn :github_project
                          (dbq/limit 10000) ; place a hard limit
                           (when-not (nil? platform) (dbq/where 
                                        [[= :language platform]])))]
      projects)))

(defn load-all-projects
  [db batch-size]
  (let [batch-size (if (> batch-size 100) 100 batch-size)
        conn (:connection db)]
    (cql/iterate-table conn :github_project :full_name batch-size)))

(defn load-all-users
  [db batch-size]
  (let [batch-size (if (> batch-size 1000) 1000 batch-size)
        conn (:connection db)]
    (cql/iterate-table conn :github_user :login batch-size)))

(defn ^:private fetch-top-proj
  [db redis language size]
  (log/warn "Start REDIS TOP PROJ Fetch" language)
  (let [projects (cache/ssort-fetch redis (str "lang-" language) 0 (- size 1))
        project-ids (mapv #(Integer/parseInt (first (.split % "-"))) projects)]
    (log/warn project-ids)
    (let [found-projects (load-projects-by-int-id db project-ids)]
      ; (log/warn found-projects)
      (log/warn (count found-projects))
      found-projects)))

(defn list-top-proj*
  "List top projects; 
  Results will be fetched from redis"
  [db redis platform limit-by]
  (let [cached-projects (fetch-top-proj db redis platform limit-by)]
    (if (!!nil? cached-projects)
        cached-projects
        (list-top-proj** db platform limit-by))))

(defn list-top-proj**
  "Given platform/language returns top n projects.
  TODO: DELETE THIS"
  [db platform limit-by]
  (log/info "[LIST-TOP-PROJ] Fetching " platform limit-by)
  (let [conn (:connection db)
        limit-by (if (> limit-by 100) 100 limit-by)]
    (when-let [projects (cql/select conn :github_project
                        (dbq/limit 10000) ; place a hard limit
                          (dbq/where [[= :language platform]]))]
      (map
        stringify-id
        (take limit-by (reverse
                          (sort-by :watchers projects)))))))

(def list-top-proj (memo/ttl list-top-proj* :ttl/threshold 6000000 ))

(defn load-project
  [db proj]
  (let [conn (:connection db)]
    (cql/select conn :github_project
      (dbq/limit 1)
      (dbq/where [[= :full_name proj]]))))

(defn load-projects-by-id
  [db proj-list]
  (let [conn (:connection db)]
    (cql/select conn :github_project
      (dbq/limit 100)
      (dbq/where [[:in :full_name proj-list]]))))

(defn load-projects-by-int-id
  [db proj-list]
  (let [conn (:connection db)]
    ;; FIX THIS SHIT!@!@!@!
     (mapcat #(cql/select conn :github_project
            (dbq/limit 1000)
            (dbq/where [[:= :id %]]))
      proj-list)))

(defn load-project-extras
  [db proj]
  (let [conn (:connection db)]
    (first (cql/select conn :github_project_list
      (dbq/limit 1)
      (dbq/where [[= :proj proj]])))))

(defn list-top-disc
  [db platform limit-by]
  (log/warn "[TOP-DISC] Fetching " platform limit-by)
  (let [conn (:connection db)
        limit-by (if (> limit-by 100) 100 limit-by)]
    (when-let [discussions (cql/select conn :discussion 
      (dbq/limit limit-by))]
      ; TODO: horrible to do this way. 
      ; Cache these top project IDs by platform
      ; and do a quick load
      (doall (map #(load-discussion db (:id %)) discussions)
    ))))

(defn list-top-user
  [db platform limit-by]
  (log/warn "[TOP-USER] Fetching " platform limit-by)
  (let [conn (:connection db)
        limit-by (if (> limit-by 100) 100 limit-by)]
    (cql/select conn :github_user
      (dbq/limit limit-by))
  ))

(defn load-user2
  [db user-id]
  (let [conn (:connection db)]
      (first (cql/select conn :github_user
        (dbq/where [[= :login user-id]])))))

(defn user-extras
  [db user-id]
  (let [conn (:connection db)]
      (first (cql/select conn :github_user_list
        (dbq/where [[= :user user-id]])))))

(defn top-users-in
  [users limit-by]
  (take limit-by 
    (reverse (sort-by :followers users))))

(defn top-users
  [db limit-by top-n]
  (let [conn (:connection db)
        users (cql/select conn :github_user 
                (dbq/limit limit-by)
                (dbq/columns :login :followers))]
    (top-users-in users top-n)))

(defn load-users-by-id
  [db user-ids]
  (let [user-ids (max-element user-ids 100)
        conn (:connection db)]
    (log/warn "Fetching user-ids" user-ids)
    (cql/select conn :github_user
        (dbq/limit 100)
        (dbq/where [[:in :login user-ids]]))
    ))

(defn fetch-top-users
  [db limit-by top-n]
  (let [toppers (top-users db limit-by top-n)
        user-ids (mapv :login toppers)]
    (load-users-by-id db user-ids)))

(defn load-users
  [db limit-by]
  (let [conn (:connection db)]
    (when-let [users (cql/select conn :github_user
                      (dbq/columns :login :followers :name :email :blog)
                      (dbq/limit 1000)
                      (dbq/where [[= :full_profile true]]))]
      (mapv #(select-keys % [:login :followers :name :email :blog]) 
        (top-users-in users limit-by)))))

(defn load-collections
  [db limit-by]
  (let [conn (:connection db)]
      (cql/select conn :collection)))

(defn create-collection
  [db data]
  (let [conn (:connection db)]
    (cql/insert conn
      :collection data)
    (cql/insert conn 
      :collection_list { :id (:id data)})))

(defn update-collection
  [{:keys [connection]} id items]  
  (cql/update connection :collection 
    {:items items}
    (dbq/where [[:= :id id]])))

(defn get-collection
  [{:keys [connection]} id]
  (cql/select connection :collection
    (dbq/limit 1)
    (dbq/where [[:= :id id]])))

(defn get-collection-extras-by-id
  [{:keys [connection]} id-list]
  (cql/select connection :collection_list
    (dbq/limit 1000)
    (dbq/where [[:in :id id-list]])))

(defn get-collection-extra
  [{:keys [connection]} id]
  (first 
    (cql/select connection :collection_list
      (dbq/limit 1)
      (dbq/where [[:= :id id]]))))

(defn add-collection-fork
  [{:keys [connection]} id fork-id]
  (cql/update connection :collection_list
    {:forks [+ #{(str fork-id)}]}
    (dbq/where [[:= :id id]])))

(defn delete-collection
  [{:keys [connection]} id]
  (cql/delete connection :collection
    (dbq/where [[:= :id id]])))

(defn star-collection
  [{:keys [connection]} id user-set]
  (cql/update connection :collection_list
    {:stargazers [+ user-set]}
    (dbq/where [[:= :id id]])))

(defn load-topics
  [{:keys [connection]} pl]
  (cql/select connection :topic
    (dbq/where [[:= :platform_id pl]])))

(defn get-topic
  [{:keys [connection]} pl id]
  (first 
    (cql/select connection :topic
      (dbq/where [
        [:= :platform_id pl]
        [:= :id id]]))))

(defn get-topic-by-slug
  [{:keys [connection]} slug]
  (first 
    (cql/select connection :topic
      (dbq/where [[:= :slug slug]]))))

(defn user-projects-es*
  [else user limit]
  (log/warn "[ES_PROJ]" user )
  (let [res (esd/search (:conn else) (:index else) "github_project"
                 :sort [ { :watchers {:order :desc}}]
                 :size limit
                  :query (q/filtered 
                          :filter   (q/term 
                                        :owner (str/lower-case user))))
          n (esrsp/total-hits res)
          hits (esrsp/hits-from res)]
    (map :_source hits)))

(defn top-projects-es*
  [else platform limit]
  (let [res (esd/search (:conn else) (:index else) "github_project"
                 :sort [ { :watchers {:order :desc}}]
                 :size limit
                  :query (q/filtered 
                          :filter   (q/term 
                                        :language (str/lower-case platform))))
          n (esrsp/total-hits res)
          hits (esrsp/hits-from res)]
    (map :_source hits)))

(def top-projects-es (memo/ttl top-projects-es* :ttl/threshold 6000000 ))
