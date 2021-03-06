(ns rems.actions.request-decision
  (:require [re-frame.core :as rf]
            [rems.actions.action :refer [action-attachment action-button action-comment action-form-view button-wrapper command!]]
            [rems.atoms :refer [enrich-user]]
            [rems.dropdown :as dropdown]
            [rems.flash-message :as flash-message]
            [rems.text :refer [text]]
            [rems.util :refer [fetch]]))

(def ^:private action-form-id "request-decision")
(def ^:private dropdown-id "request-decision-dropdown")

(rf/reg-fx
 ::fetch-potential-deciders
 (fn [on-success]
   (fetch "/api/applications/deciders"
          {:handler on-success
           :error-handler (flash-message/default-error-handler :top "Fetch potential deciders")})))

(rf/reg-event-fx
 ::open-form
 (fn [{:keys [db]} _]
   {:db (assoc db
               ::comment ""
               ::potential-deciders #{}
               ::selected-deciders #{})
    :dispatch [:rems.actions.action/set-attachment-id action-form-id nil]
    ::fetch-potential-deciders #(rf/dispatch [::set-potential-deciders %])}))

(rf/reg-sub ::potential-deciders (fn [db _] (::potential-deciders db)))
(rf/reg-event-db
 ::set-potential-deciders
 (fn [db [_ deciders]]
   (assoc db
          ::potential-deciders (set (map enrich-user deciders))
          ::selected-deciders #{})))

(rf/reg-sub ::selected-deciders (fn [db _] (::selected-deciders db)))
(rf/reg-event-db ::set-selected-deciders (fn [db [_ deciders]] (assoc db ::selected-deciders deciders)))

(rf/reg-sub ::comment (fn [db _] (::comment db)))
(rf/reg-event-db ::set-comment (fn [db [_ value]] (assoc db ::comment value)))

(rf/reg-event-fx
 ::send-request-decision
 (fn [_ [_ {:keys [deciders application-id comment attachments on-finished]}]]
   (command! :application.command/request-decision
             {:application-id application-id
              :comment comment
              :attachments attachments
              :deciders (map :userid deciders)}
             {:description [text :t.actions/request-decision]
              :collapse action-form-id
              :on-finished on-finished})
   {}))

(defn request-decision-action-button []
  [action-button {:id action-form-id
                  :text (text :t.actions/request-decision)
                  :on-click #(rf/dispatch [::open-form])}])

(defn request-decision-view
  [{:keys [application-id selected-deciders potential-deciders comment on-set-comment on-set-deciders on-send]}]
  [action-form-view action-form-id
   (text :t.actions/request-decision)
   [[button-wrapper {:id "request-decision"
                     :text (text :t.actions/request-decision)
                     :class "btn-primary"
                     :on-click on-send
                     :disabled (empty? selected-deciders)}]]
   [:div
    [action-comment {:id action-form-id
                     :label (text :t.form/add-comments-not-shown-to-applicant)
                     :comment comment
                     :on-comment on-set-comment}]
    [action-attachment {:application-id application-id
                        :key action-form-id}]
    [:div.form-group
     [:label {:for dropdown-id} (text :t.actions/request-selection)]
     [dropdown/dropdown
      {:id dropdown-id
       :items potential-deciders
       :item-key :userid
       :item-label :display
       :item-selected? #(contains? (set selected-deciders) %)
       :multi? true
       :on-change on-set-deciders}]]]])

(defn request-decision-form [application-id on-finished]
  (let [selected-deciders @(rf/subscribe [::selected-deciders])
        potential-deciders @(rf/subscribe [::potential-deciders])
        comment @(rf/subscribe [::comment])
        attachments @(rf/subscribe [:rems.actions.action/attachments action-form-id])]
    [request-decision-view {:application-id application-id
                            :selected-deciders selected-deciders
                            :potential-deciders potential-deciders
                            :comment comment
                            :on-set-comment #(rf/dispatch [::set-comment %])
                            :on-set-deciders #(rf/dispatch [::set-selected-deciders %])
                            :on-send #(rf/dispatch [::send-request-decision {:application-id application-id
                                                                             :deciders selected-deciders
                                                                             :comment comment
                                                                             :attachments attachments
                                                                             :on-finished on-finished}])}]))
