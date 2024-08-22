(ns taoensso.telemere.utils
  "Misc utils useful for Telemere handlers, middleware, etc."
  (:refer-clojure :exclude [newline])
  (:require
   [clojure.string          :as str]
   #?(:clj [clojure.java.io :as jio])
   [taoensso.encore         :as enc :refer [have have?]]
   [taoensso.telemere.impl  :as impl]))

(comment
  (require  '[taoensso.telemere :as tel])
  (remove-ns 'taoensso.telemere.utils)
  (:api (enc/interns-overview)))

;;;; Private

(enc/def* ^:no-doc upper-qn
  "Private, don't use.
  `:foo/bar` -> \"FOO/BAR\", etc."
  {:tag #?(:clj 'String :cljs 'string)}
  (enc/fmemoize (fn [x] (str/upper-case (enc/as-qname x)))))

(comment (upper-qn :foo/bar))

(enc/def* ^:no-doc format-level
  "Private, don't use.
  `:info` -> \"INFO\",
      `5` -> \"LEVEL:5\", etc."
  {:tag #?(:clj 'String :cljs 'string)}
  (enc/fmemoize
    (fn [x]
      (if (keyword?   x)
        (upper-qn     x)
        (str "LEVEL:" x)))))

(comment (format-level :info))

(enc/def* ^:no-doc format-id
  "Private, don't use.
  `:foo.bar/baz` -> \"::baz\", etc."
  {:tag #?(:clj 'String :cljs 'string)}
  (enc/fmemoize
    (fn [ns x]
      (if (keyword? x)
        (if (= (namespace x) ns)
          (str "::" (name x))
          (str            x))
        (str x)))))

(comment
  (format-id (str *ns*) ::id1)
  (format-id nil ::id1))

;;;; Unique IDs (UIDs)

(defn nano-uid-fn
  "Experimental, subject to change.
  Returns a (fn nano-uid [root?]) that returns a random nano-style uid string like:
    \"r76-B8LoIPs5lBG1_Uhdy\" - 126 bit (21 char)     root         uid
    \"tMEYoZH0K-\"            - 60  bit (10 char) non-root (child) uid"
  {:tag #?(:clj 'String :cljs 'string)}
  ([] (nano-uid-fn nil))
  ([{:keys [secure? root-len child-len]
     :or
     {root-len  21
      child-len 10}}]

   (let [root-len  (long root-len)
         child-len (long child-len)]

     #?(:cljs (fn nano-uid [root?] (if root? (enc/nanoid secure? root-len) (enc/nanoid secure? child-len)))
        :clj
        (if secure?
          (fn nano-uid-secure [root?]
            (let [srng (.get com.taoensso.encore.Ids/SRNG_STRONG)]
              (if root?
                (com.taoensso.encore.Ids/genNanoId srng root-len)
                (com.taoensso.encore.Ids/genNanoId srng child-len))))

          (fn nano-uid-insecure [root?]
            (if root?
              (com.taoensso.encore.Ids/genNanoId root-len)
              (com.taoensso.encore.Ids/genNanoId child-len))))))))

(comment ((nano-uid-fn) true))

(defn hex-uid-fn
  "Experimental, subject to change.
  Returns a (fn hex-uid [root?]) that returns a random hex-style uid string like:
    \"05039666eb9dc3206475f44ab9f3d843\" - 128 bit (32 char)     root         uid
    \"721fcef639a51513\"                 - 64  bit (16 char) non-root (child) uid"
  {:tag #?(:clj 'String :cljs 'string)}
  ([] (hex-uid-fn nil))
  ([{:keys [secure? root-len child-len]
     :or
     {root-len  32
      child-len 16}}]

   (let [root-len  (long root-len)
         child-len (long child-len)]

     #?(:cljs
        (let [rand-bytes-fn
              (if secure?
                (partial enc/rand-bytes true)
                (partial enc/rand-bytes false))

              hex-uid-root  (enc/rand-id-fn {:chars :hex-lowercase, :len root-len,  :rand-bytes-fn rand-bytes-fn})
              hex-uid-child (enc/rand-id-fn {:chars :hex-lowercase, :len child-len, :rand-bytes-fn rand-bytes-fn})]

          (fn hex-uid [root?] (if root? (hex-uid-root) (hex-uid-child))))

        :clj
        (if secure?
          (fn hex-uid-secure [root?]
            (let [srng (.get com.taoensso.encore.Ids/SRNG_STRONG)]
              (if root?
                (com.taoensso.encore.Ids/genHexId srng root-len)
                (com.taoensso.encore.Ids/genHexId srng child-len))))

          (fn hex-uid-insecure [root?]
            (if root?
              (com.taoensso.encore.Ids/genHexId root-len)
              (com.taoensso.encore.Ids/genHexId child-len))))))))

(comment ((hex-uid-fn) true))
(comment
  ;; [170.47 180.18 53.87 60.68]
  (let [nano-uid-fn (nano-uid-fn), hex-uid-fn (hex-uid-fn)]
    (enc/qb 1e6 (enc/uuid) (enc/uuid-str) (nano-uid true) (hex-uid true))))

;;;; Misc

(enc/defaliases
  enc/newline enc/pr-edn #?(:clj enc/uuid) enc/uuid-str
  #?@(:cljs [enc/pr-json])
  #?@(:clj  [enc/thread-info enc/thread-id enc/thread-name
             enc/host-info   enc/host-ip   enc/hostname]))

#?(:cljs
   (defn js-console-logger
     "Returns JavaScript console logger to match given signal level:
       `:trace` -> `js/console.trace`,
       `:error` -> `js/console.error`, etc.

     Defaults to `js.console.log` for unmatched signal levels.
     NB: assumes that `js/console` exists, handler constructors should check first!"
     [level]
     (case level
       :trace  js/console.trace
       :debug  js/console.debug
       :info   js/console.info
       :warn   js/console.warn
       :error  js/console.error
       :fatal  js/console.error
       :report js/console.info
       (do     js/console.log))))

(comment (js-console-logger))

(defn error-signal?
  "Experimental, subject to change.
  Returns true iff given signal has an `:error` value, or a `:kind` or `:level`
  that indicates that it's an error."
  #?(:cljs {:tag 'boolean})
  [signal]
  (and signal
    (boolean
      (or
        (get signal :error)
        (enc/identical-kw? (get signal :kind) :error)
        (case (get signal :level) (:error :fatal) true false)
        (get signal :error?) ; Provided kv
        ))))

(comment (error-signal? {:level :fatal}))

(defn ^:no-doc remove-signal-kvs
  "Private, don't use.
  Returns given signal without app-level kvs or `:kvs` key."
  [signal]
  (if-let [kvs (get signal :kvs)]
    (reduce-kv (fn [m k _v] (dissoc m k)) (dissoc signal :kvs) kvs)
    signal))

(defn ^:no-doc remove-signal-nils
  "Private, don't use.
  Returns given signal with nil-valued keys removed."
  [signal]
  (if (enc/editable? signal)
    (persistent! (reduce-kv (fn [m k v] (if (nil? v)   (dissoc! m k) m)) (transient signal) signal))
    (persistent! (reduce-kv (fn [m k v] (if (nil? v) m (assoc!  m k v))) (transient {})     signal))))
 
(defn ^:no-doc force-signal-msg
  "Private, don't use.
  Returns given signal with possible `:msg_` value forced (realized when a delay)."
  [signal]
  (if-let [msg_ (get signal :msg_)]
    (assoc signal :msg_ (force msg_))
    (do    signal)))
 
(defn ^:no-doc expand-signal-error
  "Private, don't use.
  Returns given signal with possible `:error` replaced by
  [{:keys [type msg data]} ...] cause chain."
  [signal]
  (enc/if-let [error (get signal :error)
               chain (enc/ex-chain :as-map error)]
    (assoc signal :error chain)
    (do    signal)))
 
;;;; Files

#?(:clj (defn ^:no-doc as-file ^java.io.File [file] (jio/as-file file)))
#?(:clj
   (defn ^:no-doc writeable-file!
     "Private, don't use.
     Returns writable `java.io.File`, or throws."
     ^java.io.File [file]
     (let [file (as-file  file)]
       (when-not (.exists file)
         (when-let [parent (.getParentFile file)] (.mkdirs parent))
         (.createNewFile file))

       (if (.canWrite file)
         file
         (throw
           (ex-info "Unable to prepare writable `java.io.File`"
             {:path (.getAbsolutePath file)}))))))

#?(:clj
   (defn ^:no-doc file-stream
     "Private, don't use.
     Returns new `java.io.FileOutputStream` for given `java.io.File`."
     ^java.io.FileOutputStream [file append?]
     (java.io.FileOutputStream. (as-file file) (boolean append?))))

