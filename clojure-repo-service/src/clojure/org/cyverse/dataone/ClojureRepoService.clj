(ns org.cyverse.dataone.ClojureRepoService
  (:refer-clojure)
  (:import [org.dataone.service.types.v1 Identifier]
           [org.irods.jargon.core.exception FileNotFoundException]
           [org.irods.jargon.core.pub DataAOHelper]
           [org.irods.jargon.core.query CollectionAndDataObjectListingEntry$ObjectType GenQueryOrderByField$OrderByType
            IRODSGenQueryBuilder QueryConditionOperators RodsGenQueryEnum]
           [org.irods.jargon.dataone.model DataOneObjectListResponse FileDataOneObject]
           [java.util Date])
  (:require [clojure.string :as string]
            [clojure.tools.logging :as log])
  (:gen-class :extends org.irods.jargon.dataone.reposervice.AbstractDataOneRepoServiceAO
              :init init
              :constructors {[org.irods.jargon.core.connection.IRODSAccount
                              org.irods.jargon.dataone.plugin.PublicationContext]
                             [org.irods.jargon.core.connection.IRODSAccount
                              org.irods.jargon.dataone.plugin.PublicationContext]}))

;; Default configuration settings.

(def ^:private default-uuid-attr "ipc_UUID")
(def ^:private default-root "/iplant/home/shared/commons_repo/curated")
(def ^:private default-page-length "50")
(def ^:private default-format "application/octet-stream")
(def ^:private default-offset 0)
(def ^:private default-limit 500)

;; Functions to retrieve configuration settings.

(defn- get-additional-properties [this]
  (.. this getPublicationContext getAdditionalProperties))

(defn- get-property [this name default]
  (.getProperty (get-additional-properties this) name default))

(defn- get-uuid-attr [this]
  (get-property this "cyverse.avu.uuid-attr" default-uuid-attr))

(defn- get-root [this]
  (get-property this "cyverse.dataone.root" default-root))

(defn- get-query-page-length [this]
  (-> (get-property this "irods.dataone.query-page-length" default-page-length)
      Integer/parseInt))

;; General convenience functions.

(defn- get-time [d]
  (when d
    (.getTime d)))

;; General jargon convenience functions.

(defn- get-collection-ao [this]
  (.getCollectionAO (.. this getPublicationContext getIrodsAccessObjectFactory)
                    (.getIrodsAccount this)))

(defn- get-file-system-ao [this]
  (.getIRODSFileSystemAO (.. this getPublicationContext getIrodsAccessObjectFactory)
                         (.getIrodsAccount this)))

(defn- get-gen-query-executor [this]
  (.getIRODSGenQueryExecutor (.. this getPublicationContext getIrodsAccessObjectFactory)
                             (.getIrodsAccount this)))

(defn- lazy-rs
  "Converts a query result set into a lazy sequence of query result sets."
  [executor rs]
  (if (.isHasMoreRecords rs)
    (lazy-seq (cons rs (lazy-rs executor (.getMoreResults executor rs))))
    (do (.closeResults executor rs) [rs])))

(defn- lazy-gen-query
  "Performs a general query and returns a lazy sequence of results."
  [this offset query]
  (let [executor (get-gen-query-executor this)]
    (mapcat (fn [rs] (.getResults rs))
            (lazy-rs executor (.executeIRODSQuery executor query (or offset default-offset))))))

(defn- gen-query
  "Performs a general query and returns the result set. This result set will be closed automatically."
  [this query offset]
  (.executeIRODSQueryAndCloseResult (get-gen-query-executor this) query offset))

(defn- add-modify-time-condition [builder operator date]
  (.addConditionAsGenQueryField builder RodsGenQueryEnum/COL_D_MODIFY_TIME operator (quot (.getTime date) 1000)))

(defn- add-coll-name-condition [builder operator value]
  (.addConditionAsGenQueryField builder RodsGenQueryEnum/COL_COLL_NAME operator value))

(defn- add-attribute-name-condition [builder operator value]
  (.addConditionAsGenQueryField builder RodsGenQueryEnum/COL_META_DATA_ATTR_NAME operator value))

