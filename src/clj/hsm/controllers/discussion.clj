(ns hsm.controllers.discussion
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [markdown.core :refer [md-to-html-string]]
            [cheshire.core :refer :all]
            [ring.util.response :as resp]
            [slingshot.slingshot :refer [throw+ try+]]
            [hsm.actions :as actions]
            [hsm.pipe.event :as event-pipe]
            [hsm.views :refer [layout panel panelx render-user left-menu]]
            [hsm.ring :refer [json-resp html-resp redirect]]
            [hsm.utils :as utils :refer :all]))

(defn get-topic
  [{:keys [db event-chan redis conf]} request]
  (let [{:keys [host id body json? user pl platform]} (common-of request)
        ; topic (actions/get-topic db (or pl 1) (Integer/parseInt id))
        topic (actions/get-topic-by-slug db id)
        top-disc (actions/load-discussions db)]
        (log/warn topic)
      (if json?
        (json-resp topic)
        (layout host
          [:div.row
            [:div.col-lg-3
              (left-menu host platform "open-source")]
            [:div.col-lg-9
              (panelx
                [:h3 (:name topic)]
                "" ""
                (for [x top-disc]
                  [:div.row 
                    [:div.col-lg-6 
                      [:h4
                        [:a {:href (str "/discussion/" (:id x)) :style "margin:bottom:10px;"} (:title x)] 
                        [:p {:style "color:gray" } 
                      (cutoff (get-in x [:post :text]) 200)]]]
                    [:div.col-lg-3 "Users"]
                    [:div.col-lg-1 "Count"]
                    [:div.col-lg-2 (:published_at x)]
                    ]))]]))))


(defn load-discussions
  [{:keys [db event-chan redis conf]} request]
  (let [{:keys [host id body json? user pl]} (common-of request)
        topics (actions/load-topics db 1)
        hosted-pl     (host->pl->lang host)
        platform      (or (or hosted-pl (pl->lang (id-of request :platform)) ) "Python")
        top-disc (actions/load-discussions db)]
  (if json?
    (json-resp top-disc)
    (layout {:website host :title "Top Discussions" :platform platform}
      [:div.row 
        [:div.col-lg-3
          (left-menu host platform "open-source")]
        [:div.col-lg-9
          (panelx "Discussions" "" "" 
          [:div.row
            [:div.btn-group
                [:button.btn.btn-default.dropdown-toggle {:type :button :data-toggle :dropdown } "Topics" [:span.caret]]
                [:ul.dropdown-menu
                (for [t topics]
                  [:li [:a {:href (format "/topic/%s" (:slug t))} (:name t)]]
                  )]]
            [:hr]
          (for [t topics]
            [:div.bs-callout.bs-callout-danger
              [:h4 [:a {:href (format "/topic/%s" (:id t))} (:name t)]
                    [:a.pull-right.btn.btn-primary.btn-xs {:href "/discussion/new"} "Start a Discussion"]]
              (for [x top-disc]
                  [:div.row
                    [:div.col-lg-6
                      [:a {:href (str "/discussion/" (:id x))} (:title x)
                      [:p {:style "color:gray"}
                      (cutoff (get-in x [:post :text]) 30)]]]
                    [:div.col-lg-3 "Users"]
                    [:div.col-lg-1 "Count"]
                    [:div.col-lg-2 (:published_at x)]
                    ])])])]]))))

