(ns ^{:author "FrankS42"}
     nrepl-repls
  "An enhance tty transport that can be used with simple bash-scripts 
  to send clojure-forms to the nrepl server."
  (:use [clojure.tools.nrepl.transport])
  (:require [clojure.tools.nrepl.bencode :as be]
            [clojure.tools.nrepl.middleware.interruptible-eval]
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


(def repls-conn-context
  "Atomized map that maintains the association between the k-v contexts of the 
  different nrepl-repls connections, like:
  {\"conn-id:e4f9e307-f966-4168-ab8a-3e6a0916cd41\" {:pr-prompt? true :pr-value? true}
  \"conn-id:a3d4e307-f966-4168-ab8a-3e6a0916ab22\" {:kill-switch? true :pr-prompt? false :pr-value? false}}
  The uuid of the conn-id is set when the connection is established.
  The common context keys are:
  :kill-switch? - toggle to indicate that connection should be closed when all requests are handled.
  :fn-transport - to maintain a ref to the transport instance such that we can close it
  :pr-value? - toggle the printing of eval-results
  :pr-prompt? - toggle to print prompt"
  (atom {}))


(defn set-repls-conn-context!
  "Convenience fn to set a k-v of the repls-conn-context map.
  Usage:
  (nrepl-repls/set-repls-conn-context! \"conn-id:e4f9e307-f966-4168-ab8a-3e6a0916cd41\" :pr-prompt? false :pr-value? false)"
  [conn-id & {:as configs}]
  (swap! repls-conn-context 
        (fn [rss conn-id configs] 
          (let [s (get-in rss [conn-id] {})]
            (assoc-in rss [conn-id] (merge s configs))))
        conn-id
        configs))


(defn set-this-repls-conn-context! [& cfgs]
  "Convenience fn to set a k-v of the repls-conn-context map for the current connection.
  The current conn-id is obtained from the message-map as it is maintained in
  clojure.tools.nrepl.middleware.interruptible-eval/*msg*
  This fn should be used if you're outside of the repl-tty context, and inside a fn being eval'ed.
  A good example is the set-kill-switch! fn, which allows you to set a connection context' kv.
  Usage:
  (nrepl-repls/set-this-repls-conn-context! :kill-switch? true)"
  (when-let [conn-id (get clojure.tools.nrepl.middleware.interruptible-eval/*msg* :conn-id)]
    (apply set-repls-conn-context! conn-id cfgs)
    nil))


(defn set-kill-switch! 
  "Convenience fn to set the connection's context :kill-switch? value to true,
  like:
  (nrepl-repls/set-kill-switch!)
  Meant to be used in shell scripts or from a repl."
  []
    (nrepl-repls/set-this-repls-conn-context! 
      :kill-switch? true))


(def evals-in-progress
  "Atomized set to maintain the message evals that are in progress.
  When an eval request message is read, its id is added to the set.
  When a write message is received with a done status for a msg id, it is removed from the set.
  When the set is empty, i.e. no pending evals, then it's safe to close the connection."
  (atom #{}))

(def close-exception 
  "Debug info to keep the last exception thrown when the connection is closed.
  Should never happen... but heh...you never know."
  (atom nil))


(defn repls
  "Returns a Transport implementation suitable for serving an nREPL backend
   via shell-script-based in/out readers.
   Allows for a simple bash-script to send clojure-forms and receive printed results."
  ([^Socket s] (repls s s s))
  ([in out & [^Socket s]]
    (let [conn-id (str "conn-id:" (uuid))]
      ;; create a conn-id context entry with default initialization
      (nrepl-repls/set-repls-conn-context! conn-id :kill-switch? false)
      (let [r (PushbackReader. (io/reader in))
            w (io/writer out)
            cns (atom "user")
            prompt (fn [newline?]
                     (when (get-in @repls-conn-context [conn-id :pr-prompt?] false)
                       ;; no prompt printing by default
                       (do (when newline? (.write w (int \newline)))
                           (.write w (str @cns "=> ")))))
            session-id (atom nil)
            read-msg #(let [code (read r)
                            m (merge {:op "eval" :code [code] :ns @cns :id (str "eval" (uuid))}
                               (when @session-id {:session @session-id})
                               ;; add conn-id to message map such that we can get to context during eval
                               {:conn-id conn-id})]
                        ;; add msg id to evals-in-progress set
                        (swap! nrepl-repls/evals-in-progress conj (:id m))
                        m)
            read-seq (atom (cons {:op "clone"} (repeatedly read-msg)))
            write (fn [{:strs [out err value status ns new-session id] :as msg}]
                    (when new-session (reset! session-id new-session))
                    (when ns (reset! cns ns))
                    (if (get-in @repls-conn-context [conn-id :pr-value?] false)
                      (doseq [^String x [out err value] :when x]
                        (.write w x))
                      ;; do not print eval results by default
                      (doseq [^String x [out err] :when x]
                        (.write w x)))
                    (when (and (= status #{:done}) id (.startsWith ^String id "eval"))
                      (prompt true))
                    (when (and (= status #{:done}) id)
                      ;; remove msg id from evals-in-progress set when done
                      (swap! nrepl-repls/evals-in-progress disj id))
                    (.flush w)
                    ;; move any possible closing of socket after the flush
                    (when (and (empty? @nrepl-repls/evals-in-progress)
                               (get-in @nrepl-repls/repls-conn-context [conn-id :kill-switch?] false))
                      ;; close only when all is done and kill-switch is set
                      (future 
                        (try (.close (get-in @nrepl-repls/repls-conn-context [conn-id :fn-transport]))
                          (catch Exception e (reset! close-exception e))))))
            read #(let [head (promise)]
                    (swap! read-seq (fn [s]
                                       (deliver head (first s))
                                       (rest s)))
                    @head)]
      (let [fnt (fn-transport read write
                  (when s
                    (swap! read-seq (partial cons {:session @session-id :op "close"}))
                    #(.close s)))]
        ;; maintain fn-transport instance in connection context to close it later
        (set-repls-conn-context! conn-id :fn-transport fnt)
        fnt)))))

