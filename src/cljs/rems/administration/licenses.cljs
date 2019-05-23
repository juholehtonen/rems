(ns rems.administration.licenses
  (:require [re-frame.core :as rf]
            [rems.administration.administration :refer [administration-navigator-container]]
            [rems.administration.status-flags :as status-flags]
            [rems.atoms :refer [readonly-checkbox document-title]]
            [rems.spinner :as spinner]
            [rems.status-modal :as status-modal]
            [rems.table2 :as table2]
            [rems.text :refer [localize-time text]]
            [rems.util :refer [dispatch! put! fetch]]))

(rf/reg-event-fx
 ::enter-page
 (fn [{:keys [db]}]
   {:db (assoc db ::display-old? false)
    :dispatch-n [[::fetch-licenses]
                 [:rems.table2/reset]]}))

(rf/reg-event-db
 ::fetch-licenses
 (fn [db]
   (fetch "/api/licenses/" {:url-params {:disabled true
                                         :expired (::display-old? db)
                                         :archived (::display-old? db)}
                            :handler #(rf/dispatch [::fetch-licenses-result %])})
   (assoc db ::loading? true)))

(rf/reg-event-db
 ::fetch-licenses-result
 (fn [db [_ licenses]]
   (-> db
       (assoc ::licenses licenses)
       (dissoc ::loading?))))

(rf/reg-sub ::licenses (fn [db _] (::licenses db)))
(rf/reg-sub ::loading? (fn [db _] (::loading? db)))

(rf/reg-event-fx
 ::update-license
 (fn [_ [_ item description]]
   (status-modal/common-pending-handler! description)
   (put! "/api/licenses/update"
         {:params (select-keys item [:id :enabled :archived])
          :handler (partial status-flags/common-update-handler! #(rf/dispatch [::fetch-licenses]))
          :error-handler status-modal/common-error-handler!})
   {}))

(rf/reg-event-fx
 ::set-display-old?
 (fn [{:keys [db]} [_ display-old?]]
   {:db (assoc db ::display-old? display-old?)
    :dispatch [::fetch-licenses]}))

(rf/reg-sub ::display-old? (fn [db _] (::display-old? db)))

(defn- to-create-licenses []
  [:a.btn.btn-primary
   {:href "/#/administration/create-license"}
   (text :t.administration/create-license)])

(defn- to-view-license [license-id]
  [:a.btn.btn-primary
   {:href (str "/#/administration/licenses/" license-id)}
   (text :t.administration/view)])

(rf/reg-sub
 ::licenses-table-rows
 (fn [_ _]
   [(rf/subscribe [::licenses])])
 (fn [[licenses] _]
   (map (fn [license]
          {:key (:id license)
           :title {:value (:title license)}
           :type {:value (:licensetype license)}
           :start (let [value (:start license)]
                    {:value value
                     :display-value (localize-time value)})
           :end (let [value (:end license)]
                  {:value value
                   :display-value (localize-time value)})
           :active (let [checked? (not (:expired license))]
                     {:td [:td.active
                           [readonly-checkbox checked?]]
                      :sort-value (if checked? 1 2)})
           :commands {:td [:td.commands
                           [to-view-license (:id license)]
                           [status-flags/enabled-toggle license #(rf/dispatch [::update-license %1 %2])]
                           [status-flags/archived-toggle license #(rf/dispatch [::update-license %1 %2])]]}})
        licenses)))

(defn- licenses-list []
  (let [licenses-table {:id ::licenses
                        :columns [{:key :title
                                   :title (text :t.administration/licenses)}
                                  {:key :type
                                   :title (text :t.administration/type)}
                                  {:key :start
                                   :title (text :t.administration/created)}
                                  {:key :end
                                   :title (text :t.administration/end)}
                                  {:key :active
                                   :title (text :t.administration/active)
                                   :filterable? false}
                                  {:key :commands
                                   :sortable? false
                                   :filterable? false}]
                        :rows [::licenses-table-rows]
                        :default-sort-column :title}]
    [:div
     [table2/search licenses-table]
     [table2/table licenses-table]]))

(defn licenses-page []
  (into [:div
         [administration-navigator-container]
         [document-title (text :t.administration/licenses)]]
        (if @(rf/subscribe [::loading?])
          [[spinner/big]]
          [[to-create-licenses]
           [status-flags/display-old-toggle
            @(rf/subscribe [::display-old?])
            #(rf/dispatch [::set-display-old? %])]
           [licenses-list]])))
