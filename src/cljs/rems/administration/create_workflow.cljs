(ns rems.administration.create-workflow
  (:require [clojure.string :as str]
            [re-frame.core :as rf]
            [rems.administration.administration :as administration]
            [rems.administration.components :refer [radio-button-group text-field]]
            [rems.atoms :as atoms :refer [enrich-user document-title]]
            [rems.collapsible :as collapsible]
            [rems.config :as config]
            [rems.dropdown :as dropdown]
            [rems.fields :as fields]
            [rems.flash-message :as flash-message]
            [rems.roles :as roles]
            [rems.spinner :as spinner]
            [rems.text :refer [text]]
            [rems.util :refer [navigate! fetch post! put! trim-when-string]]))

(rf/reg-event-fx
 ::enter-page
 (fn [{:keys [db]} [_ workflow-id]]
   (let [editing? (not (nil? workflow-id))
         roles (get-in db [:identity :roles])
         user-organization (get-in db [:identity :user :organization])
         all-organizations (get-in db [:config :organizations])
         organization (cond
                        (roles/disallow-setting-organization? roles)
                        user-organization

                        (= (count all-organizations) 1)
                        (first all-organizations)

                        :else
                        nil)]
     {:db (assoc db
                 ::workflow-id workflow-id
                 ::loading-workflow? (not (nil? workflow-id))
                 ::actors nil
                 ::editing? editing?
                 ::form (merge {:type :workflow/default}
                               (when organization
                                 {:organization organization}))
                 ::organization-read-only? (or editing?
                                               (not (nil? organization))))
      ::fetch-actors nil
      ::fetch-workflow workflow-id})))

(rf/reg-sub ::workflow-id (fn [db _] (::workflow-id db)))
(rf/reg-sub ::editing? (fn [db _] (::editing? db)))
(rf/reg-sub ::loading? (fn [db _] (or (::loading-workflow? db) (nil? (::actors db)))))
(rf/reg-sub ::organization-read-only? (fn [db _] (::organization-read-only? db)))

;;; form state

(rf/reg-sub ::form (fn [db _] (::form db)))

(rf/reg-event-db ::set-form-field (fn [db [_ keys value]] (assoc-in db (concat [::form] keys) value)))

(rf/reg-sub ::selected-organization (fn [db _] (get-in db [::form :organization])))
(rf/reg-event-db ::set-selected-organization (fn [db [_ organization]] (assoc-in db [::form :organization] organization)))

;;; fetching workflow

(rf/reg-fx
 ::fetch-workflow
 (fn [workflow-id]
   (when workflow-id
     (fetch (str "/api/workflows/" workflow-id)
            {:handler #(rf/dispatch [::fetch-workflow-result %])
             :error-handler (flash-message/default-error-handler :top "Fetch workflow")}))))

(rf/reg-event-db
 ::fetch-workflow-result
 (fn [db [_ workflow]]
   (let [new-stuff {:title (:title workflow)
                    :organization (:organization workflow)
                    :type (:type (:workflow workflow))
                    :handlers (mapv enrich-user (get-in workflow [:workflow :handlers]))}]
     (-> db
         (update ::form merge new-stuff)
         (dissoc ::loading-workflow?)))))

;;; form submit