(defn get-discussion 
  [{:keys [db event-chan redis conf]} request]
  (let [{:keys [host id body json? user platform]} (common-of request)
        disc-id (BigInteger. id)
        discussion (actions/load-discussion db disc-id)
        posts (actions/load-discussion-posts db disc-id)]
    (log/info "[DISC]Loading " id discussion)
    (if json?
      (json-resp discussion)
      (html-resp
          (layout host 
               "" ""
            [:div.row 
              [:div.col-lg-3
                (left-menu host platform "open-source")]
              [:div.col-lg-9
                [:div.bs-callout.bs-callout-danger {:style "background-color:#f4f4f4;" }
                  [:div.post-head.row 
                        [:div.col-lg-6
                        (render-user (actions/load-user2 db "bcambel") :show-followers false)]
                        [:div.col-lg-6
                        [:div.btn-group.pull-right.post-actions
                        [:a.btn.pull-right {:href (format "/discussion/%s/post/%s/edit" disc-id (:id (:post discussion))) :style "font-weight:bolder;"} [:i.fa.fa-edit]]
                        [:a.btn. {:href "#reply-section" :onclick "$('#reply-section').toggle();$('textarea').focus();" :style "display:block;"} 
                          [:i.fa.fa-reply] "Reply"]]
                        ]
                        ]
                  [:h3 (:title discussion)]
                  [:p (md-to-html-string (get-in discussion [:post :text]))]]
                ; [:hr]
                (when-not (!!nil? posts)
                  [:div.bs-callout.bs-callout-warning [:h4 "No posts yet."] 
                    [:p "Be the first one to answer! The unicorns will be with you all the time."]])
                 (for [p posts]
                  [:div.bs-callout.row.post {:id (str "post-" (:id p)) :data-id (:id p) }
                    [:div.post-head.row
                      [:div.col-lg-6
                        (render-user (actions/load-user2 db "bcambel"))]
                      [:div.col-lg-6
                        [:div.btn-group.pull-right.post-actions
                        [:a.btn {:href "#"} [:i.fa.fa-reply]]
                        [:a.btn {:href "#"} [:i.fa.fa-share]]
                        [:a.btn {:href (format "/discussion/%s/post/%s/edit" disc-id (:id p)) } [:i.fa.fa-edit]]
                        [:a.btn {:href "#" :data-remote :true 
                                            :data-method :POST
                                            :data-redirect :true
                                            :data-url (format "/discussion/%s/post/%s/delete" disc-id (:id p) ) } 
                          [:i.fa.fa-trash]]]
                      ]
                    ]
                    [:div.post-body ;{:id (str "post-" (:id p))}
                      (md-to-html-string (:text p) :heading-anchors true :reference-links? true)]])
                [:hr]
                [:div.row.discussion-buttons
                [:a.btn.btn-primary.btn-md {:href "#" :onclick "$('#reply-section').toggle();return false;"} [:i.fa.fa-reply] "Reply"]

                [:a.btn.btn-success.btn-md {:href "#" :onclick "return false;"} [:i.fa.fa-share] "Share"]
                ]

                [:div#reply-section.bs-callout.bs-callout-info {:style "display:none;"}
                  [:h4 "Reply to the post"]
                  [:form {:data-remote :true :data-redirect :true :action (str "/discussion/" id  "/post/create") :method :POST}
                    [:div.form-group
                      [:textarea.form-control {:name :text :rows 5 :data-provide :markdown :data-iconlibrary :fa}]]
                    [:button.btn.btn-success {:type :submit} [:i.fa.fa-reply] "Post"]]]
                [:hr]
                ]
              [:div.col-lg-2 ]
            ])))))

(defn ^:private following-discussion
  [f act-name {:keys [db event-chan redis conf]} request]
  (let [{:keys [host id body json? user pl]} (common-of request)
        
        discussion-id (BigInteger. id)]
    (let [result (f db discussion-id user)]
      (event-pipe/follow-discussion act-name event-chan {:user user :id discussion-id})
      (json-resp result))))

(def follow-discussion (partial following-discussion actions/follow-discussion :follow-discussion))
(def unfollow-discussion (partial following-discussion actions/unfollow-discussion :unfollow-discussion))

(defn get-discussion-posts
  [{:keys [db event-chan redis conf]} request]
  (let [host  (host-of request)
        body (body-of request)
        id (id-of request)
        platform 1
        user (whois request)
        discussion-id (BigInteger. id)
        data (utils/mapkeyw body)]
    (let [result (actions/load-discussion-posts db discussion-id )]
      (json-resp result))))

(defn rm-post-discussion
  [{:keys [db event-chan redis conf]} request]
  (let [{:keys [host id body json? user]} (common-of request)
        post-id (id-of request :pid)]
    (actions/delete-post db (BigInteger. post-id))
    (if json? 
      (json-resp {:ok 1 :url (str "/discussion/" id)})
      (redirect (str "/discussion/" id))
      )
  ))

(defn update-post-discussion
  [{:keys [db event-chan redis conf]} request]
  (let [{:keys [host id body json? user]} (common-of request)
        post-id (BigInteger. (id-of request :pid))
        disc-id (BigInteger. id)
        post (actions/load-post db post-id)
        data (mapkeyw body)]
    (log/warn data)
    (actions/update-post db post-id (select-keys data [:text]))
    (json-resp {:url (str "/discussion/" disc-id)})))

