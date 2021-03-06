(ns rems.api.public
  (:require [compojure.api.sweet :refer :all]
            [rems.api.util] ; required for route :roles
            [rems.config :refer [env]]
            [rems.locales :as locales]
            [ring.util.http-response :refer :all]
            [schema.core :as s]))

(s/defschema GetTranslationsResponse
  s/Any)

(s/defschema GetThemeResponse
  s/Any)

(s/defschema ExtraPage
  {:id s/Str
   (s/optional-key :url) s/Str
   :translations {s/Keyword {:title s/Str
                             (s/optional-key :filename) s/Str}}})

(s/defschema GetConfigResponse
  {:authentication s/Keyword
   :alternative-login-url (s/maybe s/Str)
   :application-id-column (s/enum :id :external-id)
   :extra-pages [ExtraPage]
   :languages [s/Keyword]
   :default-language s/Keyword
   :dev s/Bool
   :organizations [s/Str]})

(def translations-api
  (context "/translations" []
    :tags ["translations"]

    (GET "/" []
      :summary "Get translations"
      :return GetTranslationsResponse
      (ok locales/translations))))

(def theme-api
  (context "/theme" []
    :tags ["theme"]

    (GET "/" []
      :summary "Get current layout theme"
      :return GetThemeResponse
      (ok (:theme env)))))

(def config-api
  (context "/config" []
    :tags ["config"]

    (GET "/" []
      :summary "Get configuration that is relevant to UI"
      :return GetConfigResponse
      (ok (select-keys env [:alternative-login-url
                            :application-id-column
                            :authentication
                            :default-language
                            :dev
                            :extra-pages
                            :languages
                            :organizations])))

    (GET "/full" []
      :summary "Get (almost) full configuration"
      :roles #{:owner}
      :return s/Any
      (ok (assoc env
                 :authentication "HIDDEN"
                 :database-url "HIDDEN"
                 :test-database-url "HIDDEN"
                 :oidc-client-secret "HIDDEN")))))
