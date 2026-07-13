(ns gmail.drafts-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.string :as str]
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

(deftest ->raw-message-omits-cc-when-not-given
  (let [decoded (decode (drafts/->raw-message {:to "a@example.com" :subject "hi" :body "hello"}))]
    (is (not (re-find #"(?m)^Cc:" decoded)))))

(deftest ->raw-message-includes-a-single-cc-address
  (let [decoded (decode (drafts/->raw-message {:to "a@example.com" :cc "b@example.com"
                                               :subject "hi" :body "hello"}))]
    (is (re-find #"Cc: b@example\.com\r\n" decoded))))

(deftest ->raw-message-joins-multiple-cc-addresses
  (let [decoded (decode (drafts/->raw-message {:to "a@example.com"
                                               :cc ["b@example.com" "c@example.com"]
                                               :subject "hi" :body "hello"}))]
    (is (re-find #"Cc: b@example\.com, c@example\.com\r\n" decoded))))

(deftest ->raw-message-references-overrides-the-in-reply-to-mirror-for-a-full-chain
  (let [decoded (decode (drafts/->raw-message {:to "a@example.com" :subject "hi" :body "hello"
                                               :in-reply-to "<msg-3@mail.gmail.com>"
                                               :references "<msg-1@mail.gmail.com> <msg-2@mail.gmail.com> <msg-3@mail.gmail.com>"}))]
    (is (re-find #"In-Reply-To: <msg-3@mail\.gmail\.com>\r\n" decoded))
    (is (re-find #"References: <msg-1@mail\.gmail\.com> <msg-2@mail\.gmail\.com> <msg-3@mail\.gmail\.com>\r\n" decoded))))

(deftest ->raw-message-stays-single-part-when-no-attachments-given
  ;; backward-compat: byte-identical shape to the pre-attachments version --
  ;; no MIME-Version/multipart header leaks in for the plain-text case.
  (let [decoded (decode (drafts/->raw-message {:to "a@example.com" :subject "hi" :body "hello"}))]
    (is (not (re-find #"MIME-Version" decoded)))
    (is (not (re-find #"multipart" decoded)))
    (is (re-find #"Content-Type: text/plain; charset=\"UTF-8\"\r\n\r\nhello$" decoded))))

#?(:clj
(deftest ->raw-message-builds-a-multipart-message-with-one-attachment
  (let [svg-bytes (.getBytes "<svg></svg>" "UTF-8")
        decoded (decode (drafts/->raw-message
                          {:to "a@example.com" :subject "hi" :body "hello"
                           :attachments [{:filename "diagram.svg" :content-type "image/svg+xml" :bytes svg-bytes}]}))]
    (is (re-find #"MIME-Version: 1\.0\r\n" decoded))
    (is (re-find #"Content-Type: multipart/mixed; boundary=\"(kotoba_[^\"]+)\"" decoded))
    (is (re-find #"Content-Type: text/plain; charset=\"UTF-8\"\r\n\r\nhello" decoded))
    (is (re-find #"Content-Type: image/svg\+xml; name=\"diagram\.svg\"" decoded))
    (is (re-find #"Content-Disposition: attachment; filename=\"diagram\.svg\"" decoded))
    (is (re-find #"Content-Transfer-Encoding: base64" decoded))
    ;; the attachment bytes actually round-trip through the MIME part
    (let [boundary (second (re-find #"boundary=\"([^\"]+)\"" decoded))
          part-re (re-pattern (str "Content-Transfer-Encoding: base64\\r\\n\\r\\n([^\\r]+(?:\\r\\n[^\\r-]+)*)\\r\\n--" boundary "--"))
          b64 (second (re-find part-re decoded))]
      (is (some? b64))
      (is (= "<svg></svg>" (String. (.decode (Base64/getMimeDecoder) (str/replace b64 #"\r\n" "")) "UTF-8")))))))

#?(:clj
(deftest ->raw-message-supports-multiple-attachments
  (let [decoded (decode (drafts/->raw-message
                          {:to "a@example.com" :subject "hi" :body "hello"
                           :attachments [{:filename "a.svg" :content-type "image/svg+xml" :bytes (.getBytes "A" "UTF-8")}
                                        {:filename "b.svg" :content-type "image/svg+xml" :bytes (.getBytes "B" "UTF-8")}]}))]
    (is (re-find #"filename=\"a\.svg\"" decoded))
    (is (re-find #"filename=\"b\.svg\"" decoded)))))

(deftest create-draft-posts-to-drafts-with-the-raw-message-and-optional-thread-id
  (let [captured (atom nil)
        http-fn (fn [req] (reset! captured req) {:status 200 :body "{\"id\":\"d1\"}"})]
    (drafts/create-draft! {:to "a@example.com" :subject "hi" :body "hello" :thread-id "t1"}
                          {:http-fn http-fn :token "tok"})
    (is (= :post (:method @captured)))
    (let [body (:body @captured)]
      (is (re-find #"\"threadId\":\"t1\"" body))
      (is (re-find #"\"raw\":" body)))))
