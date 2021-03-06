(ns rems.api.licenses
  (:require [compojure.api.sweet :refer :all]
            [rems.api.schema :refer :all]
            [rems.api.services.attachment :as attachment]
            [rems.api.services.licenses :as licenses]
            [rems.api.util :as api-util] ; required for route :roles
            [rems.util :refer [getx-user-id]]
            [ring.middleware.multipart-params :as multipart]
            [ring.swagger.json-schema :as rjs]
            [ring.swagger.upload :as upload]
            [ring.util.http-response :refer :all]
            [schema.core :as s]))

(s/defschema LicenseLocalization
  {:title s/Str
   :textcontent s/Str
   (s/optional-key :attachment-id) (rjs/describe (s/maybe s/Int) "For licenses of type attachment")})

(s/defschema LicenseLocalizations
  (rjs/field {Language LicenseLocalization}
             {:description "Licence localizations keyed by language"
              :example {:en {:title "English title"
                             :textcontent "English content"}
                        :fi {:title "Finnish title"
                             :textcontent "Finnish content"}}}))

(s/defschema CreateLicenseCommand
  {:licensetype (s/enum "link" "text" "attachment")
   ;; TODO make unoptional for consistency with other endpoints?
   (s/optional-key :organization) s/Str
   :localizations LicenseLocalizations})

(s/defschema AttachmentMetadata
  {:id s/Int})

(s/defschema CreateLicenseResponse
  {:success s/Bool
   (s/optional-key :id) s/Int
   (s/optional-key :errors) [s/Any]})

(def licenses-api
  (context "/licenses" []
    :tags ["licenses"]

    (GET "/" []
      :summary "Get licenses"
      :roles #{:owner :organization-owner :handler}
      :query-params [{disabled :- (describe s/Bool "whether to include disabled licenses") false}
                     {archived :- (describe s/Bool "whether to include archived licenses") false}]
      :return Licenses
      (ok (licenses/get-all-licenses (merge (when-not disabled {:enabled true})
                                            (when-not archived {:archived false})))))

    (GET "/:license-id" []
      :summary "Get license"
      :roles #{:owner :organization-owner :handler}
      :path-params [license-id :- (describe s/Int "license id")]
      :return License
      (ok (licenses/get-license license-id)))

    (POST "/create" []
      :summary "Create license"
      :roles #{:owner :organization-owner}
      :body [command CreateLicenseCommand]
      :return CreateLicenseResponse
      (ok (licenses/create-license! command (getx-user-id))))

    (PUT "/archived" []
      :summary "Archive or unarchive license"
      :roles #{:owner :organization-owner}
      :body [command ArchivedCommand]
      :return SuccessResponse
      (ok (licenses/set-license-archived! command)))

    (PUT "/enabled" []
      :summary "Enable or disable license"
      :roles #{:owner :organization-owner}
      :body [command EnabledCommand]
      :return SuccessResponse
      (ok (licenses/set-license-enabled! command)))

    (POST "/add_attachment" []
      :summary "Add an attachment file that will be used in a license"
      :roles #{:owner :organization-owner}
      :multipart-params [file :- upload/TempFileUpload]
      :middleware [multipart/wrap-multipart-params]
      :return AttachmentMetadata
      (ok (licenses/create-license-attachment! file (getx-user-id))))

    (POST "/remove_attachment" []
      :summary "Remove an attachment that could have been used in a license."
      :roles #{:owner :organization-owner}
      :query-params [attachment-id :- (describe s/Int "attachment id")]
      :return SuccessResponse
      (ok {:success (some? (licenses/remove-license-attachment! attachment-id))}))

    (GET "/attachments/:attachment-id" []
      :summary "Get a license's attachment"
      :roles #{:owner :organization-owner}
      :path-params [attachment-id :- (describe s/Int "attachment id")]
      (if-let [attachment (licenses/get-license-attachment attachment-id)]
        (attachment/download attachment)
        (api-util/not-found-json-response)))))