#?(:clj
   (defn file-writer
     "Experimental, subject to change.
     Opens the specified file and returns a stateful fn of 2 arities:
       [content] => Writes given content to file, or noops if closed.
       []        => Closes the writer.

     Useful for basic handlers that write to a file, etc.

     Notes:
       - Automatically creates file and parent dirs as necessary.
       - Writer should be manually closed after use (with zero-arity call).
       - Flushes after every write.
       - Thread safe, locks on single file stream."

     [{:keys [file append?]
       :or   {append? true}}]

     (when-not file (throw (ex-info "Expected `:file` value" (enc/typed-val file))))

     (let [file    (writeable-file! file)
           stream_ (volatile! (file-stream file append?))
           open?_  (enc/latom true)

           close!
           (fn []
             (when (compare-and-set! open?_ true false)
               (when-let [^java.io.FileOutputStream stream (.deref stream_)]
                 (.close  stream)
                 (vreset! stream_ nil)
                 true)))

           reset!
           (fn []
             (close!)
             (vreset! stream_ (file-stream file append?))
             (reset!  open?_  true)
             true)

           write-ba!
           (fn [^bytes ba-content]
             (when-let [^java.io.FileOutputStream stream (.deref stream_)]
               (.write stream ba-content)
               (.flush stream)
               true))

           file-exists!
           (let [rl (enc/rate-limiter-once-per 100)]
             (fn []
               (or (rl) (.exists file)
                 (throw (java.io.IOException. "File doesn't exist")))))

           lock (Object.)]

       (fn a-file-writer
         ([] (when (open?_) (locking lock (close!))))
         ([content-or-action]
          (case content-or-action ; Undocumented, for dev/testing
            :writer/open?  (open?_)
            :writer/reset! (locking lock (reset!))
            :writer/state  {:file file, :stream (.deref stream_)}
            (when (open?_)
              (let [content content-or-action
                    ba (enc/str->utf8-ba (str content))]
                (locking lock
                  (try
                    (file-exists!)
                    (write-ba! ba)
                    (catch java.io.IOException _ ; Retry once
                      (reset!)
                      (write-ba! ba))))))))))))

