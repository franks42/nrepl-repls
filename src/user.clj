(ns user
  [:require [nrepl-repls]
            [clojure.tools.nrepl.server]
            [clojure.tools.nrepl.transport]])

(def nrepl-srv 
     (clojure.tools.nrepl.server/start-server 
        :port 12345
;;         :transport-fn clojure.tools.nrepl.transport/tty))
        :transport-fn nrepl-repls/repls))
