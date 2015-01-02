(ns hsm.controllers.user
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [cheshire.core :refer :all]
            [ring.util.response :as resp]
            [hsm.actions :as actions]
            [hsm.pipe.event :as event-pipe]
            [hsm.views :refer [layout panel]]
            [hsm.ring :refer [json-resp html-resp]]
            [hsm.integration.ghub :as gh]
            [hsm.utils :as utils :refer [body-of host-of whois id-of type-of]]))

(defn get-user
  [[db event-chan] request] 
  (let [id (id-of request)
        user (actions/load-user db id)]
    (json-resp user)))

(defn get-user2
  [[db event-chan] request] 
  (let [id (id-of request)
        host (host-of request)
        user (actions/load-user2 db id)
        is-json (type-of request :json)]
    (when-not (:full_profile user)
      (gh/find-n-update (:connection db) id))
    (let [user-extras (actions/user-extras db id)
          c-star (count (:starred user-extras))
          c-follow (count (:following user-extras))
          c-followers (count (:followers user-extras))]
      (if is-json 
        (json-resp user)
        (layout host
            [:div 
              [:h3 [:span (:login user)]]
              [:h5 [:span (:name user)]] 
              [:p [:a {:href (:blog user)}(:blog user)]]
              [:p (:company user)]
              [:a {:href (str "mailto://" (:email user))} (:email user)]
              [:p (format "%s %s %s" c-star c-follow c-followers)]]
          [:div.col-lg-4
          (panel (str "Starred " c-star) [:ul (for [star (:starred user-extras)] [:li [:a {:href (str "/p/" star)} star]])])]
          [:div.col-lg-4
          (panel (str "Following " c-follow) [:ul (for [star (:following user-extras)] [:li [:a {:href (str "/user2/" star)} star]])])]
          [:div.col-lg-4
          (panel (str "Followers " c-followers) 
            [:ul (for [star (take 100 (:followers user-extras))] [:li [:a {:href (str "/user2/" star)} star]])])]
          )))))

(defn sync-user2
  [[db event-chan] request] 
  (let [id (id-of request)
        host (host-of request)]
    (gh/enhance-user db id 1000)
    (json-resp (actions/load-user2 db id))
  ))

(defn create-user
  [[db event-chan] request] 
  (let [host  (get-in request [:headers "host"])
    body (parse-string (utils/body-as-string request))
    user-data (utils/mapkeyw body)]
    (actions/create-user db user-data)
    (event-pipe/create-user event-chan user-data)
    (json-resp { :ok body })))

(defn ^:private follow-user-actions
  [func act-name [db event-chan] request]
  (let [host  (host-of request)
        body (body-of request)
        current-user 243975551163827208
        id (BigInteger. (id-of request))]
    (func db id current-user)
    (event-pipe/follow-user-event act-name event-chan {:current-user current-user :user id})
    (json-resp {:ok 1})))

(def follow-user (partial follow-user-actions actions/follow-user :follow-user))
(def unfollow-user (partial follow-user-actions actions/unfollow-user :unfollow-user))

(defn ^:private get-user-detail
  [func [db event-chan] request]
  (let [host  (host-of request)
        body (body-of request)
        current-user (whois request)
        user-id (BigInteger. (id-of request))]
        (json-resp (func db user-id))))

(def get-user-following (partial get-user-detail actions/load-user-following))
(def get-user-followers (partial get-user-detail actions/load-user-followers))

(defn get-user-activity
  [[db event-chan] request]
  (let [host  (host-of request)
        body (body-of request)]

        )
  )

(defn some-user
  [[db event-chan] request]
  (let [host (host-of request)
        limit-by 100
        users (actions/load-users db limit-by)
        is-json (type-of request :json)]
    (if is-json 
      (json-resp users)
      (html-resp 
        (layout host
          [:div
            [:table.table
            (for [x users]
                [:tr   
                  [:td (:followers x)]
                  [:td 
                    [:a {:href (str "/user2/" (:login x))} (:login x)]
                    [:nbsp]
                    (:name x)
                    
                    [:p 
                      [:a {:href (:blog x)} (:blog x)]
                      (:email x)]]])]
          ])))))