(comment (def fw1 (file-writer {:file "test.txt"})) (fw1 "x") (fw1))

;;;; Sockets

#?(:clj
   (defn- default-socket-fn
     "Returns conected `java.net.Socket`, or throws."
     ^java.net.Socket [host port connect-timeout-msecs]
     (let [addr   (java.net.InetSocketAddress. ^String host (int port))
           socket (java.net.Socket.)]

       (if connect-timeout-msecs
         (.connect socket addr (int connect-timeout-msecs))
         (.connect socket addr))

       socket)))

#?(:clj
   (let [factory_ (delay (javax.net.ssl.SSLSocketFactory/getDefault))]
     (defn- default-ssl-socket-fn
       "Returns connected SSL `java.net.Socket`, or throws."
       ^java.net.Socket [^java.net.Socket socket ^String host port]
       (.createSocket ^javax.net.ssl.SSLSocketFactory @factory_
         socket host (int port) true))))

#?(:clj
   (defn tcp-socket-writer
     "Experimental, subject to change.
     Connects to specified TCP socket and returns a stateful fn of 2 arities:
       [content] => Writes given content to socket, or noops if closed.
       []        => Closes the writer.

     Useful for basic handlers that write to a TCP socket, etc.

     Options:
       `:ssl?`                  - Use SSL/TLS?
       `:connect-timeout-msecs` - Connection timeout (default 3000 msecs)
       `:socket-fn`             - (fn [host port timeout]) => `java.net.Socket`
       `:ssl-socket-fn`         - (fn [socket host port])  => `java.net.Socket`

     Notes:
       - Writer should be manually closed after use (with zero-arity call).
       - Flushes after every write.
       - Will retry failed writes once, then drop.
       - Thread safe, locks on single socket stream.
       - Advanced users may want a custom implementation using a connection
         pool and/or more sophisticated retry semantics, etc."

     [{:keys
       [host port, ssl? connect-timeout-msecs,
        socket-fn ssl-socket-fn] :as opts

       :or
       {connect-timeout-msecs 3000
        socket-fn     default-socket-fn
        ssl-socket-fn default-ssl-socket-fn}}]

     (when-not (string? host) (throw (ex-info "Expected `:host` string" (enc/typed-val host))))
     (when-not (int?    port) (throw (ex-info "Expected `:port` int"    (enc/typed-val port))))

     (let [new-conn! ; => [<java.net.Socket> <java.io.OutputStream>], or throws
           (fn []
             (try
               (let [^java.net.Socket socket
                     (let [socket (socket-fn host port connect-timeout-msecs)]
                       (if ssl?
                         (ssl-socket-fn socket host port)
                         (do            socket)))]

                 [socket (.getOutputStream socket)])

               (catch Exception ex
                 (throw (ex-info "Failed to create connection" opts ex)))))

           conn_  (volatile! (new-conn!))
           open?_ (enc/latom true)

           close!
           (fn []
             (when (compare-and-set! open?_ true false)
               (when-let [[^java.net.Socket socket] (.deref conn_)]
                 (.close  socket)
                 (vreset! conn_ nil)
                 true)))

           reset!
           (fn []
             (close!)
             (vreset! conn_ (new-conn!))
             (reset!  open?_ true)
             true)

           write-ba!
           (fn [^bytes ba-content]
             (when-let [[_ ^java.io.OutputStream output] (.deref conn_)]
               (.write output ba-content)
               (.flush output)
               true))

           conn-okay!
           (let [rl (enc/rate-limiter-once-per 100)]
             (fn []
               (or
                 (rl)
                 (when-let [[^java.net.Socket socket] (.deref conn_)]
                   (and
                     (not (.isClosed    socket))
                     (do  (.isConnected socket))))
                 (throw (java.io.IOException. "Bad connection")))))

           lock (Object.)]

       (fn a-tcp-socket-writer
         ([] (when (open?_) (locking lock (close!))))
         ([content-or-action]
          (case content-or-action ; Undocumented, for dev/testing
            :writer/open?  (open?_)
            :writer/reset! (locking lock (reset!))
            :writer/state  {:conn (.deref conn_)}
            (when (open?_)
              (let [content content-or-action
                    ba (enc/str->utf8-ba (str content))]
                (locking lock
                  (try
                    (conn-okay!)
                    (write-ba! ba)
                    (catch Exception _ ; Retry once
                      (reset!)
                      (write-ba! ba))))))))))))

