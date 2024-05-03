(ns taoensso.telemere.sockets
  "Basic TCP/UDP socket handlers."
  (:require
   [taoensso.encore :as enc :refer [have have?]]
   [taoensso.telemere.utils :as utils])

  (:import
   [java.net Socket InetAddress]
   [java.net DatagramSocket DatagramPacket InetSocketAddress]
   [java.io  PrintWriter]))

(comment
  (require  '[taoensso.telemere :as tel])
  (remove-ns 'taoensso.telemere.sockets)
  (:api (enc/interns-overview)))

;;;; Implementation

;;;; Handlers

(defn handler:tcp-socket
  "Experimental, subject to change. Feedback welcome!

  Returns a (fn handler [signal]) that:
    - Takes a Telemere signal.
    - Sends formatted signal string to specified TCP socket.

  Options:
    `host` - Destination TCP socket hostname string
    `port` - Destination TCP socket port int

    `:socket-opts` - {:keys [ssl? connect-timeout-msecs]}
    `:output-fn`   - (fn [signal]) => output string, see `format-signal-fn` or `pr-signal-fn`

  Limitations:
    - Failed writes will be retried only once.
    - Writes lock on a single underlying socket, so IO won't benefit from adding
      extra handler threads. Let me know if there's demand for socket pooling."

  ([host port] (handler:tcp-socket host port nil))
  ([host port
    {:keys [socket-opts output-fn]
     :or   {output-fn (utils/format-signal-fn)}}]

   (let [sw (utils/tcp-socket-writer host port socket-opts)]
     (defn a-handler:tcp-socket
       ([] (sw)) ; Shut down
       ([signal]
        (when-let [output (output-fn signal)]
          (sw output)))))))

(defn handler:udp-socket
  "Experimental, subject to change. Feedback welcome!

  Returns a (fn handler [signal]) that:
    - Takes a Telemere signal.
    - Sends formatted signal string to specified UDP socket.

  Options:
    `host` - Destination UDP socket hostname string
    `port` - Destination UDP socket port int

    `:output-fn`             - (fn [signal]) => output string, see `format-signal-fn` or `pr-signal-fn`
    `:max-packet-bytes`      - Max packet size (in bytes) before truncating output (default 512)
    `:truncation-warning-fn` - Optional (fn [{:keys [max actual signal]}]) to call whenever
                               output is truncated. Should be appropriately rate-limited!

  Limitations:
    - Due to UDP limitations, truncates output to `max-packet-bytes`!
    - Failed writes will be retried only once.
    - Writes lock on a single underlying socket, so IO won't benefit from adding
      extra handler threads. Let me know if there's demand for socket pooling.
    - No DTLS (Datagram Transport Layer Security) support,
      please let me know if there's demand."

  ([host port] (handler:udp-socket host port nil))
  ([host port
    {:keys [output-fn max-packet-bytes truncation-warning-fn]
     :or
     {output-fn (utils/format-signal-fn)
      max-packet-bytes 512}}]

   (let [max-packet-bytes (int max-packet-bytes)
         socket (DatagramSocket.) ; No need to change socket once created
         lock   (Object.)]

     (.connect socket (InetSocketAddress. (str host) (int port)))

     (defn a-handler:udp-socket
       ([] (.close socket)) ; Shut down
       ([signal]
        (when-let [output (output-fn signal)]
          (let [ba     (enc/str->utf8-ba (str output))
                ba-len (alength ba)
                packet (DatagramPacket. ba (min ba-len max-packet-bytes))]

            (when (and truncation-warning-fn (> ba-len max-packet-bytes))
              ;; Fn should be appropriately rate-limited
              (truncation-warning-fn {:max max-packet-bytes, :actual ba-len, :signal signal}))

            (locking lock
              (try
                (.send (DatagramSocket.) packet)
                (catch Exception _ ; Retry once
                  (Thread/sleep 250)
                  (.send (DatagramSocket.) packet)))))))))))
