(ns ^:integration rems.db.test-csv
  (:require [clojure.test :refer :all]
            [rems.db.applications :as applications]
            [rems.db.test-data :as test-data]
            [rems.db.testing :refer [rollback-db-fixture test-db-fixture]]
            [rems.db.csv :as csv]
            [rems.text :as text])
  (:import [org.joda.time DateTime]))

(use-fixtures :once test-db-fixture)
(use-fixtures :each rollback-db-fixture)

(deftest test-print-to-csv
  (testing "with default settings"
    (is (= "col1,col2\nval1,val2\nval3,val4\n"
           (csv/print-to-csv :column-names ["col1" "col2"]
                             :rows [["val1" "val2"]
                                    ["val3" "val4"]]
                             :separator ","))))

  (testing "different separator"
    (is (= "col1;col2\nval1;val2\nval3;val4\n"
           (csv/print-to-csv :column-names ["col1" "col2"]
                             :rows [["val1" "val2"]
                                    ["val3" "val4"]]
                             :separator ";"))))

  (testing "quote strings"
    (is (= "\"col1\",\"col2\"\n\"val1\",\"val2\"\n\"val3\",\"val4\"\n"
           (csv/print-to-csv :column-names ["col1" "col2"]
                             :rows [["val1" "val2"]
                                    ["val3" "val4"]]
                             :separator ","
                             :quote-strings? true))))

  (testing "do not quote numeric values"
    (is (= "\"col1\",\"col2\"\n\"val1\",\"val2\"\n\"val3\",4\n"
           (csv/print-to-csv :column-names ["col1" "col2"]
                             :rows [["val1" "val2"]
                                    ["val3" 4]]
                             :separator ","
                             :quote-strings? true))))

  (testing "do not quote nil values"
    (is (= "\"col1\",\"col2\"\n\"val1\",\"val2\"\n,\"val4\"\n"
           (csv/print-to-csv :column-names ["col1" "col2"]
                             :rows [["val1" "val2"]
                                    [nil "val4"]]
                             :separator ","
                             :quote-strings? true))))

  (testing "escape quotes inside strings when strings are quoted"
    (is (= "\"col1\",\"col2\"\n\"\\\"so called\\\" value\",\"val2\"\n\"val3\",\"val4\"\n"
           (csv/print-to-csv :column-names ["col1" "col2"]
                             :rows [["\"so called\" value" "val2"]
                                    ["val3" "val4"]]
                             :separator ","
                             :quote-strings? true))))

  (testing "do not escape quotes inside strings when strings are not quoted"
    (is (= "col1,col2\n\"so called\" value,val2\nval3,val4\n"
           (csv/print-to-csv :column-names ["col1" "col2"]
                             :rows [["\"so called\" value" "val2"]
                                    ["val3" "val4"]]
                             :separator ",")))))

(def ^:private applicant "applicant")
(def ^:private test-time (DateTime. 1000))

;; TODO: This could be non-integration non-db test if the application was
;;       created from events.
(deftest test-applications-to-csv
  (test-data/create-user! {:eppn applicant :commonName "Alice Applicant" :mail "alice@applicant.com"})
  (let [form-id (test-data/create-form!
                 {:form/fields [{:field/title {:en "Application title"
                                               :fi "Hakemuksen otsikko"}
                                 :field/optional false
                                 :field/type :description}
                                {:field/title {:en "Description"
                                               :fi "Kuvaus"}
                                 :field/optional true
                                 :field/type :description}]})
        cat-id (test-data/create-catalogue-item! {:title {:en "Test resource"
                                                          :fi "Testiresurssi"}
                                                  :form-id form-id})
        app-id (test-data/create-application! {:catalogue-item-ids [cat-id]
                                               :actor applicant})
        external-id (:application/external-id (applications/get-unrestricted-application app-id))
        get-application #(applications/get-unrestricted-application app-id)]

    (testing "draft applications not included as default"
      (is (= ""
             (csv/applications-to-csv [(get-application)] "owner"))))

    (testing "draft applications included when explicitly set"
      (is (= (str "\"Id\",\"External id\",\"Applicant\",\"Submitted\",\"State\",\"Resources\",\"Application title\",\"Description\"\n"
                  app-id ",\"" external-id "\",\"Alice Applicant\",,\"Draft\",\"Test resource\",\"\",\"\"\n")
             (csv/applications-to-csv [(get-application)] "owner" :include-drafts true))))

    (test-data/fill-form! {:application-id app-id
                           :actor applicant
                           :field-value "test value"})

    (testing "form filled out"
      (is (= (str "\"Id\",\"External id\",\"Applicant\",\"Submitted\",\"State\",\"Resources\",\"Application title\",\"Description\"\n"
                  app-id ",\"" external-id "\",\"Alice Applicant\",,\"Draft\",\"Test resource\",\"test value\",\"\"\n")
             (csv/applications-to-csv [(get-application)] "owner" :include-drafts true))))

    (test-data/accept-licenses! {:application-id app-id
                                 :actor applicant})

    (test-data/command! {:type :application.command/submit
                         :application-id app-id
                         :actor applicant
                         :time test-time})

    (testing "submitted application"
      (is (= (str "\"Id\",\"External id\",\"Applicant\",\"Submitted\",\"State\",\"Resources\",\"Application title\",\"Description\"\n"
                  app-id ",\"" external-id "\",\"Alice Applicant\",\""
                  (text/localize-time test-time)
                  "\",\"Applied\",\"Test resource\",\"test value\",\"\"\n")
             (csv/applications-to-csv [(get-application)] "owner"))))))
