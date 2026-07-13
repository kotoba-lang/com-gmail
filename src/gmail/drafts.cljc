(ns gmail.drafts
  "Gmail draft creation (plain-text reply drafts). REST v1, JVM-only."
  (:require [gmail.client :as client]
            [clojure.string :as str])
  #?(:clj (:import [java.util Base64])))

#?(:clj
(defn- base64url-encode ^String [^String s]
  (.encodeToString (Base64/getUrlEncoder) (.getBytes s "UTF-8"))))

#?(:clj
(defn- addr-list
  "Normalizes a Cc/To-style value to one comma-separated header string.
  Accepts a single address string or a collection of address strings --
  reply threads with multiple recipients (e.g. Cc'ing co-counsel) need the
  collection form."
  [v]
  (cond
    (nil? v) nil
    (string? v) v
    (coll? v) (str/join ", " v)
    :else (str v))))

#?(:clj
(defn ->raw-message
  "Build a minimal RFC 2822 plain-text message and base64url-encode it, the
  shape Gmail's `drafts.create` `message.raw` field expects.

  `cc` accepts one address string or a collection of address strings.
  `references` overrides the default References-mirrors-In-Reply-To
  behavior with a full RFC 2822 References chain (space-separated
  Message-IDs) when replying deep into a thread that has more than one
  prior message -- Gmail's own threading heuristic (and other clients')
  uses the whole chain, not just the immediate parent."
  [{:keys [to cc subject body in-reply-to references]}]
  (base64url-encode
   (str "To: " to "\r\n"
        (when-let [cc-line (addr-list cc)] (str "Cc: " cc-line "\r\n"))
        "Subject: " subject "\r\n"
        (when in-reply-to (str "In-Reply-To: " in-reply-to "\r\n"))
        (when-let [refs (or references in-reply-to)] (str "References: " refs "\r\n"))
        "Content-Type: text/plain; charset=\"UTF-8\"\r\n"
        "\r\n"
        body))))

#?(:clj
(defn create-draft!
  "Create a Gmail draft. `thread-id` (optional) attaches the draft to an
  existing thread instead of starting a new one -- the same shape manimani's
  reply_llm policy needs (file the draft under the original conversation)."
  ([message] (create-draft! message {}))
  ([{:keys [thread-id] :as message} http-opts]
   (client/request! "/drafts"
                    (assoc http-opts
                           :method :post
                           :body {:message (cond-> {:raw (->raw-message message)}
                                             thread-id (assoc :threadId thread-id))})))))