(def workflow-types #{:workflow/default :workflow/decider :workflow/master})

(defn needs-handlers? [type]
  (contains? #{:workflow/default :workflow/decider :workflow/master} type))

(defn- valid-create-request? [request]
  (and
   (contains? workflow-types (:type request))
   (if (needs-handlers? (:type request))
     (seq (:handlers request))
     true)
   (not (str/blank? (:organization request)))
   (not (str/blank? (:title request)))))

(defn build-create-request [form]
  (let [request (merge
                 {:organization (:organization form)
                  :title (trim-when-string (:title form))
                  :type (:type form)}
                 (when (needs-handlers? (:type form))
                   {:handlers (map :userid (:handlers form))}))]
    (when (valid-create-request? request)
      request)))

(defn- valid-edit-request? [request]
  (and (number? (:id request))
       (seq (:handlers request))
       (not (str/blank? (:title request)))))

(defn build-edit-request [id form]
  (let [request {:id id
                 :title (:title form)
                 :handlers (map :userid (:handlers form))}]
    (when (valid-edit-request? request)
      request)))

(rf/reg-event-fx
 ::create-workflow
 (fn [_ [_ request]]
   (let [description [text :t.administration/create-workflow]]
     (post! "/api/workflows/create"
            {:params request
             :handler (flash-message/default-success-handler
                       :top description #(navigate! (str "/administration/workflows/" (:id %))))
             :error-handler (flash-message/default-error-handler :top description)}))
   {}))

(rf/reg-event-fx
 ::edit-workflow
 (fn [_ [_ request]]
   (let [description [text :t.administration/edit-workflow]]
     (put! "/api/workflows/edit"
           {:params request
            :handler (flash-message/default-success-handler
                      :top description #(navigate! (str "/administration/workflows/" (:id request))))
            :error-handler (flash-message/default-error-handler :top description)}))
   {}))

(rf/reg-event-db ::set-handlers (fn [db [_ handlers]] (assoc-in db [::form :handlers] (sort-by :userid handlers))))

(defn- fetch-actors []
  (fetch "/api/workflows/actors"
         {:handler #(rf/dispatch [::fetch-actors-result %])
          :error-handler (flash-message/default-error-handler :top "Fetch actors")}))

(rf/reg-fx ::fetch-actors fetch-actors)

(rf/reg-event-db
 ::fetch-actors-result
 (fn [db [_ actors]]
   (-> db
       (assoc ::actors (map enrich-user actors))
       (dissoc ::loading?))))

(rf/reg-sub ::actors (fn [db _] (::actors db)))


;;;; UI

(def ^:private context
  {:get-form ::form
   :update-form ::set-form-field})

(def ^:private handlers-dropdown-id "handlers-dropdown")
(def ^:private organization-dropdown-id "organization-dropdown")

(defn- workflow-organization-field []
  (let [organizations (:organizations @(rf/subscribe [:rems.config/config]))
        selected-organization @(rf/subscribe [::selected-organization])
        item-selected? #(= % selected-organization)
        readonly @(rf/subscribe [::organization-read-only?])]
    [:div.form-group
     [:label {:for organization-dropdown-id} (text :t.administration/organization)]
     (if readonly
       [fields/readonly-field {:id organization-dropdown-id
                               :value selected-organization}]
       [dropdown/dropdown
        {:id organization-dropdown-id
         :items organizations
         :item-selected? item-selected?
         :on-change #(rf/dispatch [::set-selected-organization %])}])]))

(defn- workflow-title-field []
  [text-field context {:keys [:title]
                       :label (text :t.create-workflow/title)}])

(defn- workflow-type-field []
  [radio-button-group context {:id :workflow-type
                               :keys [:type]
                               :readonly @(rf/subscribe [::editing?])
                               :orientation :horizontal
                               :options (concat
                                         [{:value :workflow/default
                                           :label (text :t.create-workflow/default-workflow)}
                                          {:value :workflow/decider
                                           :label (text :t.create-workflow/decider-workflow)}]
                                         (when (config/dev-environment?)
                                           [{:value :workflow/master
                                             :label (text :t.create-workflow/master-workflow)}]))}])

(defn- save-workflow-button []
  (let [form @(rf/subscribe [::form])
        id @(rf/subscribe [::workflow-id])
        request (if id
                  (build-edit-request id form)
                  (build-create-request form))]
    [:button.btn.btn-primary
     {:type :button
      :on-click (fn []
                  (rf/dispatch [:rems.spa/user-triggered-navigation])
                  (if id
                    (rf/dispatch [::edit-workflow request])
                    (rf/dispatch [::create-workflow request])))
      :disabled (nil? request)}
     (text :t.administration/save)]))

(defn- cancel-button []
  [atoms/link {:class "btn btn-secondary"}
   "/administration/workflows"
   (text :t.administration/cancel)])

(defn workflow-type-description [description]
  [:div.alert.alert-info description])

;; TODO: Eventually filter handlers by the selected organization when
;;   we are sure that all the handlers have the organization information?
(defn- workflow-handlers-field []
  (let [form @(rf/subscribe [::form])
        all-handlers @(rf/subscribe [::actors])
        selected-handlers (set (map :userid (get-in form [:handlers])))]
    [:div.form-group
     [:label {:for handlers-dropdown-id} (text :t.create-workflow/handlers)]
     [dropdown/dropdown
      {:id handlers-dropdown-id
       :items all-handlers
       :item-key :userid
       :item-label :display
       :item-selected? #(contains? selected-handlers (% :userid))
       :multi? true
       :on-change #(rf/dispatch [::set-handlers %])}]]))

(defn default-workflow-form []
  [:div
   [workflow-type-description (text :t.create-workflow/default-workflow-description)]
   [workflow-handlers-field]])

(defn decider-workflow-form []
  [:div
   [workflow-type-description (text :t.create-workflow/decider-workflow-description)]
   [workflow-handlers-field]])

(defn master-workflow-form []
  [:div
   [workflow-type-description (text :t.create-workflow/master-workflow-description)]
   [workflow-handlers-field]])

(defn create-workflow-page []
  (let [form @(rf/subscribe [::form])
        workflow-type (:type form)
        loading? @(rf/subscribe [::loading?])
        editing? @(rf/subscribe [::editing?])
        title (if editing?
                (text :t.administration/edit-workflow)
                (text :t.administration/create-workflow))]
    [:div
     [administration/navigator]
     [document-title title]
     [flash-message/component :top]
     [collapsible/component
      {:id "create-workflow"
       :title title
       :always (if loading?
                 [:div#workflow-loader [spinner/big]]
                 [:div#workflow-editor
                  [workflow-organization-field]
                  [workflow-title-field]
                  [workflow-type-field]

                  (case workflow-type
                    :workflow/default [default-workflow-form]
                    :workflow/decider [decider-workflow-form]
                    :workflow/master [master-workflow-form])

                  [:div.col.commands
                   [cancel-button]
                   [save-workflow-button]]])}]]))
