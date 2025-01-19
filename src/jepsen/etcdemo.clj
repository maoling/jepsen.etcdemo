(ns jepsen.etcdemo
    (:require [clojure.string :as str]
      [clojure.tools.logging :refer :all]
      (jepsen [checker :as checker]
              [cli :as cli]
              [client :as client]
              [control :as c]
              [db :as db]
              [generator :as gen]
              [independent :as independent]
              [nemesis :as nemesis]
              [tests :as tests])
      [jepsen.checker.timeline :as timeline]
      [jepsen.control.util :as cu]
      [jepsen.os.debian :as debian]
      [knossos.model :as model]
      [slingshot.slingshot :refer [try+]]
      [verschlimmbesserung.core :as v]))


(defn r [_ _] {:type :invoke, :f :read, :value nil})
(defn w [_ _] {:type :invoke, :f :write, :value (rand-int 5)})
(defn cas [_ _] {:type :invoke, :f :cas, :value [(rand-int 5) (rand-int 5)]})

(def dir "/opt/etcd")
(def binary "etcd")
(def logfile (str dir "/etcd.log"))
(def pidfile (str dir "/etcd.pid"))

(defn node-url
      "An HTTP url for connecting to a node on a particular port."
      [node port]
      (str "http://" node ":" port))

(defn peer-url
      "The HTTP url for other peers to talk to a node."
      [node]
      (node-url node 2380))

(defn client-url
      "The HTTP url clients use to talk to a node"
      [node]
      (node-url node 2379))

(defn initial-cluster
      "Constructs an initial cluster string for a test, like
      \foo=foo:2380,bar=bar:2380,...\""
      [test]
      (->> (:nodes test)
           (map (fn [node]
                    (str node "=" (peer-url node))))
           (str/join ",")))

(defn parse-long-nil
      "Parses a string to a Long. Passes through `nil`."
      [s]
      (when s (parse-long s)))

(defrecord Client [conn]
           client/Client
           (open! [this test node]
                  (assoc this :conn (v/connect (client-url node)
                                               {:timeout 5000})))

           (setup! [this test])

					 (invoke! [_ test op]
										(let [[k v] (:value op)]
												 (try+
													 (case (:f op)
																 :read (let [value (-> conn
																											 (v/get k {:quorum? (:quorum test)})
																											 parse-long-nil)]
																						(assoc op :type :ok, :value (independent/tuple k value)))

																 :write (do (v/reset! conn k v)
																						(assoc op :type :ok))

																 :cas (let [[old new] v]
																					 (assoc op :type (if (v/cas! conn k old new)
																														 :ok
																														 :fail))))

													 (catch java.net.SocketTimeoutException e
														 (assoc op
																		:type  (if (= :read (:f op)) :fail :info)
																		:error :timeout))

													 (catch [:errorCode 100] e
														 (assoc op :type :fail, :error :not-found)))))

           (teardown! [this test])

           (close! [_ test]))

(defn db
      "Etcd DB for a particular version."
      [version]
      (reify db/DB
             (setup! [_ test node]
                     (info node "installing etcd" version)
                     (c/su
                       (let [url (str "https://storage.googleapis.com/etcd/" version
                                      "/etcd-" version "-linux-amd64.tar.gz")]
                            (cu/install-archive! url dir))

                       (cu/start-daemon!
                         {:logfile logfile
                          :pidfile pidfile
                          :chdir   dir}
                         binary
                         :--log-output :stderr
                         :--name (name node)
                         :--listen-peer-urls (peer-url node)
                         :--listen-client-urls (client-url node)
                         :--advertise-client-urls (client-url node)
                         :--initial-cluster-state :new
                         :--initial-advertise-peer-urls (peer-url node)
                         :--initial-cluster (initial-cluster test))

                       (Thread/sleep 10000)))

             (teardown! [_ test node]
                        (info node "tearing down etcd")
                        (cu/stop-daemon! binary pidfile)
                        (c/su (c/exec :rm :-rf dir)))
             db/LogFiles
             (log-files [_ test node]
                        [logfile])))

(def cli-opts
	"Additional command line options."
	[["-q" "--quorum" "Use quorum reads, instead of reading from any primary."]
	 ["-r" "--rate HZ" "Approximate number of requests per second, per thread."
		:default 10
		:parse-fn read-string
		:validate [#(and (number? %) (pos? %))] "Must be a positive number"]
	 [nil "--ops-per-key NUM" "Maximum number of operations on any given key. "
		:default 10
		:parse-fn parse-long
		:validate [pos? "Must be a positive integer."]]])

(defn etcd-test
      "Given an options map from the command line runner (e.g. :nodes, :ssh,
      :concurrency ...), constructs a test map. Special options:
      	 			:quorum            Whether to use quorum reads"
    [opts]
		(let [quorum (boolean (:quorum opts))]
      (merge tests/noop-test
             opts
             {:pure-:generators true
              :name             (str "etcd q=" quorum)
							:quorum 					quorum
              :os               debian/os
              :db               (db "v3.1.5")
              :client           (Client. nil)
              :nemesis          (nemesis/partition-random-halves)
              :checker          (checker/compose
                                  {:perf  (checker/perf)
                                   :indep (independent/checker
                                            (checker/compose
                                              {:linear   (checker/linearizable {:model     (model/cas-register)
                                                                                :algorithm :linear})
                                               :timeline (timeline/html)}))})
              :generator        (->> (independent/concurrent-generator
                                       10
                                       (range)
                                       (fn [k]
                                           (->> (gen/mix [r w cas])
                                                (gen/stagger (/ (:rate opts)))
                                                (gen/limit (:ops-per-key opts)))))
                                     (gen/nemesis
                                       (cycle [(gen/sleep 5)
                                               {:type :info, :f :start}
                                               (gen/sleep 5)
                                               {:type :info, :f :stop}]))
                                     (gen/time-limit (:time-limit opts)))})))

(defn -main
      "Handles command line arguments. Can either run a test, or a web server for
      browsing results."
      [& args]
      (cli/run! (merge (cli/single-test-cmd {:test-fn etcd-test
																						 :opt-spec cli-opts})
                       (cli/serve-cmd))
                args))