(defn edit-post-discussion
  [{:keys [db event-chan redis conf]} request]
  (let [{:keys [host id body json? user]} (common-of request)
        post-id (BigInteger. (id-of request :pid))
        disc-id (BigInteger. id)
        discussion (actions/load-discussion db disc-id)
        post (actions/load-post db post-id)]
    (layout host 
      [:div#reply-section.bs-callout.bs-callout-info
        [:h4 "Editing your post for the following discussion"]
        [:p (md-to-html-string (get-in discussion [:post :text]))]
        [:hr]
        [:form {:data-remote :true :action (format "/discussion/%s/post/%s/edit" disc-id post-id ) 
                :method :POST :data-redirect :true}
          [:div.form-group
            [:label "You said.."]
            [:textarea.form-control {:type :text :name :text 
                                    :rows 10 :data-provide :markdown 
                                    :data-iconlibrary :fa} (:text post)]]
          [:button.btn.btn-success {:type :submit} "Save"]]])))

(defn post-discussion
  [{:keys [db event-chan redis conf]} request]
  (log/warn request)
  (let [host  (host-of request)
        body (body-of request)
        platform 1
        id (id-of request)
        user (whois request)
        discussion-id (BigInteger. id)
        data (utils/mapkeyw body)]
        (log/warn host body)
    (let [result (actions/new-discussion-post db user discussion-id data)]
      (try+ 
        (event-pipe/post-discussion event-chan {:post result :discussion-id discussion-id :current-user user}))
      (json-resp {:id (str result) 
        :url (format "/discussion/%s" id )})))) ;(str result)

(defn create-discussion
  [{:keys [db event-chan redis conf]} request]
  (log/warn request)
  (let [{:keys [host id body json? user pl]} (common-of request)
        platform 1
        user (whois request)
        data (utils/mapkeyw body)]
        (log/warn data)
    (let [res (try
                  (actions/create-discussion db platform user data)
                  (catch Throwable t
                    (log/error t)))
          response (if (nil? res)
                        {:ok false}
                        {:id (:id res) :url (str "/discussion/" (:id res)) })]
          (log/warn res)
      (try+ (event-pipe/create-discussion event-chan {:discussion res  :current-user user}))
      (if json?
        (json-resp response)
        (redirect (str "/discussion/" res))))))

(defn new-discussion
  [{:keys [db event-chan redis conf]} request]
  (let [host (host-of request)]
  (html-resp 
    (layout host 
      [:div.bs-callout.bs-callout-danger {:style "background-color:#EEEEEE;"}
        [:h4 "Heyo, Start a new discussion here! But.."]
        [:p "Please " [:b "search"] " first before creating new discussion"]
        [:p [:b "Always keep in mind, there is no stupid question, only stupid answer!"]]]
      [:div#reply-section.bs-callout.bs-callout-info
        [:h4 "Discussion details"]
        [:form {:data-remote :true :action "/discussion/create" :method :POST :data-redirect :true}
          [:div.form-group
            [:label "Question"]
            [:input.form-control {:type :text :name :title}]]
          [:div.form-group
            [:label "Explain.."]
            [:textarea.form-control {:type :text :name :post :rows 10 :data-provide :markdown :data-iconlibrary :fa}]]

          [:button.btn.btn-success {:type :submit} "Start Discussion"]
            ]]
  ))))

(defn discussions
  [{:keys [db event-chan redis conf]} request]
  (log/warn request)
  (let [{:keys [host id body json? user platform 
                req-id limit-by url hosted-pl]} (common-of request)]
    (when-let [discussion-list (actions/list-top-disc db platform 50)]
      (if json?
        (json-resp discussion-list)
        (html-resp 
          (layout {:website host :title "Top Discussions" :platform platform}
            [:div
              [:div.row
                (panel [:a {:href (format "/%s/discussions" platform)} "Discussions"]
                [:ul {:style "list-style-type:none;padding-left:1px;" }
                  (for [x discussion-list]
                    [:li 
                      [:a {:href (str "/discussion/" (:id x))} (:title x) 
                        [:p {:style "color:gray" } (get-in x [:post :text])]]])])]]))))))
