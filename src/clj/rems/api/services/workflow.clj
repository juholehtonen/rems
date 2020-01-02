(ns rems.api.services.workflow
  (:require [rems.db.applications :as applications]
            [rems.db.catalogue :as catalogue]
            [rems.db.core :as db]
            [rems.db.users :as users]
            [rems.db.workflow :as workflow]
            [rems.json :as json]))

(defn create-workflow! [workflow]
  (let [id (workflow/create-workflow! workflow)]
    {:id id
     :success (not (nil? id))}))

(defn- unrich-workflow [workflow]
  ;; TODO: keep handlers always in the same format, to avoid this conversion (we can ignore extra keys)
  (if (get-in workflow [:workflow :handlers])
    (update-in workflow [:workflow :handlers] #(map :userid %))
    workflow))

(defn edit-workflow! [{:keys [id title handlers]}]
  (let [workflow (unrich-workflow (workflow/get-workflow id))
        workflow-body (cond-> (:workflow workflow)
                        handlers (assoc :handlers handlers))]
    (db/edit-workflow! {:id id
                        :title title
                        :workflow (json/generate-string workflow-body)}))
  (applications/reload-cache!)
  {:success true})

(defn set-workflow-enabled! [command]
  (db/set-workflow-enabled! (select-keys command [:id :enabled]))
  {:success true})

(defn set-workflow-archived! [{:keys [id archived]}]
  (let [workflow (workflow/get-workflow id)
        archived-licenses (filter :archived (:licenses workflow))
        catalogue-items
        (->> (catalogue/get-localized-catalogue-items {:workflow id
                                                       :archived false})
             (map #(select-keys % [:id :title :localizations])))]
    (cond
      (and archived (seq catalogue-items))
      {:success false
       :errors [{:type :t.administration.errors/workflow-in-use
                 :catalogue-items catalogue-items}]}

      (and (not archived) (seq archived-licenses))
      {:success false
       :errors [{:type :t.administration.errors/license-archived
                 :licenses archived-licenses}]}

      :else
      (do
        (db/set-workflow-archived! {:id id
                                    :archived archived})
        {:success true}))))

(defn get-workflow [id] (workflow/get-workflow id))
(defn get-workflows [filters] (workflow/get-workflows filters))
(defn get-available-actors [] (users/get-users))

(defn get-handlers []
  (let [workflows (workflow/get-workflows {:enabled true
                                           :archived false})
        handlers (mapcat (fn [wf]
                           (get-in wf [:workflow :handlers]))
                         workflows)]
    (->> handlers distinct (sort-by :userid))))