;; Functions to retrieve the list of exposed identifiers.

(defn- build-id-query [this]
  (-> (IRODSGenQueryBuilder. true nil)
      (.addSelectAsGenQueryValue RodsGenQueryEnum/COL_META_DATA_ATTR_VALUE)
      (.addOrderByGenQueryField RodsGenQueryEnum/COL_META_DATA_ATTR_VALUE GenQueryOrderByField$OrderByType/ASC)
      (add-coll-name-condition QueryConditionOperators/LIKE (str (get-root this) "%"))
      (add-attribute-name-condition QueryConditionOperators/EQUAL (get-uuid-attr this))
      (.exportIRODSQueryFromBuilder (get-query-page-length this))))

(defn- list-exposed-identifiers [this]
  (mapv (fn [row] (doto (Identifier.) (.setValue (.getColumn row 0))))
        (lazy-gen-query this (build-id-query this))))

;; Functions to retrieve the list of exposed data objects.

(defn- build-data-object-listing-query [this from-date to-date limit]
  (-> (IRODSGenQueryBuilder. true false nil)
      (.addSelectAsGenQueryValue RodsGenQueryEnum/COL_META_DATA_ATTR_NAME)
      (.addSelectAsGenQueryValue RodsGenQueryEnum/COL_META_DATA_ATTR_VALUE)
      (.addSelectAsGenQueryValue RodsGenQueryEnum/COL_META_DATA_ATTR_UNITS)
      (DataAOHelper/addDataObjectSelectsToBuilder)
      (add-modify-time-condition QueryConditionOperators/NUMERIC_GREATER_THAN_OR_EQUAL_TO from-date)
      (add-modify-time-condition QueryConditionOperators/NUMERIC_LESS_THAN_OR_EQUAL_TO to-date)
      (add-coll-name-condition QueryConditionOperators/LIKE (str (get-root this) "%"))
      (add-attribute-name-condition QueryConditionOperators/EQUAL (get-uuid-attr this))
      (.addOrderByGenQueryField RodsGenQueryEnum/COL_D_DATA_PATH GenQueryOrderByField$OrderByType/ASC)
      (.exportIRODSQueryFromBuilder limit)))

(defn- file-data-one-object-from-row [this row]
  (FileDataOneObject.
   (.getPublicationContext this)
   (.getIrodsAccount this)
   (.getColumn row RodsGenQueryEnum/COL_META_DATA_ATTR_VALUE)
   (DataAOHelper/buildDomainFromResultSetRow row)))

(defn- list-exposed-data-objects [this from-date to-date format-id start-index count]
  (let [rs       (gen-query this (build-data-object-listing-query this from-date to-date format-id count))
        elements (mapv (partial file-data-one-object-from-row this) (.getResults rs))]
    (DataOneObjectListResponse. elements (.getTotalRecords rs) start-index)))

;; Last modification date functions.

(defn- get-last-modified-date [this path]
  (try
    (let [stat (.getObjStat (get-file-system-ao this) path)]
      (when (= (.getObjectType stat)
               (CollectionAndDataObjectListingEntry$ObjectType/DATA_OBJECT))
        (.getModifiedAt stat)))
    (catch FileNotFoundException e nil)))

;; Class method implementations.

(defn -init [irods-account publication-context]
  [[irods-account publication-context] {}])

(defn -getListOfDataoneExposedIdentifiers [this]
  (list-exposed-identifiers this))

(defn -getExposedObjects [this from-date to-date format-id _ offset limit]
  (let [offset (or offset default-offset)
        limit       (or limit default-limit)]
    (if (or (nil? (some-> format-id .getValue)) (= (.getValue format-id) default-format))
      (try
        (log/spy :warn (list-exposed-data-objects this from-date to-date offset limit))
        (catch Throwable t
          (log/error t)
          (throw t)))
      (DataOneObjectListResponse. [] offset limit))))

(defn -getLastModifiedDate [this path]
  (get-last-modified-date this path))

(defn -getFormat [_ _]
  default-format)
