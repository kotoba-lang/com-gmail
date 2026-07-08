(ns gmail.client-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [gmail.client :as client]))

(defn- stub-http-fn [status body]
  (fn [_req] {:status status :body body}))

(deftest request-gets-with-bearer-auth
  (let [captured (atom nil)
        http-fn (fn [req] (reset! captured req) {:status 200 :body "{\"threads\":[]}"})
        resp (client/request! "/threads" {:http-fn http-fn :token "test-token"})]
    (is (= (str client/api-base "/threads") (:url @captured)))
    (is (= :get (:method @captured)))
    (is (= "Bearer test-token" (get (:headers @captured) "Authorization")))
    (is (= {:threads []} resp))))

(deftest request-builds-a-query-string-from-the-query-opt
  (let [captured (atom nil)
        http-fn (fn [req] (reset! captured req) {:status 200 :body "{}"})]
    (client/request! "/threads" {:http-fn http-fn :token "t" :query {:q "wise" :maxResults 10}})
    (is (str/starts-with? (:url @captured) (str client/api-base "/threads?")))
    (is (re-find #"q=wise" (:url @captured)))
    (is (re-find #"maxResults=10" (:url @captured)))))

(deftest request-throws-on-non-2xx-transport-status
  (is (thrown-with-msg?
       #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
       #"Gmail API request failed"
       (client/request! "/threads" {:http-fn (stub-http-fn 401 "{\"error\":\"denied\"}") :token "t"}))))

(deftest request-returns-nil-for-an-empty-body-instead-of-a-parse-error
  (is (nil? (client/request! "/threads/t1/modify"
                             {:http-fn (stub-http-fn 200 "") :token "t" :method :post}))))

(deftest access-token-fails-closed-without-env-or-explicit-token
  (is (thrown-with-msg?
       #?(:clj clojure.lang.ExceptionInfo :cljs js/Error)
       #"GMAIL_ACCESS_TOKEN is required"
       (client/request! "/threads" {:http-fn (stub-http-fn 200 "{}")}))))
