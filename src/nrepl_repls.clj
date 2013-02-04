
(ns ^{:author "FrankS42"}
     nrepl-repls
  (:use [clojure.tools.nrepl.transport])
  (:require [clojure.tools.nrepl.bencode :as be]
            [clojure.java.io :as io]
            (clojure walk set))
  (:use [clojure.tools.nrepl.misc :only (returning uuid)])
  (:refer-clojure :exclude (send))
  (:import (java.io InputStream OutputStream PushbackInputStream
                    PushbackReader IOException EOFException)
           (java.net Socket SocketException)
           (java.util.concurrent SynchronousQueue LinkedBlockingQueue
                                 BlockingQueue TimeUnit)
           clojure.lang.RT))

(def ^:dynamic *session-id*)
(def repls-session-state (atom {}))
(defn set-repls-session-state! [session-id & {:as configs}]
  (swap! repls-session-state 
        (fn [rss session-id configs] 
          (let [s (get-in rss [session-id] {})]
            (assoc-in rss [session-id] (merge s configs))))
        session-id
        configs))
  

(defn repls
  "Returns a Transport implementation suitable for serving an nREPL backend
   via simple in/out readers, as with a tty or telnet connection."
  ([^Socket s] (repls s s s))
  ([in out & [^Socket s]]
    (let [r (PushbackReader. (io/reader in))
          w (io/writer out)
          cns (atom "user")
          prompt (fn [newline?]
                   (when newline? (.write w (int \newline)))
                   (.write w (str @cns "==> ")))
          conn-id (str "conn-id" (uuid))
          session-id (atom nil)
          read-msg #(let [code (read r)]
                      (println "code:" code)
                      (merge {:op "eval" :code [code] :ns @cns :id (str "eval" (uuid))}
                             (when @session-id {:session @session-id})
                             {:conn-id conn-id}))
          read-seq (atom (cons {:op "clone"} (repeatedly read-msg)))
;;           read-seq (atom (repeatedly read-msg))
          write (fn [{:strs [out err value status ns new-session id] :as msg}]
;;                   (.write w (str msg))
                  (when new-session (reset! session-id new-session))
                  (when ns (reset! cns ns))
                  (if (get-in @repls-session-state [@session-id :prn-value] true)
                    (doseq [^String x [out err value] :when x]
                      (.write w x))
                    (doseq [^String x [out err] :when x]
                      (.write w x)))
                  (when (and (= status #{:done}) id (.startsWith ^String id "eval"))
                    (prompt true))
                  (.flush w))
          read #(let [head (promise)]
                  (swap! read-seq (fn [s]
                                     (deliver head (first s))
                                     (rest s)))
                  (println "@head:" @head)
                  @head)]
      (binding [*session-id* session-id]
        (fn-transport read write
          (when s
            (swap! read-seq (partial cons {:session @session-id :op "close"}))
            #(.close s)))))))

