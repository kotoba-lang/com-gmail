(ns gmail.client
  "Portable core for talking to the Gmail API v1 -- one auth/HTTP boundary
  for every gmail.* namespace in this library.

  Query construction and response parsing are pure .cljc. The actual HTTP
  call is JVM-only by default (java.net.http) but always takes an
  injectable `:http-fn` -- the same `{:url :method :headers :body} ->
  {:status :body}` convention as cloudflare.client (kotoba-lang/com-cloudflare)
  and cloud-itonami.runtime/jvm-http-fn -- so every namespace here is
  testable with a stub, never only against a live account.

  Auth is a bearer OAuth2 access token. Obtaining/refreshing that token (the
  OAuth2 consent flow itself) is out of scope for this library -- callers
  pass a valid token, the same way cloudflare.client expects a
  CLOUDFLARE_API_TOKEN rather than performing its own auth flow."
  (:require [clojure.string :as str]
            #?(:clj [clojure.data.json :as json])))

(def api-base "https://gmail.googleapis.com/gmail/v1/users/me")

#?(:clj
(defn jvm-http-fn
  "Real java.net.http transport. {:url :method :headers :body} ->
  {:status :body}, same convention as cloudflare.client/jvm-http-fn."
  ([] (jvm-http-fn {}))
  ([{:keys [timeout-seconds] :or {timeout-seconds 30}}]
   (fn [{:keys [url method headers body]}]
     (let [builder (-> (java.net.http.HttpRequest/newBuilder (java.net.URI/create url))
                       (.timeout (java.time.Duration/ofSeconds timeout-seconds))
                       (as-> b (reduce-kv (fn [b k v] (.header b k v)) b headers)))
           request (case method
                     :post (-> builder
                              (.POST (java.net.http.HttpRequest$BodyPublishers/ofString (or body "")))
                              .build)
                     :get (-> builder .GET .build)
                     (throw (ex-info "Unsupported HTTP method" {:method method})))
           resp (.send (java.net.http.HttpClient/newHttpClient) request
                      (java.net.http.HttpResponse$BodyHandlers/ofString))]
       {:status (.statusCode resp) :body (.body resp)})))))

#?(:clj
(defn access-token
  "GMAIL_ACCESS_TOKEN from the environment, or throw. Callers can always
  override via an explicit :token in opts instead of relying on env."
  []
  (or (System/getenv "GMAIL_ACCESS_TOKEN")
      (throw (ex-info "GMAIL_ACCESS_TOKEN is required" {})))))

#?(:clj
(defn- auth-headers [token]
  {"Authorization" (str "Bearer " token)
   "Content-Type" "application/json"}))

#?(:clj
(defn request!
  "Call a Gmail API v1 endpoint. `path` is relative to api-base (e.g.
  \"/threads\" or (str \"/threads/\" thread-id)). `opts` accepts :method
  (default :get), :body (a map, JSON-encoded), :query (a map of query
  params), :http-fn, :token. Returns the parsed JSON body, or nil for an
  empty body (Gmail returns an empty 200 for some modify calls). Throws on
  a transport-level non-2xx status."
  ([path] (request! path {}))
  ([path {:keys [method body http-fn token query]
          :or {method :get http-fn (jvm-http-fn)}}]
   (let [query-string (when (seq query)
                        (str "?" (str/join "&" (map (fn [[k v]] (str (name k) "=" v)) query))))
         resp (http-fn (cond-> {:url (str api-base path query-string)
                                :method method
                                :headers (auth-headers (or token (access-token)))}
                        body (assoc :body (json/write-str body))))]
     (when-not (< (:status resp) 300)
       (throw (ex-info "Gmail API request failed"
                       {:status (:status resp) :path path :body (:body resp)})))
     (when (seq (:body resp))
       (json/read-str (:body resp) :key-fn keyword))))))