;;;; Formatters

(defn format-nsecs-fn
  "Experimental, subject to change.
  Returns a (fn format [nanosecs]) that:
    - Takes a long nanoseconds (e.g. runtime).
    - Returns a human-readable string like:
      \"1.00m\", \"4.20s\", \"340ms\", \"822μs\", etc."
  ([] (format-nsecs-fn nil))
  ([{:as _opts}] (fn format-nsecs [nanosecs] (enc/format-nsecs nanosecs))))

(comment ((format-nsecs-fn) 4747463567))

(enc/defalias enc/format-inst-fn)

(comment ((format-inst-fn) (enc/now-inst)))

#?(:clj
   (defn- format-clj-stacktrace
     [trace]
     (let [sb   (enc/str-builder)
           s+nl (enc/sb-appender sb enc/newline)]
       (doseq [st-el (force trace)]
         (let [{:keys [class method file line]} st-el]
           (s+nl "  " class "/" method " at " file ":" line)))
       (str sb))))

(comment (println (format-clj-stacktrace (:trace (enc/ex-map (ex-info "Ex2" {:k2 "v2"} (ex-info "Ex1" {:k1 "v1"})))))))

(defn format-error-fn
  "Experimental, subject to change.
  Returns a (fn format [error]) that:
    - Takes a platform error (`Throwable` or `js/Error`).
    - Returns a human-readable error string."
  ([] (format-error-fn nil))
  ([{:as _opts}]
   (let [nl  enc/newline
         nls enc/newlines]

     (fn format-error [error]
       (when-let [em (enc/ex-map error)]
         (let [sb (enc/str-builder)
               s+ (partial enc/sb-append sb)
               {:keys [chain trace]} em]

           (let [s+cause (enc/sb-appender sb (str nls "Caused: "))]
             (s+ "  Root: ")
             (doseq [{:keys [type msg data]} (rseq chain)]
               (s+cause type " - " msg)
               (when data
                 (s+ nl "  data: " (enc/pr-edn* data)))))

           (when trace
             (s+ nl nl "Root stack trace:" nl)
             #?(:cljs (s+                        trace)
                :clj  (s+ (format-clj-stacktrace trace))))

           (str sb)))))))

