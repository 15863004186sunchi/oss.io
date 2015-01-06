(ns hsm.controllers.project
  (:require 
    [clojure.tools.logging :as log]
    [clojure.core.memoize :as memo]
    [clojure.java.io :as io]
    [cheshire.core :refer :all]
    [ring.util.response :as resp]
    [hiccup.core :as hic]
    [hiccup.page :as hic.pg]
    [hiccup.element :as hic.el]
    [hiccup.def         :refer [defhtml]]
    [hsm.actions :as actions]
    [hsm.ring :refer [json-resp html-resp redirect]]
    [hsm.views :as views :refer [layout panel panelx]]
    [hsm.helpers :refer [pl->lang]]
    [hsm.integration.ghub :as gh]
    [hsm.cache :as cache]
    [hsm.utils :as utils :refer :all]))

(def platforms {
  "pythonhackers.com" {:lang "Python" :id 1}
  "clojurehackers.com" {:lang "Clojure" :id 2} })

(defn grid-view
  [top-projects keyset]
  [:table {:class "table table-striped table-hover"}
    [:thead 
      [:tr 
        (for [header keyset] 
        [:th header])]]
    [:tbody
      (for [x top-projects]
        [:tr (for [ky (keys x)]
          [:td (get x ky)])])]])

(defn list-view
  [top-projects keyset]
  [:table.table
    [:tbody
      (for [x top-projects]
        [:tr
          [:td (get x :watchers)]
          [:td  
            [:a { :href (format "/p/%s" (get x :full_name))} (get x :full_name)
            [:p {:style "color:gray"} (get x :description)]]]
        ])]])

(defn list-top-proj
  [[db event-chan] request]
  (log/warn request)
  (let [host        (host-of request)
        body         (body-of request)
        user         (whois request)
        data         (utils/mapkeyw body)
        platform      (pl->lang (id-of request :platform))
        is-json     false
        view         (get-in request [:params :view])
        view-fn     (if (= view "grid") grid-view list-view)
        limit       (or (get-in request [:params :limit]) (str 20))
        limit-by     (or (Integer/parseInt limit) 20)]
    (let [top-projects (actions/list-top-proj db platform limit-by)
          keyset (keys (first top-projects))]
      (if is-json
        (json-resp top-projects)
        (html-resp
          (views/layout host
            (view-fn top-projects keyset)))))))

(defn get-project-readme*
  [redis proj]
  (let [cache-key (str "readme-" proj)
        cached (cache/retrieve redis cache-key)]
    (if (!nil? cached)
      cached
      (let [readme (gh/project-readme proj)]
        (cache/setup redis cache-key readme)
        readme
        ))))

(defn get-project-readme 
  [redis proj] 
  (memo/memo 
    (fn[] 
      (get-project-readme redis proj))))

    ; (fn [redis id] (get-project-readme* redis id))))

(defhtml project-header
  [id proj admin? owner contributor-count watcher-count]
  [:div.row
    [:div.col-lg-2 {:style "text-align: center;padding-top:15px;"}
      [:h3 [:i.fa.fa-star] [:a {:href (str "/p/" id "/stargazers")}(:watchers proj)]]
      [:form {:action "/ajax/project/follow" :method "POST"}
        [:input {:type "hidden" :value (:id proj)}]
        [:button.btn.btn-primary {:type "submit"} "Love It"]]
        (when admin?
          [:a.btn.btn-danger.btn-sm {:href (format "/p/%s?force-sync=1" id)} "Synchronize"])
        ]
    [:div.col-lg-8
      [:h3
        [:a {:href (str "/user2/" owner)} owner]
        [:span " / "]
        [:a {:href (str "/p/" id)} [:span  (:name proj)]]
        
        [:a.pad10 {
            :href (str "https://github.com/" (:full_name proj))
            :title "View on Github"}
          [:i.fa.fa-github]]]
      [:a {:href (:homepage proj)}]
      [:div.icons 
        [:a {:href (str "/p/" id "/watchers")} 
          [:span [:i.fa.fa-bullhorn] watcher-count]]
        [:a {:href (str "/p/" id "/contributors")} 
          [:span [:i.fa.fa-users] contributor-count]]]
      [:span.label.label-warning (:language proj)]
      [:p.lead (:description proj)]]]
      [:hr])

