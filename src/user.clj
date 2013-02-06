(ns user
  [:require [nrepl-repls]
            [clojure.tools.nrepl.server]
            [clojure.tools.nrepl.transport]])


(defonce nrepl-srv (nrepl-repls/start-repls-server))


(defn -main [])
