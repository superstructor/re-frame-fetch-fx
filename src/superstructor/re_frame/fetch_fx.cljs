(ns superstructor.re-frame.fetch-fx
  (:require
    [clojure.string :as string]
    [goog.object :as obj]
    [re-frame.core :refer [reg-fx dispatch]]))

(def default-json-reader
  {:reader-kw :json
   :reader-fn #(js->clj % :keywordize-keys true)})

(def default-text-reader
  {:reader-kw :text
   :reader-fn identity})

;; Utilities
;; =============================================================================

(defn ->seq
  "Returns x if x satisfies ISequential, otherwise vector of x."
  [x]
  (if (sequential? x) x [x]))

(defn ->str
  "Returns the name String of x if x is a symbol or keyword, otherwise
   x.toString()."
  [x]
  (if (or (symbol? x)
          (keyword? x))
    (name x)
    (str x)))

(defn encode-kv [k v]
  (str (js/encodeURIComponent (->str k)) "="
       (js/encodeURIComponent (->str v))))

(defn params->str
  "Returns a URI-encoded string of the params."
  [params]
  (if (zero? (count params))
    ""
    (let [reducer (fn [ret k v]
                    (conj ret (if (vector? v)
                                (string/join "&" (map (fn [x] (encode-kv k x)) v))
                                (encode-kv k v))))
          pairs   (reduce-kv reducer [] params)]
      (str "?" (string/join "&" pairs)))))

(defn headers->js
  "Returns a new js/Headers JavaScript object of the ClojureScript map of headers."
  [headers]
  (reduce-kv
    (fn [js-headers header-name header-value]
      (doto js-headers
        (.append (->str header-name)
                 (->str header-value))))
    (js/Headers.)
    headers))

(defn request->js-init
  "Returns an init options js/Object to use as the second argument to js/fetch."
  [{:keys [method headers request-content-type body mode credentials cache redirect referrer integrity] :as request}
   js-controller]
  (let [mode        (or mode "same-origin")
        credentials (or credentials "include")
        redirect    (or redirect "follow")
        body'       (if (= :json request-content-type)
                      (js/JSON.stringify (clj->js body))
                      body)
        headers'    (if (= :json request-content-type)
                      (merge {"Content-Type" "application/json"}
                             headers)
                      headers)]
    (doto
      #js {;; There is always a controller, as in our impl all requests can be
           ;; aborted.
           :signal      (.-signal js-controller)

           ;; There is always a method, as dispatch is via sub-effects like :get.
           :method      (->str method)

           ;; Although the below keys are usually optional, the default between
           ;; different browsers is inconsistent so we always set our own default.

           ;; Possible: cors no-cors same-origin navigate
           :mode        (->str mode)

           ;; Possible: omit same-origin include
           :credentials (->str credentials)

           ;; Possible: follow error manual
           :redirect    (->str redirect)}

      ;; Everything else is optional...
      (cond-> headers' (obj/set "headers" (headers->js headers')))

      (cond-> body (obj/set "body" body'))

      ;; Possible: default no-store reload no-cache force-cache only-if-cached
      (cond-> cache (obj/set "cache" (->str cache)))

      ;; Possible: no-referrer client
      (cond-> referrer (obj/set "referrer" (->str referrer)))

      ;; Sub-resource integrity string
      (cond-> integrity (obj/set "integrity" (->str integrity))))))

(defn js-headers->clj
  "Returns a new ClojureScript map of the js/Headers JavaScript object."
  [js-headers]
  (reduce
    (fn [headers [header-name header-value]]
      (assoc headers (keyword header-name) header-value))
    {}
    (es6-iterator-seq (.entries js-headers))))

(defn js-response->clj
  "Returns a new ClojureScript map of the js/Response JavaScript object."
  [^js js-response]
  {:url         (.-url js-response)
   :ok?         (.-ok js-response)
   :redirected? (.-redirected js-response)
   :status      (.-status js-response)
   :status-text (.-statusText js-response)
   :type        (.-type js-response)
   :final-uri?  (.-useFinalURL js-response)
   :headers     (js-headers->clj (.-headers js-response))})