(defn get-project-*
  [mod-fn {:keys [db event-chan redis]} request]
   (let [host  (host-of request)
         is-json (type-of request :json)
         id (format "%s/%s" (id-of request :user) (id-of request :project))
         force-sync (is-true (get-in request [:params :force-sync]))
         related-projects []
         admin? true
         proj (first (actions/load-project db id))
         proj-extras (actions/load-project-extras db id)
         watcher-count (count (:watchers proj-extras))
         contributor-count (count (:contributors proj-extras))
         owner (first (.split id "/"))
         owner-obj (actions/load-user2 db owner)]
      (if is-json
        (json-resp (assoc proj :owner owner-obj))
        (views/layout host
          (project-header id proj admin? owner contributor-count watcher-count)
          (mod-fn id proj proj-extras)))))

(defhtml contribs
  [id proj proj-extras]
  (panel [:span [:i.fa.fa-users] " Contributors" ]
    [:ul (for [x (:contributors proj-extras)]
      [:li [:a {:href (format "/user2/%s" x)} x]])]))

(defhtml watchers
  [id proj proj-extras]
  (panel "Watchers"
    [:ul (for [x (:watchers proj-extras)]
      [:li [:a {:href (format "/user2/%s" x)} x]])]))

(defhtml stargazers
  [id proj proj-extras]
  (panel "Star gazers"
    [:ul (for [x (:stargazers proj-extras)]
      [:li [:a {:href (format "/user2/%s" x)} x]])]))

(def get-project-contrib (partial get-project-* contribs))
(def get-project-stargazers (partial get-project-* stargazers))
(def get-project-watchers (partial get-project-* watchers))

(defhtml user-list
  [users]
  [:ul (for [x users]
    [:li [:a {:href (format "/user2/%s" x)} x]])])

(defn get-proj-module
  [spec request]
  (let [module (id-of request :mod)]
    (condp = module
      "stargazers" (get-project-stargazers spec request)
      "watchers"    (get-project-watchers spec request)
      "contributors" (get-project-contrib spec request)
      (resp/status 404))))

(defn get-proj
  [{:keys [db event-chan redis]} request]
  (let [host  (host-of request)
        is-json (type-of request :json)
        id (format "%s/%s" (id-of request :user) (id-of request :project))
        force-sync (is-true (get-in request [:params :force-sync]))
        related-projects []
        admin? true]
    (when-let [proj (first (actions/load-project db id))]
      (if force-sync
        (do
          (gh/enhance-proj db id 1000)
          (redirect (str "/p/" id)))
        (let [proj-extras (actions/load-project-extras db id)
              watcher-count (count (:watchers proj-extras))
              contributor-count (count (:contributors proj-extras))
              owner (first (.split id "/"))
              owner-obj (actions/load-user2 db owner)]
          (if is-json
            (json-resp (assoc proj :owner owner-obj))
            (html-resp
              (views/layout host
                (project-header id proj admin? owner contributor-count watcher-count)
                [:div.row
                  [:div.col-lg-10
                    (views/panel "READ ME"
                      (get-project-readme* redis id))]
                  [:div.col-lg-2
                    (panel [:a {:href (str "/p/" id "/contributors")} [:span [:i.fa.fa-users] " Contributors" ]]
                      (user-list (take 10 (:contributors proj-extras))))
                    (panel [:a {:href (str "/p/" id "/watchers") } [:span [:i.fa.fa-users] "Watchers"]]
                      (user-list (take 10 (:watchers proj-extras))))
                    (panel [:a {:href (str "/p/" id "/stargazers") } [:span [:i.fa.fa-users] "Stargazers"]]
                      (user-list (take 10 (:stargazers proj-extras))))
                  
                    (panel "Related Projects"
                      [:ul
                        (for [x related-projects]
                          [:li
                            [:a {:href (str "/p/"(:full_name x))} (:full_name x)
                            [:p {:style "color:gray"} (cutoff (:description x) 50)]]
                            ]
                          )])]]))))))))