(comment
  (do                         (throw      (ex-info "Ex2" {:k2 "v2"} (ex-info "Ex1" {:k1 "v1"}))))
  (do                         (enc/ex-map (ex-info "Ex2" {:k2 "v2"} (ex-info "Ex1" {:k1 "v1"}))))
  (println (str "--\n" ((format-error-fn) (ex-info "Ex2" {:k2 "v2"} (ex-info "Ex1" {:k1 "v1"}))))))

;;;;

(defn signal-preamble-fn
  "Experimental, subject to change.
  Returns a (fn preamble [signal]) that:
    - Takes a Telemere signal (map).
    - Returns a signal preamble ?string like:
      \"2024-03-26T11:14:51.806Z INFO EVENT Hostname taoensso.telemere(2,21) ::ev-id - msg\"

  Options:
    `:format-inst-fn` - (fn format [instant]) => string."
  ([] (signal-preamble-fn nil))
  ([{:keys [format-inst-fn]
     :or   {format-inst-fn (format-inst-fn)}}]

   (fn signal-preamble [signal]
     (let [{:keys [inst level kind ns id msg_]} signal
           sb    (enc/str-builder)
           s+spc (enc/sb-appender sb " ")]

       (when inst  (when-let [ff format-inst-fn] (s+spc (ff inst))))
       (when level (s+spc (format-level level)))

       (if kind (s+spc (upper-qn kind)) (s+spc "DEFAULT"))
       #?(:clj  (s+spc (hostname)))

       ;; "<ns>(<line>,<column>)"
       (when-let [base (or ns (get signal :file))]
         (let [s+ (partial enc/sb-append sb)] ; Without separator
           (s+ " " base)
           (when-let [l (get signal :line)]
             (s+ "(" l)
             (when-let [c (get signal :column)] (s+ "," c))
             (s+ ")"))))

       (when id (s+spc (format-id ns id)))
       (when-let [msg (force msg_)] (s+spc "- " msg))

       (when-not (zero? (enc/sb-length sb))
         (str sb))))))

(comment ((signal-preamble-fn) (tel/with-signal (tel/event! ::ev-id))))

