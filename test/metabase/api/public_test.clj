(ns metabase.api.public-test
  "Tests for `api/public/` (public links) endpoints."
  (:require [cheshire.core :as json]
            [clj-pdf.core :as pdf]
            [dk.ative.docjure.spreadsheet :as spreadsheet]
            [expectations :refer :all]
            [metabase
             [config :as config]
             [http-client :as http]
             [query-processor-test :as qp-test]
             [util :as u]]
            [metabase.api.public :as public-api]
            [metabase.models
             [card :refer [Card]]
             [dashboard :refer [Dashboard]]
             [dashboard-card :refer [DashboardCard]]
             [dashboard-card-series :refer [DashboardCardSeries]]
             [field-values :refer [FieldValues]]]
            [metabase.test
             [data :as data]
             [util :as tu]]
            [metabase.test.data.users :as test-users]
            [toucan.db :as db]
            [toucan.util.test :as tt])
  (:import java.io.ByteArrayInputStream
           [org.apache.pdfbox.pdmodel PDDocument]
           [org.apache.pdfbox.util PDFTextStripper]
           java.util.UUID))

;;; --------------------------------------------------- Helper Fns ---------------------------------------------------

(defn count-of-venues-card []
  {:dataset_query {:database (data/id)
                   :type     :query
                   :query    {:source_table (data/id :venues)
                              :aggregation  [:count]}}})

(defn- shared-obj []
  {:public_uuid       (str (UUID/randomUUID))
   :made_public_by_id (test-users/user->id :crowberto)})

