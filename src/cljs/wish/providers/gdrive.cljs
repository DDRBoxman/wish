(ns ^{:author "Daniel Leong"
      :doc "Google-drive powered Provider"}
  wish.providers.gdrive
  (:require-macros [cljs.core.async :refer [go]]
                   [wish.util.async :refer [call-with-cb->chan]]
                   [wish.util.log :as log :refer [log]])
  (:require [clojure.core.async :refer [chan put! to-chan <! >!]]
            [clojure.string :as str]
            [wish.providers.core :refer [IProvider load-raw]]
            [wish.sheets.util :refer [make-id]]
            [wish.util :refer [>evt]]
            [wish.util.async :refer [promise->chan]]))


;;
;; Constants
;;

;; Client ID and API key from the Developer Console
(def ^:private client-id "661182319990-3aa8akj9fh8eva9lf7bt02621q2i18s6.apps.googleusercontent.com")

;; Array of API discovery doc URLs for APIs used by the quickstart
(def ^:private discovery-docs #js ["https://www.googleapis.com/discovery/v1/apis/drive/v3/rest"])

;; Authorization scopes required by the API; multiple scopes can be
;; included, separated by spaces.
(def ^:private scopes (str/join
                        " "
                        ["https://www.googleapis.com/auth/drive.appfolder"
                         "https://www.googleapis.com/auth/drive.appdata"
                         "https://www.googleapis.com/auth/drive.file"]))

(def ^:private sheet-desc "WISH Character Sheet")
(def ^:private sheet-mime "application/edn")
(def ^:private sheet-props {:wish-type "wish-sheet"})
(def ^:private source-desc "WISH Data Source")
(def ^:private source-mime "application/edn")
(def ^:private source-props {:wish-type "data-source"})

;;
;; Internal util
;;

(defn- ->id
  [gapi-id]
  (keyword "gdrive" gapi-id))

(defn- ->clj [v]
  (js->clj v :keywordize-keys true))

;;
;; gapi wrappers
;;

(defn- query-files
  [q & {page-size :max
        :keys [on-error on-success]
        :or {page-size 50
             on-error (fn [e]
                        (log/err "ERROR listing files" e))}}]

  (-> js/gapi.client.drive.files
      (.list #js {:q q
                  :pageSize page-size
                  :spaces "drive,appDataFolder"
                  :fields "nextPageToken, files(id, name)"})
      (.then (fn [response]
               (log "FILES LIST:" response)
               (on-success
                 (->> response
                      ->clj
                      :result
                      :files
                      (map
                        (fn [raw-file]
                          [(make-id :gdrive (:id raw-file))
                           (select-keys raw-file
                                        [:name])])))))
             on-error)))

