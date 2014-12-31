(ns hsm.controllers.project
  (:require 
    [clojure.tools.logging :as log]
    [clojure.java.io :as io]
    [cheshire.core :refer :all]
    [ring.util.response :as resp]
    [hiccup.core :as hic]
    [hiccup.page :as hic.pg]
    [hiccup.element :as hic.el]
    [hsm.actions :as actions]
    [hsm.ring :refer [json-resp html-resp]]
    [hsm.views :as views]
    [hsm.helpers :refer [pl->lang]]
    [hsm.utils :as utils :refer [host-of body-of whois id-of]]))

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
						[:a {:target "_blank" :href (format "/p/%s" (get x :full_name))} (get x :full_name)
						[:p {:style "color:gray"} (get x :description)]]]
				])]])

(defn list-top-proj
  [[db event-chan] request]
  (log/warn request)
  (let [host  			(host-of request)
        body 				(body-of request)
        user 				(whois request)
        data 				(utils/mapkeyw body)
        platform 	 	(pl->lang (id-of request :platform))
        is-json 		false
        view 				(get-in request [:params :view])
        view-fn 		(if (= view "grid") grid-view list-view)
        limit 			(or (get-in request [:params :limit]) (str 20))
        limit-by 		(or (Integer/parseInt limit) 20)]
    (let [top-projects (actions/list-top-proj db platform limit-by)
    			keyset (keys (first top-projects))]
      (if is-json
        (json-resp top-projects)
        (html-resp
          (views/layout host
            (view-fn top-projects keyset)))))))

(defn get-proj
	[[db event-chan] request]
	(let [host  (host-of request)
				is-json false
				id (format "%s/%s" (id-of request :user) (id-of request :project))]
		(when-let [proj (first (actions/load-project db id))]
			(if is-json 
				(json-resp proj)
				(html-resp 
					(views/layout host 
						[:div.row 
							[:div.col-lg-2 {:style "text-align: center;padding-top:15px;"}
								[:h3 (:watchers proj)]
								[:form {:action "/ajax/project/follow" :method "POST"}
									[:input {:type "hidden" :value (:id proj)}]
									[:button.btn.btn-primary {:type "submit"} "Love It"]
								]]
							[:div.col-lg-8 
								[:h1 (:name proj)]
								[:a {:href (str "https://github.com/" (:full_name proj))} "Github"]
								[:p.lead (:description proj)]
								]

						; (str id (generate-string proj))
						]
						[:hr]
						))))))