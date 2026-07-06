(ns gmail.threads
  "Gmail thread listing/reading/label-modification. REST v1, JVM-only."
  (:require [gmail.client :as client]))

#?(:clj
(defn list-threads
  "List threads, optionally filtered by `q` (Gmail search syntax, e.g.
  \"from:wise.com in:inbox\"), paginated via `max-results`/`page-token`."
  ([] (list-threads {}))
  ([{:keys [q max-results page-token] :as http-opts}]
   (client/request! "/threads"
                    (assoc (dissoc http-opts :q :max-results :page-token)
                           :query (cond-> {}
                                    q (assoc :q q)
                                    max-results (assoc :maxResults max-results)
                                    page-token (assoc :pageToken page-token)))))))

#?(:clj
(defn get-thread
  ([thread-id] (get-thread thread-id {}))
  ([thread-id http-opts]
   (client/request! (str "/threads/" thread-id) http-opts))))

#?(:clj
(defn modify-thread!
  "Add/remove label IDs on every message in a thread (and future messages
  added to it). `add-label-ids`/`remove-label-ids` are Gmail label IDs, not
  display names -- see gmail.labels/find-or-create-label! to resolve one."
  ([thread-id mods] (modify-thread! thread-id mods {}))
  ([thread-id {:keys [add-label-ids remove-label-ids]} http-opts]
   (client/request! (str "/threads/" thread-id "/modify")
                    (assoc http-opts
                           :method :post
                           :body (cond-> {}
                                   (seq add-label-ids) (assoc :addLabelIds add-label-ids)
                                   (seq remove-label-ids) (assoc :removeLabelIds remove-label-ids)))))))

#?(:clj
(defn archive-thread!
  "Remove the INBOX label -- Gmail's definition of archiving a thread."
  ([thread-id] (archive-thread! thread-id {}))
  ([thread-id http-opts]
   (modify-thread! thread-id {:remove-label-ids ["INBOX"]} http-opts))))
