(ns onyx.windowing.basic-windowing-crash-test
  (:require [clojure.core.async :refer [chan >!! <!! close! sliding-buffer]]
            [clojure.test :refer [deftest is]]
            [taoensso.timbre :refer [info error warn trace fatal] :as timbre]
            [onyx.plugin.core-async :refer [take-segments!]]
            [onyx.state.state-extensions :as state-extensions]
            [onyx.test-helper :refer [load-config with-test-env add-test-env-peers!]]
            [onyx.api]))

;;; IMPORTANT - since this crashed before task, it'll never play a message twice
;;; Therefore this is not a good test of message deduping
;;; It also only tests crashes at certain points of the process. 
;;; For example, in this test, messages are likely already acked since the crash
;;; delay is rather long

(def input
  ;; ensure some duplicates are around and interdispersed
  [{:id 1  :age 21 :event-time #inst "2015-09-13T03:00:00.829-00:00"}
   {:id 2  :age 12 :event-time #inst "2015-09-13T03:04:00.829-00:00"}
   {:id 2  :age 12 :event-time #inst "2015-09-13T03:04:00.829-00:00"}
   {:id 2  :age 12 :event-time #inst "2015-09-13T03:04:00.829-00:00"}
   {:id 2  :age 12 :event-time #inst "2015-09-13T03:04:00.829-00:00"}
   {:id 2  :age 12 :event-time #inst "2015-09-13T03:04:00.829-00:00"}
   {:id 2  :age 12 :event-time #inst "2015-09-13T03:04:00.829-00:00"}
   {:id 2  :age 12 :event-time #inst "2015-09-13T03:04:00.829-00:00"}
   {:id 2  :age 12 :event-time #inst "2015-09-13T03:04:00.829-00:00"}
   {:id 2  :age 12 :event-time #inst "2015-09-13T03:04:00.829-00:00"}
   {:id 2  :age 12 :event-time #inst "2015-09-13T03:04:00.829-00:00"}
   ; Exact dupe
   {:id 2  :age 12 :event-time #inst "2015-09-13T03:04:00.829-00:00"}
   ; Exact dupe
   {:id 2  :age 12 :event-time #inst "2015-09-13T03:04:00.829-00:00"}
   ; Exact dupe
   {:id 2  :age 12 :event-time #inst "2015-09-13T03:04:00.829-00:00"}
   {:id 3  :age 3  :event-time #inst "2015-09-13T03:05:00.829-00:00"}
   {:id 4  :age 64 :event-time #inst "2015-09-13T03:06:00.829-00:00"}
   {:id 5  :age 53 :event-time #inst "2015-09-13T03:07:00.829-00:00"}
   {:id 4  :age 64 :event-time #inst "2015-09-13T03:06:00.829-00:00"}
   {:id 6  :age 52 :event-time #inst "2015-09-13T03:08:00.829-00:00"}
   {:id 7  :age 24 :event-time #inst "2015-09-13T03:09:00.829-00:00"}
   {:id 8  :age 35 :event-time #inst "2015-09-13T03:15:00.829-00:00"}
   {:id 9  :age 49 :event-time #inst "2015-09-13T03:25:00.829-00:00"}
   {:id 10 :age 37 :event-time #inst "2015-09-13T03:45:00.829-00:00"}
   {:id 11 :age 15 :event-time #inst "2015-09-13T03:03:00.829-00:00"}
   ; Exact dupe
   {:id 10 :age 37 :event-time #inst "2015-09-13T03:45:00.829-00:00"}
   {:id 12 :age 22 :event-time #inst "2015-09-13T03:56:00.829-00:00"}
   ; Exact dupe
   {:id 12 :age 22 :event-time #inst "2015-09-13T03:56:00.829-00:00"}
   {:id 13 :age 83 :event-time #inst "2015-09-13T03:59:00.829-00:00"}
   {:id 14 :age 60 :event-time #inst "2015-09-13T03:32:00.829-00:00"}
   {:id 15 :age 35 :event-time #inst "2015-09-13T03:16:00.829-00:00"}
   ;; Ensure some duplicate ages are counted, with different ids
   {:id 16  :age 12 :event-time #inst "2015-09-13T03:04:00.829-00:00"}
   {:id 17  :age 52 :event-time #inst "2015-09-13T03:08:00.829-00:00"}
   {:id 18  :age 53 :event-time #inst "2015-09-13T03:07:00.829-00:00"}
   {:id 19  :age 3 :event-time #inst "2015-09-13T03:05:00.829-00:00"}])

(defn output->final-counts [window-counts]
  (let [grouped (group-by (juxt first second) window-counts)]
    (set (map (fn get-latest [[[bounds k] all]]
                [bounds k (last (sort (map last all)))])
              grouped)))) 

(def expected-windows
  #{[[1442115000000 1442115299999] 60 1]
    [[1442113200000 1442113499999] 21 1]
    [[1442113500000 1442113799999] 64 1]
    [[1442113500000 1442113799999] 3 2]
    [[1442116500000 1442116799999] 83 1]
    [[1442115900000 1442116199999] 37 1]
    [[1442113500000 1442113799999] 52 2]
    [[1442114100000 1442114399999] 35 2]
    [[1442113500000 1442113799999] 53 2]
    [[1442113200000 1442113499999] 15 1]
    [[1442114700000 1442114999999] 49 1]
    [[1442113500000 1442113799999] 24 1]
    [[1442113200000 1442113499999] 12 2]
    [[1442116500000 1442116799999] 22 1]})

(defn restartable? [event lifecycle lifecycle-name e]
  :restart)

(defrecord MonitoringStats
  [zookeeper-write-log-entry
   zookeeper-read-log-entry
   zookeeper-write-catalog
   zookeeper-write-workflow
   zookeeper-write-flow-conditions
   zookeeper-write-lifecycles
   zookeeper-write-task
   zookeeper-write-chunk
   zookeeper-write-job-scheduler
   zookeeper-write-messaging
   zookeeper-force-write-chunk
   zookeeper-write-origin
   zookeeper-read-catalog
   zookeeper-read-workflow
   zookeeper-read-flow-conditions
   zookeeper-read-lifecycles
   zookeeper-read-task
   zookeeper-read-chunk
   zookeeper-read-origin
   zookeeper-read-job-scheduler
   zookeeper-read-messaging
   zookeeper-gc-log-entry
   window-log-write-entry
   window-log-playback
   window-log-compaction
   peer-ack-segments
   peer-retry-segment
   peer-try-complete-job
   peer-strip-sentinel
   peer-complete-message
   peer-gc-peer-link
   peer-backpressure-on
   group-backpressure-off
   group-prepare-join
   group-notify-join
   peer-accept-join])

(def test-state (atom nil))

(def batch-num (atom nil))

(def compaction-finished? (atom nil))

(def playback-occurred? (atom nil))

(def in-chan (atom nil))

(def out-chan (atom nil))

(defn update-atom! [event window trigger {:keys [lower-bound upper-bound group-key event-type] :as opts} extent-state]
  (when-not (= :job-completed event-type)
    (swap! test-state conj [[lower-bound upper-bound] group-key extent-state])))

(def identity-crash
  {:lifecycle/before-batch 
   (fn [event lifecycle]
     (case (swap! batch-num inc)
       ;;2 
       ;;(do (state-extensions/compact-log (:onyx.core/state-log event) event @(:onyx.core/window-state event))
       ;;    (Thread/sleep 7000))
       ;; compactions happen at a check in between log entries writing, so we need to wait for two cycles
       ;; before crashing
       4
       (do
         ;; give the peer a bit of time to write the chunks out and ack the batches,
         ;; since we want to ensure that the batches aren't re-read on restart
         (Thread/sleep 7000)
         (throw (ex-info "Restart me!" {})))
       {}))})

(defn inject-in-ch [event lifecycle]
  {:core.async/chan @in-chan})

(defn inject-out-ch [event lifecycle]
  {:core.async/chan @out-chan})

(def in-calls
  {:lifecycle/before-task-start inject-in-ch})

(def identity-calls
  {:lifecycle/handle-exception restartable?})

(def out-calls
  {:lifecycle/before-task-start inject-out-ch})

(deftest fault-tolerance-fixed-windows-segment-trigger
  (do
    (reset! test-state [])
    (reset! batch-num 0)

    (reset! compaction-finished? false)
    (reset! playback-occurred? false)

    (reset! in-chan (chan (inc (count input))))
    (reset! out-chan (chan (sliding-buffer (inc (count input)))))

    (let [id (java.util.UUID/randomUUID)
          config (load-config)
          env-config (assoc (:env-config config) :onyx/tenancy-id id)
          batch-size 5
          workflow
          [[:in :identity] [:identity :out]]
          catalog
          [{:onyx/name :in
            :onyx/plugin :onyx.plugin.core-async/input
            :onyx/type :input
            :onyx/medium :core.async
            :onyx/pending-timeout 30000
            ;; make the time between batches very long
            ;; because we don't want too many empty batches
            ;; between which we'll get crashes
            :onyx/batch-timeout 20000
            :onyx/batch-size batch-size
            :onyx/max-peers 1
            :onyx/doc "Reads segments from a core.async channel"}

           {:onyx/name :identity
            :onyx/fn :clojure.core/identity
            :onyx/group-by-key :age ;; irrelevant because only one peer
            :onyx/uniqueness-key :id
            :onyx/min-peers 1
            :onyx/max-peers 1
            :onyx/flux-policy :recover ;; should only recover if possible?
            :onyx/type :function
            :onyx/batch-size batch-size}

           {:onyx/name :out
            :onyx/plugin :onyx.plugin.core-async/output
            :onyx/type :output
            :onyx/medium :core.async
            :onyx/max-peers 1
            :onyx/batch-size batch-size
            :onyx/doc "Writes segments to a core.async channel"}]

          windows
          [{:window/id :collect-segments
            :window/task :identity
            :window/type :fixed
            :window/aggregation :onyx.windowing.aggregation/count
            :window/window-key :event-time
            :window/range [5 :minutes]}]

          triggers
          [{:trigger/window-id :collect-segments
            :trigger/refinement :onyx.refinements/accumulating
            :trigger/on :onyx.triggers/segment
            ;; Align threshhold with batch-size since we'll be restarting
            :trigger/threshold [1 :elements]
            :trigger/sync ::update-atom!}]

          lifecycles
          [{:lifecycle/task :in
            :lifecycle/calls ::in-calls}
           {:lifecycle/task :in
            :lifecycle/calls :onyx.plugin.core-async/reader-calls}
           {:lifecycle/task :identity
            :lifecycle/calls ::identity-calls}
           {:lifecycle/task :out
            :lifecycle/calls ::out-calls}
           {:lifecycle/task :identity
            :lifecycle/calls ::identity-crash}
           {:lifecycle/task :out
            :lifecycle/calls :onyx.plugin.core-async/writer-calls}]

          stats-holder (let [stats (map->MonitoringStats {})]
                         (reduce (fn [st k] (assoc st k (atom [])))
                                 stats
                                 (keys stats)))

          event-fn (fn print-monitoring-event [_ event]
                     (let [stats-value (swap! (get stats-holder (:event event)) conj event)]
                       (case (:event event)
                         :window-log-compaction (reset! compaction-finished? true)
                         :window-log-playback (reset! playback-occurred? true)
                         nil)))

          monitoring-config {:monitoring :custom
                             :zookeeper-read-catalog event-fn
                             :window-log-compaction event-fn
                             :window-log-playback event-fn
                             :window-log-write-entry event-fn}

          peer-config (assoc (:peer-config config)
                        :onyx/tenancy-id id
                        ;; Write for every batch to ensure compaction occurs
                        :onyx.bookkeeper/write-batch-size 1
                        :monitoring-config monitoring-config)]
      (with-test-env [test-env [6 env-config peer-config]]
        (onyx.api/submit-job peer-config
                             {:catalog catalog
                              :workflow workflow
                              :lifecycles lifecycles
                              :windows windows
                              :triggers triggers
                              :task-scheduler :onyx.task-scheduler/balanced})
        (doseq [i input]
          (>!! @in-chan i))
        (>!! @in-chan :done)

        (close! @in-chan)

        (let [results (take-segments! @out-chan)]
          (is (= :done (last results)))
          ;; FIXME: Re-enable this test.
          #_(is (true? @compaction-finished?))
          (is (true? @playback-occurred?))
          (is (= expected-windows (output->final-counts @test-state))))))))
