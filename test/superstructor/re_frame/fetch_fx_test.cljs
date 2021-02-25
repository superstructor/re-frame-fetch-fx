(ns superstructor.re-frame.fetch-fx-test
  (:require
    [clojure.test :refer [deftest is testing async use-fixtures]]
    [clojure.spec.alpha :as s]
    [goog.object :as obj]
    [re-frame.core :as rf]
    [superstructor.re-frame.fetch-fx :as fetch-fx]))

(deftest localhost-test
  (async done
    (rf/reg-event-fx :localhost-test-success
                     (fn [_ [_ {:keys [ok?]}]]
                       (is ok?)
                       (done)
                       {}))
    (rf/reg-event-fx :localhost-test-failure
                     (fn [_ [_ res]]
                       (is false)
                       (done)
                       {}))
    (rf/reg-event-fx :localhost-test
                     (fn [_ _]
                       {:fetch {:method     :get
                                :mode       :no-cors
                                :url        js/window.location.href
                                :on-success [:localhost-test-success]
                                :on-failure [:localhost-test-failure]}}))
    (rf/dispatch [:localhost-test])))

;; Utilities
;; =============================================================================

(deftest ->seq-test
  (is (= [{}]
         (fetch-fx/->seq {})))
  (is (= [{}]
         (fetch-fx/->seq [{}])))
  (is (= [nil]
         (fetch-fx/->seq nil))))

(deftest ->str-test
  (is (= ""
         (fetch-fx/->str nil)))
  (is (= "42"
         (fetch-fx/->str 42)))
  (is (= "salient"
         (fetch-fx/->str :salient)))
  (is (= "symbolic"
         (fetch-fx/->str 'symbolic))))

(deftest ->params->str-test
  (is (= ""
         (fetch-fx/params->str nil)))
  (is (= ""
         (fetch-fx/params->str {})))
  (is (= "?sort=desc&start=0"
         (fetch-fx/params->str {:sort :desc :start 0})))
  (is (= "?ids=1&ids=2&ids=3&ids=4"
         (fetch-fx/params->str {:ids [1 2 3 4]})))
  (is (= "?fq=Expect%20nothing%2C%20%5Ba-z%26%26%5B%5Eaeiou%5D%5D&debug=timing"
         (fetch-fx/params->str {:fq         "Expect nothing, [a-z&&[^aeiou]]"
                                :debug 'timing}))))

(deftest headers->js-test
  (let [js-headers (fetch-fx/headers->js {:content-type "application/json"})]
    (is (instance? js/Headers js-headers))
    (is (= "application/json"
           (.get js-headers "content-type")))))

(deftest request->js-init-test
  (let [js-abort-controller (js/AbortController.)
        js-init             (fetch-fx/request->js-init
                              {:method "GET"}
                              js-abort-controller)]
    (is (= "{\"signal\":{},\"method\":\"GET\",\"mode\":\"same-origin\",\"credentials\":\"include\",\"redirect\":\"follow\"}"
           (js/JSON.stringify js-init)))
    (is (= (.-signal js-abort-controller)
           (.-signal js-init)))))

(deftest js-headers->clj-test
  (let [headers {:content-type "application/json"
                 :server       "nginx"}]
    (is (= headers
           (fetch-fx/js-headers->clj (fetch-fx/headers->js headers))))))

(deftest js-response->clj
  (is (= {:url ""
          :ok? true
          :redirected? false
          :status 200
          :status-text ""
          :type "default"
          :final-uri? nil
          :headers {}}
         (fetch-fx/js-response->clj (js/Response.)))))

(deftest response->reader-test
  (let [{:keys [reader-kw reader-fn]} (fetch-fx/response->reader
                                        {}
                                        {:headers {:content-type "application/json"}})]
    (is (= :text reader-kw))
    (is (fn? reader-fn)))
  (let [{:keys [reader-kw reader-fn]} (fetch-fx/response->reader
                                        {:response-content-types {"text/plain" :blob}}
                                        {:headers {}})]
    (is (= :blob reader-kw))
    (is (nil? (reader-fn)))) ;; TODO: is this correct ?
  (let [{:keys [reader-kw reader-fn]} (fetch-fx/response->reader
                                        {:response-content-types {#"(?i)application/.*json" :json}}
                                        {:headers {:content-type "application/json"}})]
    (is (= :json reader-kw))
    (is (fn? reader-fn))))

(deftest timeout-race-test
  (async done
    (-> (fetch-fx/timeout-race
          (js/Promise.
            (fn [_ reject]
              (js/setTimeout #(reject :winner) 16)))
          32)
        (.catch (fn [value]
                  (is (= :winner value))
                  (done)))))
  (async done
    (-> (fetch-fx/timeout-race
          (js/Promise.
            (fn [_ reject]
              (js/setTimeout #(reject :winner) 32)))
          16)
        (.catch (fn [value]
                  (is (= :timeout value))
                  (done))))))
