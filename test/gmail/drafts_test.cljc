(ns gmail.drafts-test
  (:require [clojure.test :refer [deftest is]]
            [gmail.drafts :as drafts])
  #?(:clj (:import [java.util Base64])))

#?(:clj
(defn- decode [raw] (String. (.decode (Base64/getUrlDecoder) ^String raw) "UTF-8")))

(deftest ->raw-message-encodes-a-plain-text-rfc2822-message
  (let [raw (drafts/->raw-message {:to "a@example.com" :subject "hi" :body "hello"})
        decoded (decode raw)]
    (is (re-find #"To: a@example\.com\r\n" decoded))
    (is (re-find #"Subject: hi\r\n" decoded))
    (is (re-find #"\r\n\r\nhello$" decoded))))

(deftest ->raw-message-includes-in-reply-to-and-references-when-given
  (let [decoded (decode (drafts/->raw-message {:to "a@example.com" :subject "hi" :body "hello"
                                               :in-reply-to "<msg-1@mail.gmail.com>"}))]
    (is (re-find #"In-Reply-To: <msg-1@mail\.gmail\.com>\r\n" decoded))
    (is (re-find #"References: <msg-1@mail\.gmail\.com>\r\n" decoded))))

(deftest create-draft-posts-to-drafts-with-the-raw-message-and-optional-thread-id
  (let [captured (atom nil)
        http-fn (fn [req] (reset! captured req) {:status 200 :body "{\"id\":\"d1\"}"})]
    (drafts/create-draft! {:to "a@example.com" :subject "hi" :body "hello" :thread-id "t1"}
                          {:http-fn http-fn :token "tok"})
    (is (= :post (:method @captured)))
    (let [body (:body @captured)]
      (is (re-find #"\"threadId\":\"t1\"" body))
      (is (re-find #"\"raw\":" body)))))