(defn- update-meta
  "Update the metadata on a file. Useful for eg:
   (update-meta
     :new-source
     {:appProperties {:wish-type \"data-source\"}})"
  [file-id metadata]
  (-> js/gapi.client.drive.files
      (.update (clj->js
                 (assoc metadata
                        :fileId file-id)))
      promise->chan))

;;
;; State management and API interactions
;;

; gapi availability channel. Once js/gapi is
; available, this atom is reset! to nil. Due to
; how the (go) macro works, we can't have a nice
; convenience function to use this, so callers will
; have to look like:
;
;   (when-let [ch @gapi-available?]
;     (<! ch))
;
(defonce ^:private gapi-available? (atom (chan)))

(defn- set-gapi-available! []
  (when-let [gapi-available-ch @gapi-available?]
    (reset! gapi-available? nil)
    (put! gapi-available-ch true)))

(defn- auth-instance
  "Convenience to get the gapi auth instance:
   gapi.auth2.getAuthInstance().
   @return {gapi.AuthInstance}"
  []
  (js/gapi.auth2.getAuthInstance))

(defn- access-token
  "When logged in, get the current user's access token"
  []
  (-> (auth-instance)
      (.-currentUser)
      (.get)
      (.getAuthResponse)
      (.-access_token)))

(defn- update-signin-status!
  [signed-in?]
  (log/info "signed-in? <-" signed-in?)
  (>evt [:put-provider-state! :gdrive (if signed-in?
                                        :signed-in
                                        :signed-out)])
  (when signed-in?
    (>evt [:mark-provider-listing! :gdrive true])
    (query-files
      "appProperties has { key='wish-type' and value='wish-sheet' }"
      :on-success (fn on-files-list [files]
                    (log/info "Found: " files)
                    (>evt [:add-sheets files])
                    (>evt [:mark-provider-listing! :gdrive false])))))

(defn- on-client-init
  []
  (log "gapi client init!")
  (set-gapi-available!)

  ; listen for updates
  (-> (auth-instance)
      (.-isSignedIn)
      (.listen update-signin-status!))

  ; set current status immediately
  (update-signin-status!
    (-> (auth-instance)
        (.-isSignedIn)
        (.get))))

(defn init-client!
  []
  (-> (js/gapi.client.init
        #js {:discoveryDocs discovery-docs
             :clientId client-id
             :scope scopes})
      (.then on-client-init)))

;;
;; NOTE: Exposed to index.html
(defn ^:export handle-client-load
  []
  (log "load")
  (js/gapi.load "client:auth2", init-client!))

;;
;; Public API
;;

(defn signin!
  []
  (-> (auth-instance)
      (.signIn)))

(defn signout!
  []
  (-> (auth-instance)
      (.signOut)))

(defn upload-data
  "The GAPI client doesn't provide proper support for
   file uploads out-of-the-box, so let's roll our own.

   Returns a channel that emits [err, res], where err
   is non-nil on error, and res is non-nil on success."
  [upload-type metadata content]
  {:pre [(contains? #{:create :update} upload-type)
         (string? (:mimeType metadata))
         (not (nil? content))]}
  (let [base (case upload-type
               :create {:path "/upload/drive/v3/files"
                        :method "POST"}
               :update {:path (str "/upload/drive/v3/files/"
                                   (:fileId metadata))
                        :method "PATCH"})
        boundary "-------314159265358979323846"
        delimiter (str "\r\n--" boundary "\r\n")
        close-delim (str "\r\n--" boundary "--")
        body (str delimiter
                  "Content-Type: application/json\r\n\r\n"
                  (js/JSON.stringify (clj->js metadata))
                  delimiter
                  "Content-Type: " (:mimeType metadata) "\r\n\r\n"
                  content
                  close-delim)
        request (assoc base
                       :params {:uploadType "multipart"}
                       :headers
                       {:Content-Type
                        (str "multipart/related; boundary=\"" boundary "\"")}
                       :body body)]
    (call-with-cb->chan
      (.execute
        (js/gapi.client.request
          (clj->js request))))))

(defn- refresh-auth []
  (call-with-cb->chan
    (js/gapi.auth.authorize
      #js {:client_id client-id
           :scope scopes
           :immediate true})))

(defn upload-data-with-retry
  [upload-type metadata content]
  (go (let [[error resp] (<! (upload-data
                               upload-type metadata content))]
        (cond
          (and error
               (= 401 (.-code error)))
          ; refresh creds and retry
          (let [_ (log/info "Refreshing auth before retrying upload...")
                [refresh-err refresh-resp] (<! (refresh-auth))]
            (if refresh-err
              (do
                (log/warn "Auth refresh failed:" refresh-resp)
                [refresh-err nil])

              (let [_ (log/info "Auth refreshed! Retrying upload...")
                    [retry-err retry-resp] (<! (upload-data
                                                 upload-type metadata content))]
                (if retry-err
                  (do
                    (log/err "Even after auth refresh, upload failed: " resp)
                    [retry-err nil])

                  ; upload retry succeeded!
                  [nil retry-resp]))))

          ; unexpected error:
          error (do
                  (log/err "upload-data ERROR:" error)
                  [error nil])

          ; no problem; pass it along
          :else [nil resp]))))

; ======= file picker ======================================

(defonce ^:private picker-api-loaded (atom false))

(defn- do-pick-file
  [{:keys [mimeType description props]}]
  ; NOTE: I don't love using camel case in my clojure code,
  ; but since we're using it everywhere else for easier compat
  ; with google docs, let's just use it here for consistency.

  (let [ch (chan)]
    (-> (js/google.picker.PickerBuilder.)
        (.addView (doto (js/google.picker.View.
                          js/google.picker.ViewId.DOCS)
                    (.setMimeTypes "application/edn,application/json,text/plain")))
        (.addView (js/google.picker.DocsUploadView.))
        (.setAppId client-id)
        (.setOAuthToken (access-token))
        (.setCallback
          (fn [result]
            (let [result (->clj result)
                  uploaded? (= "upload"
                               (-> result :viewToken first))]
              (log "Picked: (wasUpload=" uploaded? ") " result)
              (case (:action result)
                "cancel" (put! ch [nil nil])
                "picked" (let [file (-> result
                                        :docs
                                        first
                                        (select-keys [:id :name]))
                               file-id (:id file)
                               wish-file (update file :id
                                                 (partial make-id :gdrive))]
                           (when uploaded?
                             ; update mime type and annotate with :wish-type
                             (update-meta
                               file-id
                               {:appProperties props
                                :description description
                                :mimeType mimeType}))
                           (put! ch [nil wish-file]))
                (log "Other pick action")))))
        (.build)
        (.setVisible true))
    ; return the channel
    ch))

(defn pick-file [opts]
  (if @picker-api-loaded
    ; loaded! do it now
    (do-pick-file opts)

    ; load first
    (let [ch (chan)]
      (log "Loading picker API")
      (js/gapi.load "picker"
                    (fn []
                      (log "Loaded picker! Waiting for result")
                      (reset! picker-api-loaded true)
                      (go (>!
                            ch
                            (<! (do-pick-file opts))))))
      ch)))

; ======= Provider def =====================================

(deftype GDriveProvider []
  IProvider
  (id [this] :gdrive)

  (create-sheet [this file-name data]
    (log/info "Create sheet " file-name)
    (go (let [[err resp] (<! (upload-data-with-retry
                               :create
                               {:name file-name
                                :description sheet-desc
                                :mimeType sheet-mime
                                :appProperties sheet-props}
                               (str data)))]
          (if err
            [err nil]

            (let [pro-sheet-id (:id resp)]
              (log/info "CREATED" resp)
              [nil (make-id :gdrive pro-sheet-id)])))))

  #_(delete-sheet [this info]
      (log/info "Delete " (:gapi-id info))
      (-> js/gapi.client.drive.files
          (.delete #js {:fileId (:gapi-id info)})
          (.then (fn [resp]
                   (log/info "Deleted!" (:gapi-id info)))
                 (fn [e]
                   (log/warn "Failed to delete " (:gapi-id info))))))

  (init! [this]) ; nop

  (load-raw
    [this id]
    (go (let [_ (when-let [ch @gapi-available?]
                  (<! ch))

              [err resp] (<! (promise->chan
                               (-> js/gapi.client.drive.files
                                   (.get #js {:fileId id
                                              :alt "media"}))))]
          (if err
            (do
              (log/err "ERROR loading " id err)
              [err nil])

            ; success:
            [nil (.-body resp)]))))

  (query-data-sources [this]
    ; TODO indicate query state?
    (query-files
      "appProperties has { key='wish-type' and value='data-source' }"
      :on-success (fn [files]
                    (log "Found data sources: " files)
                    (>evt [:add-data-sources
                           (map (fn [[id file]]
                                  (assoc file :id id))
                                files)]))))

  (register-data-source [this]
    ; TODO sanity checks galore
    (pick-file {:mimeType source-mime
                :description source-desc
                :props source-props}))

  (save-sheet [this file-id data]
    (log/info "Save " file-id data)
    (upload-data-with-retry
      :update
      {:fileId file-id
       :mimeType sheet-mime
       :description sheet-desc
       :name (:name data)}
      (str data))))

(defn create-provider []
  (->GDriveProvider))