(defn ->reader
  "Wrap a normal keyword on a reader map (see `response->header`), and
   set a default json body reader."
  [reader-or-kw]
  (cond
    ;; default json body reader
    (and (keyword? reader-or-kw)
         (#{:json} reader-or-kw)) default-json-reader

    ;; identity wrap reader
    (keyword? reader-or-kw) {:reader-kw reader-or-kw
                             :reader-fn identity}
    ;; user provided reader
    :else reader-or-kw))

(defn response->reader
  "Returns a reader map or the `default-text-reader` to use for the body of the
   response according to the Content-Type header.
   A reader map is one with 2 keys:
   - `reader-kw`: a keyword to indicate what js/Body method should be used.
   - `reader-fn`: a function that processes the `js-body` as required."
  [{:keys [response-content-types]} response]
  (let [content-type (get-in response [:headers :content-type] "text/plain")
        reader (reduce-kv
                (fn [ret pattern reader]
                  (if (or (and (string? pattern) (= content-type pattern))
                          (and (regexp? pattern) (re-find pattern content-type)))
                    (reduced reader)
                    ret))
                default-text-reader
                response-content-types)]
    (->reader reader)))

(defn timeout-race
  "Returns a js/Promise JavaScript object that is a race between another
   js/Promise JavaScript object and timeout in ms if timeout is not nil,
   otherwise js-promise."
  [js-promise timeout]
  (if timeout
    (.race js/Promise
           #js [js-promise
                (js/Promise.
                  (fn [_ reject]
                    (js/setTimeout #(reject :timeout) timeout)))])
    js-promise))

;; Effects and Handlers
;; =============================================================================

(def request-id->js-abort-controller
  (atom {}))

(defn body-success-handler
  [{:as   request
    :keys [request-id envelope? on-success on-failure]
    :or   {on-success [:fetch-no-on-success]
           on-failure [:fetch-no-on-failure]}}
   response
   {:keys [reader-kw reader-fn] :as _reader}
   js-body]
  (swap! request-id->js-abort-controller #(dissoc %1 %2) request-id)
  (let [body     (reader-fn js-body)
        response (cond-> response
                   body
                   (assoc :body   body
                          :reader reader-kw)
                   (not (:ok? response))
                   (assoc :problem :server))
        handler  (if (:ok? response)
                   on-success
                   on-failure)
        event    (if envelope?
                   (assoc-in handler [1 ::response] response)
                   (conj handler response))]
    (dispatch event)))

(defn body-problem-handler
  [{:as   request
    :keys [request-id envelope? on-failure]
    :or   {on-failure [:fetch-no-on-failure]}}
   response
   {:keys [reader-kw] :as _reader}
   js-error]
  (swap! request-id->js-abort-controller #(dissoc %1 %2) request-id)
  (let [problem-message (obj/get js-error "message")
        response        (assoc response
                          :problem         :body
                          :reader          reader-kw
                          :problem-message problem-message)
        event           (if envelope?
                          (assoc-in on-failure [1 ::response] response)
                          (conj on-failure response))]
    (dispatch event)))

(defn response-success-handler
  "Reads the js/Response JavaScript Object stream to completion. Returns nil."
  [request js-response]
  (let [response                       (js-response->clj js-response)
        {:keys [reader-kw] :as reader} (response->reader request response)]
    (if (or (= "0" (get-in response [:headers :content-length]))
            (= 204 (:status response)))
      (body-success-handler request
                            response
                            {:reader-fn identity}
                            nil)
      (-> (case reader-kw
            :json (.json js-response)
            :form-data (.formData js-response)
            :blob (.blob js-response)
            :array-buffer (.arrayBuffer js-response)
            :text (.text js-response))
          (.then (partial body-success-handler request response reader))
          (.catch (partial body-problem-handler request response reader))))))

(defn response-problem-handler
  [{:as   request
    :keys [request-id envelope? on-failure]
    :or   {on-failure [:fetch-no-on-failure]}}
   js-error]
  (swap! request-id->js-abort-controller #(dissoc %1 %2) request-id)
  (let [problem         (if (= :timeout js-error) :timeout :fetch)
        problem-message (if (= :timeout js-error) "Fetch timed out" (obj/get js-error "message"))
        response        {:problem         problem
                         :problem-message problem-message}
        event           (if envelope?
                          (assoc-in on-failure [1 ::response] response)
                          (conj on-failure response))]
    (dispatch event)))

(defn fetch
  "Initialise the request. Returns nil."
  [{:keys [url timeout params request-id envelope? on-request-id] :as request
    :or   {request-id (keyword (gensym "fetch-fx-"))}}]
  (when (vector? on-request-id)
    (let [event (if envelope?
                  (assoc-in on-request-id [1 ::request-id] request-id)
                  (conj on-request-id request-id))]
      (dispatch event)))
  (let [request'            (assoc request :request-id request-id)
        url'                (str url (params->str params))
        js-abort-controller (js/AbortController.)]
    (swap! request-id->js-abort-controller
           #(assoc %1 %2 %3)
           request-id
           js-abort-controller)
    (-> (timeout-race (js/fetch url' (request->js-init request' js-abort-controller)) timeout)
        (.then (partial response-success-handler request'))
        (.catch (partial response-problem-handler request')))))

(defn fetch-fx
  [envelope? effect]
  (let [seq-of-effects (->seq effect)]
    (doseq [effect seq-of-effects]
      (let [with-defaults (merge {:envelope? envelope?}
                                 effect)]
        (fetch with-defaults)))))

(reg-fx :fetch (partial fetch-fx false)) ;; deprecated
(reg-fx ::fetch (partial fetch-fx true))

(defn abort
  [{:keys [request-id]}]
  (let [js-abort-controller (get @request-id->js-abort-controller request-id)]
    (when js-abort-controller
      (swap! request-id->js-abort-controller #(dissoc %1 %2) request-id)
      (.abort js-abort-controller))))

(defn abort-fx
  [effect]
  (let [seq-of-effects (-> seq effect)]
    (doseq [effect seq-of-effects]
      (abort effect))))

(reg-fx :fetch/abort abort-fx) ;; deprecated
(reg-fx ::abort abort-fx)