(defmacro ^:private with-temp-public-card {:style/indent 1} [[binding & [card]] & body]
  `(let [card-settings# (merge (count-of-venues-card) (shared-obj) ~card)]
     (tt/with-temp Card [card# card-settings#]
       ;; add :public_uuid back in to the value that gets bound because it might not come back from post-select if
       ;; public sharing is disabled; but we still want to test it
       (let [~binding (assoc card# :public_uuid (:public_uuid card-settings#))]
         ~@body))))

(defmacro ^:private with-temp-public-dashboard {:style/indent 1} [[binding & [dashboard]] & body]
  `(let [dashboard-settings# (merge
                              {:parameters [{:name    "Venue ID"
                                             :slug    "venue_id"
                                             :type    "id"
                                             :target  [:dimension (data/id :venues :id)]
                                             :default nil}]}
                              (shared-obj)
                              ~dashboard)]
     (tt/with-temp Dashboard [dashboard# dashboard-settings#]
       (let [~binding (assoc dashboard# :public_uuid (:public_uuid dashboard-settings#))]
         ~@body))))

(defn- add-card-to-dashboard! {:style/indent 2} [card dashboard & {:as kvs}]
  (db/insert! DashboardCard (merge {:dashboard_id (u/get-id dashboard), :card_id (u/get-id card)}
                                   kvs)))

(defmacro ^:private with-temp-public-dashboard-and-card
  {:style/indent 1}
  [[dashboard-binding card-binding & [dashcard-binding]] & body]
  `(with-temp-public-dashboard [dash#]
     (with-temp-public-card [card#]
       (let [~dashboard-binding        dash#
             ~card-binding             card#
             ~(or dashcard-binding
                  (gensym "dashcard")) (add-card-to-dashboard! card# dash#)]
         ~@body))))



;;; ------------------------------------------- GET /api/public/card/:uuid -------------------------------------------

;; Check that we *cannot* fetch a PublicCard if the setting is disabled
(expect
  "An error occurred."
  (tu/with-temporary-setting-values [enable-public-sharing false]
    (with-temp-public-card [{uuid :public_uuid}]
      (http/client :get 400 (str "public/card/" uuid)))))

;; Check that we get a 400 if the PublicCard doesn't exist
(expect
  "An error occurred."
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (http/client :get 400 (str "public/card/" (UUID/randomUUID)))))

;; Check that we *cannot* fetch a PublicCard if the Card has been archived
(expect
  "An error occurred."
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (with-temp-public-card [{uuid :public_uuid} {:archived true}]
      (http/client :get 400 (str "public/card/" uuid)))))

;; Check that we can fetch a PublicCard
(expect
  #{:dataset_query :description :display :id :name :visualization_settings :param_values}
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (with-temp-public-card [{uuid :public_uuid}]
      (set (keys (http/client :get 200 (str "public/card/" uuid)))))))

;; make sure :param_values get returned as expected
(expect
  {(data/id :categories :name) {:values                75
                                :human_readable_values {}
                                :field_id              (data/id :categories :name)}}
  (tt/with-temp Card [card {:dataset_query
                            {:database (data/id)
                             :type     :native
                             :native   {:query         (str "SELECT COUNT(*) "
                                                            "FROM venues "
                                                            "LEFT JOIN categories ON venues.category_id = categories.id "
                                                            "WHERE {{category}}")
                                        :collection    "CATEGORIES"
                                        :template_tags {:category {:name         "category"
                                                                   :display_name "Category"
                                                                   :type         "dimension"
                                                                   :dimension    ["field-id" (data/id :categories :name)]
                                                                   :widget_type  "category"
                                                                   :required     true}}}}}]
    (-> (:param_values (#'public-api/public-card :id (u/get-id card)))
        (update-in [(data/id :categories :name) :values] count))))


;;; ------------------------- GET /api/public/card/:uuid/query (and JSON/CSV/XSLX/PDF versions) --------------------------

;; Check that we *cannot* execute a PublicCard if the setting is disabled
(expect
  "An error occurred."
  (tu/with-temporary-setting-values [enable-public-sharing false]
    (with-temp-public-card [{uuid :public_uuid}]
      (http/client :get 400 (str "public/card/" uuid "/query")))))


;; Check that we get a 400 if the PublicCard doesn't exist
(expect
  "An error occurred."
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (http/client :get 400 (str "public/card/" (UUID/randomUUID) "/query"))))

;; Check that we *cannot* execute a PublicCard if the Card has been archived
(expect
  "An error occurred."
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (with-temp-public-card [{uuid :public_uuid} {:archived true}]
      (http/client :get 400 (str "public/card/" uuid "/query")))))

;; Check that we can exec a PublicCard
(expect
  [[100]]
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (with-temp-public-card [{uuid :public_uuid}]
      (qp-test/rows (http/client :get 200 (str "public/card/" uuid "/query"))))))

;; Check that we can exec a PublicCard and get results as JSON
(expect
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (with-temp-public-card [{uuid :public_uuid}]
      (http/client :get 200 (str "public/card/" uuid "/query/json")))))

;; Check that we can exec a PublicCard and get results as CSV
(expect
  "count\n100\n"
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (with-temp-public-card [{uuid :public_uuid}]
      (http/client :get 200 (str "public/card/" uuid "/query/csv"), :format :csv))))

;; Check that we can exec a PublicCard and get results as XLSX
(expect
  [{:col "count"} {:col 100.0}]
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (with-temp-public-card [{uuid :public_uuid}]
      (->> (http/client :get 200 (str "public/card/" uuid "/query/xlsx") {:request-options {:as :byte-array}})
           ByteArrayInputStream.
           spreadsheet/load-workbook
           (spreadsheet/select-sheet "Query result")
           (spreadsheet/select-columns {:A :col})))))

;; Check that we can exec a PublicCard and get results as PDF
(expect
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (with-temp-public-card [{uuid :public_uuid}]
      (http/client :get 200 (str "public/card/" uuid "/query/pdf")))))

;; Check that we can get results of a PublicCard inside PDF
(expect
  not-empty
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (with-temp-public-card [{uuid :public_uuid}]
      (let [in (http/client :get 200 (str "public/card/" uuid "/query/pdf") {:request-options {:as :byte-array}})]
      (with-open [pd (PDDocument/load (ByteArrayInputStream. in))]
        (let [stripper (PDFTextStripper.)]
          (let [source-text (.getText stripper pd)]
            (println source-text)
            source-text)))))))

;; Check that we can exec a PublicCard with `?parameters`
(expect
  [{:name "Venue ID", :slug "venue_id", :type "id", :value 2}]
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (with-temp-public-card [{uuid :public_uuid}]
      (get-in (http/client :get 200 (str "public/card/" uuid "/query")
                           :parameters (json/encode [{:name "Venue ID", :slug "venue_id", :type "id", :value 2}]))
              [:json_query :parameters]))))

;; make sure CSV (etc.) downloads take editable params into account (#6407)

(defn- card-with-date-field-filter []
  (assoc (shared-obj)
    :dataset_query {:database (data/id)
                    :type     :native
                    :native   {:query         "SELECT COUNT(*) AS \"count\" FROM CHECKINS WHERE {{date}}"
                               :template_tags {:date {:name         "date"
                                                      :display_name "Date"
                                                      :type         "dimension"
                                                      :dimension    [:field-id (data/id :checkins :date)]
                                                      :widget_type  "date/quarter-year"}}}}))

(expect
  "count\n107\n"
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (tt/with-temp Card [{uuid :public_uuid} (card-with-date-field-filter)]
      (http/client :get 200 (str "public/card/" uuid "/query/csv")
                   :parameters (json/encode [{:type   :date/quarter-year
                                              :target [:dimension [:template-tag :date]]
                                              :value  "Q1-2014"}])))))

;; make sure it also works with the forwarded URL
(expect
  "count\n107\n"
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (tt/with-temp Card [{uuid :public_uuid} (card-with-date-field-filter)]
      ;; make sure the URL doesn't include /api/ at the beginning like it normally would
      (binding [http/*url-prefix* (str "http://localhost:" (config/config-str :mb-jetty-port) "/")]
        (http/client :get 200 (str "public/question/" uuid ".csv")
                     :parameters (json/encode [{:type   :date/quarter-year
                                                :target [:dimension [:template-tag :date]]
                                                :value  "Q1-2014"}]))))))


;;; ---------------------------------------- GET /api/public/dashboard/:uuid -----------------------------------------

;; Check that we *cannot* fetch PublicDashboard if setting is disabled
(expect
  "An error occurred."
  (tu/with-temporary-setting-values [enable-public-sharing false]
    (with-temp-public-dashboard [{uuid :public_uuid}]
      (http/client :get 400 (str "public/dashboard/" uuid)))))

;; Check that we get a 400 if the PublicDashboard doesn't exis
(expect
  "An error occurred."
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (http/client :get 400 (str "public/dashboard/" (UUID/randomUUID)))))

(defn- fetch-public-dashboard [{uuid :public_uuid}]
  (-> (http/client :get 200 (str "public/dashboard/" uuid))
      (select-keys [:name :ordered_cards])
      (update :name boolean)
      (update :ordered_cards count)))

;; Check that we can fetch a PublicDashboard
(expect
  {:name true, :ordered_cards 1}
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (with-temp-public-dashboard-and-card [dash card]
      (fetch-public-dashboard dash))))

;; Check that we don't see Cards that have been archived
(expect
  {:name true, :ordered_cards 0}
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (with-temp-public-dashboard-and-card [dash card]
      (db/update! Card (u/get-id card), :archived true)
      (fetch-public-dashboard dash))))


;;; --------------------------------- GET /api/public/dashboard/:uuid/card/:card-id ----------------------------------

(defn- dashcard-url [dash card]
  (str "public/dashboard/" (:public_uuid dash) "/card/" (u/get-id card)))


;; Check that we *cannot* exec PublicCard via PublicDashboard if setting is disabled
(expect
  "An error occurred."
  (tu/with-temporary-setting-values [enable-public-sharing false]
    (with-temp-public-dashboard-and-card [dash card]
      (http/client :get 400 (dashcard-url dash card)))))

;; Check that we get a 400 if PublicDashboard doesn't exist
(expect
  "An error occurred."
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (with-temp-public-dashboard-and-card [_ card]
      (http/client :get 400 (dashcard-url {:public_uuid (UUID/randomUUID)} card)))))


;; Check that we get a 400 if PublicCard doesn't exist
(expect
  "An error occurred."
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (with-temp-public-dashboard-and-card [dash _]
      (http/client :get 400 (dashcard-url dash Integer/MAX_VALUE)))))

;; Check that we get a 400 if the Card does exist but it's not part of this Dashboard
(expect
  "An error occurred."
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (with-temp-public-dashboard-and-card [dash _]
      (tt/with-temp Card [card]
        (http/client :get 400 (dashcard-url dash card))))))

;; Check that we *cannot* execute a PublicCard via a PublicDashboard if the Card has been archived
(expect
  "An error occurred."
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (with-temp-public-dashboard-and-card [dash card]
      (db/update! Card (u/get-id card), :archived true)
      (http/client :get 400 (dashcard-url dash card)))))

;; Check that we can exec a PublicCard via a PublicDashboard
(expect
  [[100]]
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (with-temp-public-dashboard-and-card [dash card]
      (qp-test/rows (http/client :get 200 (dashcard-url dash card))))))

;; Check that we can exec a PublicCard via a PublicDashboard with `?parameters`
(expect
  [{:name    "Venue ID"
    :slug    "venue_id"
    :target  ["dimension" (data/id :venues :id)]
    :value   [10]
    :default nil
    :type    "id"}]
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (with-temp-public-dashboard-and-card [dash card]
      (get-in (http/client :get 200 (dashcard-url dash card)
                           :parameters (json/encode [{:name   "Venue ID"
                                                      :slug   :venue_id
                                                      :target [:dimension (data/id :venues :id)]
                                                      :value  [10]}]))
              [:json_query :parameters]))))

;; Make sure params are validated: this should pass because venue_id *is* one of the Dashboard's :parameters
(expect
 [[1]]
 (tu/with-temporary-setting-values [enable-public-sharing true]
   (with-temp-public-dashboard-and-card [dash card]
     (-> (http/client :get 200 (dashcard-url dash card)
                      :parameters (json/encode [{:name   "Venue ID"
                                                 :slug   :venue_id
                                                 :target [:dimension (data/id :venues :id)]
                                                 :value  [10]}]))
         qp-test/rows))))

;; Make sure params are validated: this should fail because venue_name is *not* one of the Dashboard's :parameters
(expect
 "An error occurred."
 (tu/with-temporary-setting-values [enable-public-sharing true]
   (with-temp-public-dashboard-and-card [dash card]
     (http/client :get 400 (dashcard-url dash card)
                  :parameters (json/encode [{:name   "Venue Name"
                                             :slug   :venue_name
                                             :target [:dimension (data/id :venues :name)]
                                             :value  ["PizzaHacker"]}])))))

;; Check that an additional Card series works as well
(expect
  [[100]]
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (with-temp-public-dashboard-and-card [dash card]
      (with-temp-public-card [card-2]
        (tt/with-temp DashboardCardSeries [_ {:dashboardcard_id (db/select-one-id DashboardCard
                                                                  :card_id      (u/get-id card)
                                                                  :dashboard_id (u/get-id dash))
                                              :card_id          (u/get-id card-2)}]
          (qp-test/rows (http/client :get 200 (dashcard-url dash card-2))))))))

;; Make sure that parameters actually work correctly (#7212)
(expect
  [[50]]
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (tt/with-temp Card [card {:dataset_query {:database (data/id)
                                              :type     :native
                                              :native   {:query         "SELECT {{num}} AS num"
                                                         :template_tags {:num {:name         "num"
                                                                               :display_name "Num"
                                                                               :type         "number"
                                                                               :required     true
                                                                               :default      "1"}}}}}]
      (with-temp-public-dashboard [dash {:parameters [{:name "Num"
                                                       :slug "num"
                                                       :id   "537e37b4"
                                                       :type "category"}]}]
        (add-card-to-dashboard! card dash
          :parameter_mappings [{:card_id      (u/get-id card)
                                :target       [:variable
                                               [:template-tag :num]]
                                :parameter_id "537e37b4"}])
        (-> ((test-users/user->client :crowberto)
             :get (str (dashcard-url dash card)
                       "?parameters="
                       (json/generate-string
                        [{:type   :category
                          :target [:variable [:template-tag :num]]
                          :value  "50"}])))
            :data
            :rows)))))

;; ...with MBQL Cards as well...
(expect
  [[1]]
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (tt/with-temp Card [card {:dataset_query {:database (data/id)
                                              :type     :query
                                              :query    {:source-table (data/id :venues)
                                                         :aggregation  [:count]}}}]
      (with-temp-public-dashboard [dash {:parameters [{:name "Venue ID"
                                                       :slug "venue_id"
                                                       :id   "22486e00"
                                                       :type "id"}]}]
        (add-card-to-dashboard! card dash
          :parameter_mappings [{:parameter_id "22486e00"
                                :card_id      (u/get-id card)
                                :target       [:dimension
                                               [:field-id
                                                (data/id :venues :id)]]}])
        (-> ((test-users/user->client :crowberto)
             :get (str (dashcard-url dash card)
                       "?parameters="
                       (json/generate-string
                        [{:type   :id
                          :target [:dimension [:field-id (data/id :venues :id)]]
                          :value  "50"}])))
            :data
            :rows)))))

;; ...and also for DateTime params
(expect
  [[733]]
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (tt/with-temp Card [card {:dataset_query {:database (data/id)
                                              :type     :query
                                              :query    {:source-table (data/id :checkins)
                                                         :aggregation  [:count]}}}]
      (with-temp-public-dashboard [dash {:parameters [{:name "Date Filter"
                                                       :slug "date_filter"
                                                       :id   "18a036ec"
                                                       :type "date/all-options"}]}]
        (add-card-to-dashboard! card dash
          :parameter_mappings [{:parameter_id "18a036ec"
                                :card_id      (u/get-id card)
                                :target       [:dimension
                                               [:field-id
                                                (data/id :checkins :date)]]}])
        (-> ((test-users/user->client :crowberto)
             :get (str (dashcard-url dash card)
                       "?parameters="
                       (json/generate-string
                        [{:type   "date/all-options"
                          :target [:dimension [:field-id (data/id :checkins :date)]]
                          :value  "~2015-01-01"}])))
            :data
            :rows)))))

;; make sure DimensionValue params also work if they have a default value, even if some is passed in for some reason
;; as part of the query (#7253)
;; If passed in as part of the query however make sure it doesn't override what's actually in the DB
(expect
 [["Wow"]]
 (tu/with-temporary-setting-values [enable-public-sharing true]
   (tt/with-temp Card [card {:dataset_query {:database (data/id)
                                             :type     :native
                                             :native   {:query         "SELECT {{msg}} AS message"
                                                        :template_tags {:msg {:id           "181da7c5"
                                                                              :name         "msg"
                                                                              :display_name "Message"
                                                                              :type         "text"
                                                                              :required     true
                                                                              :default      "Wow"}}}}}]
     (with-temp-public-dashboard [dash {:parameters [{:name "Message"
                                                      :slug "msg"
                                                      :id   "181da7c5"
                                                      :type "category"}]}]
       (add-card-to-dashboard! card dash
         :parameter_mappings [{:card_id      (u/get-id card)
                               :target       [:variable [:template-tag :msg]]
                               :parameter_id "181da7c5"}])
       (-> ((test-users/user->client :crowberto)
            :get (str (dashcard-url dash card)
                      "?parameters="
                      (json/generate-string
                       [{:type    :category
                         :target  [:variable [:template-tag :msg]]
                         :value   nil
                         :default "Hello"}])))
           :data
           :rows)))))


;;; --------------------------- Check that parameter information comes back with Dashboard ---------------------------

;; double-check that the Field has FieldValues
(expect
  [1 2 3 4]
  (db/select-one-field :values FieldValues :field_id (data/id :venues :price)))

(defn- price-param-values []
  {(keyword (str (data/id :venues :price))) {:values                [1 2 3 4]
                                             :human_readable_values {}
                                             :field_id              (data/id :venues :price)}})

(defn- add-price-param-to-dashboard! [dashboard]
  (db/update! Dashboard (u/get-id dashboard) :parameters [{:name "Price", :type "category", :slug "price"}]))

(defn- add-dimension-param-mapping-to-dashcard! [dashcard card dimension]
  (db/update! DashboardCard (u/get-id dashcard) :parameter_mappings [{:card_id (u/get-id card)
                                                                      :target  ["dimension" dimension]}]))

(defn- GET-param-values [dashboard]
  (tu/with-temporary-setting-values [enable-public-sharing true]
    (:param_values (http/client :get 200 (str "public/dashboard/" (:public_uuid dashboard))))))

;; Check that param info comes back for SQL Cards
(expect
  (price-param-values)
  (with-temp-public-dashboard-and-card [dash card dashcard]
    (db/update! Card (u/get-id card)
      :dataset_query {:database (data/id)
                      :type     :native
                      :native   {:template_tags {:price {:name         "price"
                                                         :display_name "Price"
                                                         :type         "dimension"
                                                         :dimension    ["field-id" (data/id :venues :price)]}}}})
    (add-price-param-to-dashboard! dash)
    (add-dimension-param-mapping-to-dashcard! dashcard card ["template-tag" "price"])
    (GET-param-values dash)))

;; Check that param info comes back for MBQL Cards (field-id)
(expect
  (price-param-values)
  (with-temp-public-dashboard-and-card [dash card dashcard]
    (add-price-param-to-dashboard! dash)
    (add-dimension-param-mapping-to-dashcard! dashcard card ["field-id" (data/id :venues :price)])
    (GET-param-values dash)))

;; Check that param info comes back for MBQL Cards (fk->)
(expect
  (price-param-values)
  (with-temp-public-dashboard-and-card [dash card dashcard]
    (add-price-param-to-dashboard! dash)
    (add-dimension-param-mapping-to-dashcard! dashcard card ["fk->" (data/id :checkins :venue_id) (data/id :venues :price)])
    (GET-param-values dash)))
