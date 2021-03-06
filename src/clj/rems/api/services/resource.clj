(ns rems.api.services.resource
  (:require [rems.api.services.util :as util]
            [rems.db.catalogue :as catalogue]
            [rems.db.core :as db]
            [rems.db.licenses :as licenses]
            [rems.db.resource :as resource])
  (:import (org.postgresql.util PSQLException)))

(defn get-resource [id] (resource/get-resource id))
(defn get-resources [filters] (resource/get-resources filters))

(defn create-resource! [{:keys [resid organization licenses] :as command} user-id]
  (util/check-allowed-organization! organization)
  (let [id (:id (db/create-resource! {:resid resid
                                      :organization organization
                                      :owneruserid user-id
                                      :modifieruserid user-id}))]
    (doseq [licid licenses]
      (db/create-resource-license! {:resid id
                                    :licid licid}))
    {:success true
     :id id}))

(defn set-resource-enabled! [{:keys [id enabled]}]
  (util/check-allowed-organization! (:organization (get-resource id)))
  (db/set-resource-enabled! {:id id :enabled enabled})
  {:success true})

(defn set-resource-archived! [{:keys [id archived]}]
  (util/check-allowed-organization! (:organization (get-resource id)))
  (let [catalogue-items
        (->> (catalogue/get-localized-catalogue-items {:resource-id id
                                                       :archived false})
             (map #(select-keys % [:id :title :localizations])))
        licenses (licenses/get-resource-licenses id)
        archived-licenses (filter :archived licenses)]
    (cond
      (and archived (seq catalogue-items))
      {:success false
       :errors [{:type :t.administration.errors/resource-in-use
                 :catalogue-items catalogue-items}]}

      (and (not archived) (seq archived-licenses))
      {:success false
       :errors [{:type :t.administration.errors/license-archived
                 :licenses archived-licenses}]}

      :else
      (do
        (db/set-resource-archived! {:id id
                                    :archived archived})
        {:success true}))))