(defn signal-content-fn
  "Experimental, subject to change.
  Returns a (fn content [signal]) that:
    - Takes a Telemere signal (map).
    - Returns a signal content ?string (incl. data, ctx, etc.).

  Options:
    `:raw-error?`      - Retain unformatted error? (default false)
    `:incl-keys`       - Subset of signal keys to retain from those
                         otherwise excluded by default: #{:kvs :host :thread}
    `:format-nsecs-fn` - (fn [nanosecs]) => string.
    `:format-error-fn` - (fn [error])    => string."

  ([] (signal-content-fn nil))
  ([{:keys [raw-error? incl-keys, format-nsecs-fn format-error-fn]
     :or
     {format-nsecs-fn (format-nsecs-fn) ; (fn [nanosecs])
      format-error-fn (format-error-fn) ; (fn [error])
      }}]

   (let [nl           newline
         err-start    (str nl "<<< error <<<" nl)
         err-stop     (str nl ">>> error >>>")
         incl-kvs?    (contains? incl-keys :kvs)
         incl-host?   (contains? incl-keys :host)
         incl-thread? (contains? incl-keys :thread)]

     (fn signal-content
       ([signal]
        (let [sb  (enc/str-builder)
              s++ (enc/sb-appender sb nl)]
          (signal-content signal s++ enc/pr-edn*)
          (when-not (zero? (enc/sb-length sb))
            (str sb))))

       ;; Undocumented, advanced arity
       ([signal append-fn val-fn]
        (let [af append-fn
              vf    val-fn]

          (let [{:keys [uid parent root data kvs ctx #?@(:clj [host thread]) sample-rate]} signal]
            (when              sample-rate          (af " sample: " (vf sample-rate)))
            (when              uid                  (af "    uid: " (vf uid)))
            (when              parent               (af " parent: " (vf (dissoc parent :inst)))) ; {:keys [id uid]}
            (when         (and parent root)         (af "   root: " (vf (dissoc root   :inst)))) ; {:keys [id uid]}
            #?(:clj (when (and host   incl-host?)   (af "   host: " (vf host))))   ; {:keys [      name ip]}
            #?(:clj (when (and thread incl-thread?) (af " thread: " (vf thread)))) ; {:keys [group name id]}
            (when              data                 (af "   data: " (vf data)))
            (when         (and kvs incl-kvs?)       (af "    kvs: " (vf kvs)))
            (when              ctx                  (af "    ctx: " (vf ctx))))

          (let [{:keys [run-form error]} signal]
            (when run-form
              (let [{:keys [run-val run-nsecs]} signal
                    run-time (when run-nsecs (when-let [ff format-nsecs-fn] (ff run-nsecs)))
                    run-info
                    (if error
                      {:form  run-form
                       :time  run-time
                       :nsecs run-nsecs}

                      {:form  run-form
                       :time  run-time
                       :nsecs run-nsecs
                       :val   run-val
                       #?@(:clj [:val-type (enc/class-sym run-val)])})]
                (af "    run: " (vf run-info))))

            (when error
              (if raw-error?
                (af "  error: " error)
                (when-let [ff format-error-fn]
                  (af err-start (ff error) err-stop)))))))))))

(comment
  ((signal-content-fn) (tel/with-signal (tel/event! ::ev-id)))
  ((signal-content-fn) (tel/with-signal (tel/event! ::ev-id {:data {:k1 "v1"}}))))

(defn pr-signal-fn
  "Experimental, subject to change.
  Returns a (fn pr [signal]) that:
    - Takes a Telemere signal (map).
    - Returns a machine-readable signal ?string.

  Options:
    `:pr-fn`         - ∈ #{<unary-fn> :edn (default) :json (Cljs only)}
    `:incl-kvs?`     - Include signal's app-level kvs?         (default false)
    `:incl-nils?`    - Include signal's keys with nil values?  (default false)
    `:incl-newline?` - Include terminating system newline?     (default true)
    `:incl-keys`     - Subset of signal keys to retain from those otherwise
                       excluded by default: #{:location :kvs :file :host :thread}

  Examples:
    (pr-signal-fn {:pr-fn :edn  ...}) ; Outputs edn
    (pr-signal-fn {:pr-fn :json ...}) ; Outputs JSON (Cljs only)

    To output JSON for Clj, you must provide an appropriate `:pr-fn`.
    `jsonista` offers one good option, Ref. <https://github.com/metosin/jsonista>:

      (require '[jsonista.core :as jsonista])
      (pr-signal-fn {:pr-fn jsonista/write-value-as-string ...})

  See also `format-signal-fn` for human-readable output."
  ([] (pr-signal-fn nil))
  ([{:keys [pr-fn, incl-kvs? incl-nils? incl-newline? incl-keys] :as opts
     :or
     {pr-fn         :edn
      incl-newline? true}}]

   (let [nl newline
         pr-fn
         (or
           (case pr-fn
             :none nil ; Undocumented, used for unit tests
             :edn  pr-edn
             :json
             #?(:cljs pr-json
                :clj
                (throw
                  (ex-info "`:json` pr-fn only supported in Cljs. To output JSON in Clj, please provide an appropriate unary fn instead (e.g. jsonista/write-value-as-string)."
                    {})))

             (if (fn? pr-fn)
               (do    pr-fn)
               (enc/unexpected-arg! pr-fn
                 {:context  `pr-signal-fn
                  :param    'pr-fn
                  :expected
                  #?(:clj  '#{:edn       unary-fn}
                     :cljs '#{:edn :json unary-fn})}))))

         assoc!*
         (if-not incl-nils?
           (fn [m k v] (if (nil? v) m (assoc! m k v))) ; As `remove-signal-nils`
           (do                         assoc!))

         incl-location? (contains? incl-keys :location)
         incl-kvs-key?  (contains? incl-keys :kvs)
         incl-file?     (contains? incl-keys :file)
         incl-host?     (contains? incl-keys :host)
         incl-thread?   (contains? incl-keys :thread)]

     (fn pr-signal [signal]
       (when (map?  signal)
         (let [signal
               (persistent!
                 (reduce-kv
                   (fn [m k v]
                     (enc/case-eval k
                       :msg_                                           (assoc!* m k (force v)) ; As  force-signal-msg
                       :error (if-let [chain (enc/ex-chain :as-map v)] (assoc!  m k chain) m)  ; As expand-signal-error

                       ;;; Keys excluded by default
                       :location (if incl-location? (assoc!* m k v) m)
                       :kvs      (if incl-kvs-key?  (assoc!* m k v) m)
                       :file     (if incl-file?     (assoc!* m k v) m)
                       :thread   (if incl-thread?   (assoc!* m k v) m)
                       :host     (if incl-host?     (assoc!* m k v) m)

                       (clojure.core/into ()
                         taoensso.telemere.impl/impl-signal-keys) m ; noop

                       (clojure.core/into ()
                         (clojure.core/disj
                           taoensso.telemere.impl/standard-signal-keys
                           :msg_ :error :location :kvs :file :host :thread))
                       (assoc!* m k v)

                       (if incl-kvs? (assoc!* m k v) m) ; As remove-signal-kvs
                       ))

                   (transient {}) signal))]

           (if-not pr-fn
             signal
             (let [output (pr-fn signal)]
               (if incl-newline?
                 (str output nl)
                 (do  output))))))))))

(comment
  (def s1 (tel/with-signal (tel/event! ::ev-id {:kvs {:k1 "v1"}})))
  ((pr-signal-fn {:pr-fn :edn})           s1)
  ((pr-signal-fn {:pr-fn (fn [_] "str")}) s1)
  ((pr-signal-fn {:pr-fn :none})          s1)

  (let [pr-fn (pr-signal-fn {:pr-fn :none})]
    (enc/qb 1e6 ; 817.78
      (pr-fn s1))))

(defn format-signal-fn
  "Experimental, subject to change.
  Returns a (fn format [signal]) that:
    - Takes a Telemere signal (map).
    - Returns a human-readable signal string.

  Options:
    `:incl-newline?` - Include terminating system newline? (default true)
    `:preamble-fn`   - (fn [signal]) => signal preamble string.
    `:content-fn`    - (fn [signal]) => signal content  string.

  See also `pr-signal-fn` for machine-readable output."
  ([] (format-signal-fn nil))
  ([{:keys [incl-newline? preamble-fn content-fn]
     :or
     {incl-newline? true
      preamble-fn (signal-preamble-fn)
      content-fn  (signal-content-fn)}}]

   (let [nl newline]
     (fn format-signal [signal]
       (let [preamble (when preamble-fn (preamble-fn signal))
             content  (when content-fn  (content-fn  signal))]

         (if (and preamble content)
           (str preamble nl content (when incl-newline? nl))
           (str preamble    content (when incl-newline? nl))))))))

(comment
  (tel/with-ctx {:c :C}
    (println
      ((format-signal-fn)
       (tel/with-signal
         (tel/event! ::ev-id
           {:my-k1 #{:a :b :c}
            :msg   "hi"
            :data  {:a :A}
            ;; :error (ex-info "Ex2" {:k2 "v2"} (ex-info "Ex1" {:k1 "v1"}))
            :run   (/ 1 0)}))))))
