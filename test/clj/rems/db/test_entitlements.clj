(ns ^:integration rems.db.test-entitlements
  (:require [cheshire.core :as cheshire]
            [clj-time.core :as time]
            [clojure.test :refer :all]
            [rems.db.core :as db]
            [rems.db.applications :as applications]
            [rems.db.entitlements :as entitlements]
            [rems.db.testing :refer [test-db-fixture rollback-db-fixture test-data-fixture]]
            [rems.testing-util :refer [suppress-logging]]
            [stub-http.core :as stub]))

(use-fixtures
  :once
  (suppress-logging "rems.db.entitlements")
  test-db-fixture
  rollback-db-fixture
  test-data-fixture)

(def +entitlements+
  [{:resid "res1" :catappid 11 :userid "user1" :start (time/date-time 2001 10 11) :mail "user1@tes.t"}
   {:resid "res2" :catappid 12 :userid "user2" :start (time/date-time 2002 10 11) :mail "user2@tes.t"}])

(def +expected-payload+
  [{"resource" "res1" "application" 11 "user" "user1" "mail" "user1@tes.t"}
   {"resource" "res2" "application" 12 "user" "user2" "mail" "user2@tes.t"}])

(defn run-with-server
  "Run callback with a mock entitlements http server set up.
   Return sequence of data received by mock server."
  [endpoint-spec callback]
  (with-open [server (stub/start! {"/entitlements" endpoint-spec})]
    (with-redefs [rems.config/env {:entitlements-target
                                   {:add (str (:uri server) "/entitlements")}}]
      (callback)
      (for [r (stub/recorded-requests server)]
        (cheshire/parse-string (get-in r [:body "postData"]))))))

(deftest test-post-entitlements
  (let [log (atom [])]
    (with-redefs [db/log-entitlement-post! #(swap! log conj %)]
      (testing "ok"
        (is (= [+expected-payload+]
               (run-with-server {:status 200}
                                #(#'entitlements/post-entitlements :add +entitlements+))))
        (let [[{payload :payload status :status}] @log]
          (is (= 200 status))
          (is (= +expected-payload+ (cheshire/parse-string payload))))
        (reset! log []))
      (testing "not found"
        (run-with-server {:status 404}
                         #(#'entitlements/post-entitlements :add +entitlements+))
        (let [[{payload :payload status :status}] @log]
          (is (= 404 status))
          (is (= +expected-payload+ (cheshire/parse-string payload))))
        (reset! log []))
      (testing "timeout"
        (run-with-server {:status 200 :delay 5000} ;; timeout of 2500 in code
                         #(#'entitlements/post-entitlements :add +entitlements+))
        (let [[{payload :payload status :status}] @log]
          (is (= "exception" status))
          (is (= +expected-payload+ (cheshire/parse-string payload)))))
      (testing "no server"
        (with-redefs [rems.config/env {:entitlements-target "http://invalid/entitlements"}]
          (#'entitlements/post-entitlements :add +entitlements+)
          (let [[{payload :payload status :status}] @log]
            (is (= "exception" status))
            (is (= +expected-payload+ (cheshire/parse-string payload)))))))))

(deftest test-entitlement-granting
  (with-open [server (stub/start! {"/add" {:status 200}
                                   "/remove" {:status 200}})]
    (with-redefs [rems.config/env {:entitlements-target
                                   {:add (str (:uri server) "/add")
                                    :remove (str (:uri server) "/remove")}}]
      (let [uid "bob"
            admin "owner"
            organization "foo"
            workflow {:type :workflow/dynamic :handlers [admin]}
            wfid (:id (db/create-workflow! {:organization "abc" :modifieruserid "owner" :owneruserid "owner" :title "dynamic" :fnlround -1 :workflow (cheshire/generate-string workflow)}))
            formid (:id (db/create-form! {:organization "abc" :title "internal-title" :user "owner"}))
            res1 (:id (db/create-resource! {:resid "resource1" :organization organization :owneruserid admin :modifieruserid admin}))
            res2 (:id (db/create-resource! {:resid "resource2" :organization organization :owneruserid admin :modifieruserid admin}))
            item1 (:id (db/create-catalogue-item! {:title "item1" :form formid :resid res1 :wfid wfid}))
            item2 (:id (db/create-catalogue-item! {:title "item2" :form formid :resid res2 :wfid wfid}))]
        (db/add-user! {:user uid :userattrs (cheshire/generate-string {"mail" "b@o.b"})})
        (db/add-user! {:user admin :userattrs nil})
        (let [app-id (:application-id (applications/create-application! uid [item1 item2]))]
          (is (nil? (applications/command! {:type :application.command/submit
                                            :actor uid
                                            :application-id app-id
                                            :time (time/now)})))
          (testing "submitted application should not yet cause entitlements"
            (rems.poller.entitlements/run)
            (is (empty? (db/get-entitlements {:application app-id})))
            (is (empty? (stub/recorded-requests server))))

          (is (nil? (applications/command! {:type :application.command/approve
                                            :actor admin
                                            :application-id app-id
                                            :comment ""
                                            :time (time/now)})))

          (testing "approved application generated entitlements"
            (rems.poller.entitlements/run)
            (rems.poller.entitlements/run) ;; run twice to check idempotence
            (testing "db"
              (= [1 2]
                 (db/get-entitlements {:application app-id})))
            (testing "POST"
              (let [data (first (stub/recorded-requests server))
                    target (:path data)
                    body (cheshire/parse-string (get-in data [:body "postData"]))]
                (is (= "/add" target))
                (is (= [{"resource" "resource1" "application" app-id "user" "bob" "mail" "b@o.b"}
                        {"resource" "resource2" "application" app-id "user" "bob" "mail" "b@o.b"}]
                       body)))))

          (is (nil? (applications/command! {:type :application.command/close
                                            :actor admin
                                            :application-id app-id
                                            :comment ""
                                            :time (time/now)})))

          (testing "closed application should end entitlements"
            (rems.poller.entitlements/run)
            (testing "db"
              (= [1 2]
                 (db/get-entitlements {:application app-id})))
            (testing "POST"
              (let [data (second (stub/recorded-requests server))
                    target (:path data)
                    body (cheshire/parse-string (get-in data [:body "postData"]))]
                (is (= "/remove" target))
                (is (= [{"resource" "resource1" "application" app-id "user" "bob" "mail" "b@o.b"}
                        {"resource" "resource2" "application" app-id "user" "bob" "mail" "b@o.b"}]
                       body))))))))))
