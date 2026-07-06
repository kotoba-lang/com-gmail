(ns gmail.drafts
  "Gmail draft creation (plain-text reply drafts). REST v1, JVM-only."
  (:require [gmail.client :as client])
  #?(:clj (:import [java.util Base64])))

#?(:clj
(defn- base64url-encode ^String [^String s]
  (.encodeToString (Base64/getUrlEncoder) (.getBytes s "UTF-8"))))

#?(:clj
(defn ->raw-message
  "Build a minimal RFC 2822 plain-text message and base64url-encode it, the
  shape Gmail's `drafts.create` `message.raw` field expects."
  [{:keys [to subject body in-reply-to]}]
  (base64url-encode
   (str "To: " to "\r\n"
        "Subject: " subject "\r\n"
        (when in-reply-to (str "In-Reply-To: " in-reply-to "\r\nReferences: " in-reply-to "\r\n"))
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
