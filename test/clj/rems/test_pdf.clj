(ns ^:integration rems.test-pdf
  (:require [clj-time.core :as time]
            [clojure.test :refer :all]
            [rems.db.applications :as applications]
            [rems.db.test-data :as test-data]
            [rems.db.testing :refer [test-db-fixture rollback-db-fixture]]
            [rems.pdf :as pdf]
            [rems.testing-util :refer [with-fixed-time utc-fixture]]
            [rems.text :refer [with-language]]))

(use-fixtures :once
  utc-fixture
  test-db-fixture)
(use-fixtures :each rollback-db-fixture)

(deftest test-pdf-gold-standard
  (test-data/create-user! {:eppn "alice" :commonName "Alice Applicant" :mail "alice@example.com"})
  (test-data/create-user! {:eppn "beth" :commonName "Beth Applicant" :mail "beth@example.com"})
  (let [lic1 (test-data/create-license! {:license/type :link
                                         :license/title {:en "Google license"
                                                 :fi "Google-lisenssi"}
                                         :license/link {:en "http://google.com"
                                                        :fi "http://google.fi"}})
        lic2 (test-data/create-license! {:license/type :text
                                         :license/title {:en "Text license"
                                                 :fi "Tekstilisenssi"}
                                         :license/text {:en "Some text"
                                                        :fi "Tekstiä"}})
        ;; TODO attachment license
        resource (test-data/create-resource! {:resource-ext-id "pdf-resource-ext"
                                              :license-ids [lic1 lic2]})
        form (test-data/create-form! {:form/title "Form"
                                      :form/fields test-data/all-field-types-example})
        catalogue-item (test-data/create-catalogue-item! {:resource-id resource
                                                          :title {:en "Catalogue item"
                                                                  :fi "Katalogi-itemi"}
                                                          :form-id form})
        applicant "alice"
        application-id (test-data/create-application! {:actor applicant
                                                       :catalogue-item-ids [catalogue-item]
                                                       :time (time/date-time 2000)})
        handler "developer"]
    (testing "fill and submit"
      (test-data/fill-form! {:time (time/date-time 2000)
                             :actor applicant
                             :application-id application-id
                             :field-value "pdf test"
                             :optional-fields true})
      (test-data/accept-licenses! {:time (time/date-time 2000)
                                   :actor applicant
                                   :application-id application-id})
      (test-data/command! {:time (time/date-time 2001)
                           :application-id application-id
                           :type :application.command/submit
                           :actor applicant}))
    (testing "add member"
      (test-data/command! {:time (time/date-time 2002)
                           :application-id application-id
                           :type :application.command/add-member
                           :member {:userid "beth"}
                           :actor handler}))
    (testing "approve"
      (test-data/command! {:time (time/date-time 2003)
                           :application-id application-id
                           :type :application.command/approve
                           :comment "approved"
                           :actor handler}))
    (testing "pdf contents"
      (is (= [{}
              [[:heading pdf/heading-style "Application 2000/1: pdf test"]
               [:paragraph "This PDF generated at" " " "2010-01-01 00:00"]
               [:paragraph "State" [:phrase ": " "Approved"]]
               [:heading pdf/heading-style "Applicants"]
               [:paragraph "Applicant" ": " "Alice Applicant (alice) <alice@example.com>"]
               [:paragraph "Member" ": " "Beth Applicant (beth) <beth@example.com>"]
               [:heading pdf/heading-style "Resources"]
               [:list [:phrase "Catalogue item" " (" "pdf-resource-ext" ")"]]]
              [[:heading pdf/heading-style "Terms of use"]
               [:paragraph "Google license"]
               [:paragraph "Text license"]]
              [[:heading pdf/heading-style "Application"]
               [:paragraph {} "This form demonstrates all possible field types. (This text itself is a label field.)"]
               [:paragraph ""]
               [:paragraph {:style :bold} "Application title field"]
               [:paragraph "pdf test"]
               [:paragraph {:style :bold} "Text field"]
               [:paragraph "pdf test"]
               [:paragraph {:style :bold} "Text area"]
               [:paragraph "pdf test"]
               [:paragraph {:style :bold :size 15} "Header"]
               [:paragraph ""]
               [:paragraph {:style :bold} "Date field"]
               [:paragraph "2002-03-04"]
               [:paragraph {:style :bold} "Email field"]
               [:paragraph "user@example.com"]
               [:paragraph {:style :bold} "Attachment"]
               [:paragraph ""]
               [:paragraph {:style :bold} "Option list. Choose the first option to reveal a new field."]
               [:paragraph "First option"]
               [:paragraph {:style :bold} "Conditional field. Shown only if first option is selected above."]
               [:paragraph "pdf test"]
               [:paragraph {:style :bold} "Multi-select list"]
               [:paragraph "First option"]
               [:paragraph {} "The following field types can have a max length."]
               [:paragraph ""]
               [:paragraph {:style :bold} "Text field with max length"]
               [:paragraph "pdf test"]
               [:paragraph {:style :bold} "Text area with max length"]
               [:paragraph "pdf test"]]
              [[:heading pdf/heading-style "Events"]
               [:table
                {:header ["Time" "Event" "Comment"]}
                ["2000-01-01 00:00" "Alice Applicant created a new application." ""]
                ["2000-01-01 00:00" "Alice Applicant saved the application as a draft." ""]
                ["2000-01-01 00:00" "Alice Applicant accepted the terms of use." ""]
                ["2001-01-01 00:00" "Alice Applicant submitted the application for review." ""]
                ["2002-01-01 00:00" "Developer added Beth Applicant to the application." ""]
                ["2003-01-01 00:00" "Developer approved the application." "approved"]]]]
             (with-language :en
               (fn []
                 (with-fixed-time (time/date-time 2010)
                   (fn []
                     (#'pdf/render-application (applications/get-application handler application-id)))))))))
      (testing "pdf rendering succeeds"
        (is (some?
             (with-language :en
               #(pdf/application-to-pdf-bytes (applications/get-application handler application-id))))))))
