(ns cetcd.core
  (:require [cheshire.core :as json]
            [cetcd.util :refer (apply-map)]
            [org.httpkit.client :as http]
            [slingshot.slingshot :refer [throw+ try+]]))

(def ^{:dynamic true} *etcd-config* {:protocol "http" :host "127.0.0.1" :port 4001})

(defn set-connection!
  "Blindly copied the approach from congomongo, but without most of the protections"
  [{:keys [protocol host port]
    :or {protocol "http" host "127.0.0.1" port 4001}}]
  (let [config {:protocol protocol :host host :port port}]
    (alter-var-root #'*etcd-config* (constantly config))
    (when (thread-bound? #'*etcd-config*)
      (set! *etcd-config* config))))

(defn base-url
  "Constructs url used for all api calls"
  []
  (str (java.net.URL. (:protocol *etcd-config*)
                      (:host *etcd-config*)
                      (:port *etcd-config*)
                      "/v2")))

(defn wrap-callback [callback]
  (fn [resp]
    (-> resp
        :body
        (cheshire.core/decode true)
        callback)))

(defn api-req [method path & {:keys [callback] :as opts}]
  (let [resp (http/request (merge {:method method
                                   :url (format "%s/%s" (base-url) path)}
                                  opts)
                           (when callback
                             (wrap-callback callback)))]
    (if callback
      resp
      (-> @resp
          :body
          (cheshire.core/decode true)))))

(defn set-key!
  "Sets key to value, optionally takes ttl in seconds as keyword argument"
  [key value & {:keys [ttl callback dir cas] :as opts
                :or {dir false}}]
  (api-req :put (format "keys/%s" key)
           :form-params (merge {:value value}
                               cas
                               (when ttl
                                 {:ttl ttl
                                  :dir dir}))
           :callback callback))

(defn get-key [key & {:keys [recursive wait waitIndex callback]
                      :or {recursive false wait false}}]
  (api-req :get (format "keys/%s" key)
           :query-params (merge {:recursive recursive
                                 :wait wait}
                                (when waitIndex
                                  {:waitIndex waitIndex}))
           :callback callback))

(defn delete-key! [key & {:keys [callback]}]
  (api-req :delete (format "keys/%s" key) :callback callback))

(defn compare-and-swap! [key value conditions & {:keys [ttl callback dir] :as opts}]
  (apply-map set-key! key value :cas conditions opts))

(defn watch-key [key & {:keys [waitIndex recursive callback] :as opts}]
  (apply-map get-key key :wait true opts))

(defn connected-machines []
  (api-req :get "keys/_etcd/machines"))