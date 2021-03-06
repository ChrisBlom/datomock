(ns datomock.impl
  (:require [datomic.api :as d]
            [datomic.promise])
  (:import (datomic Log Database Connection)
           (java.util UUID)
           (java.util.concurrent BlockingQueue ExecutionException LinkedBlockingDeque)))

(defn ^Database make-empty-db []
  (let [uri (str "datomic:mem://" "datomock-" (UUID/randomUUID))
        db (do (d/create-database uri)
               (d/db (d/connect uri)))]
    (d/delete-database uri)
    db))

(defrecord MockConnState [db logVec])

(defn log-item
  [tx-res]
  {:t (d/basis-t (:db-after tx-res))
   :data (:tx-data tx-res)})

(defn log-tail [logVec startT endT]
  (filter (fn [{:as log-item, :keys [t]}]
            (and
              (or (nil? startT) (<= startT t))
              (or (nil? endT) (< t endT))
              )) logVec))

(defrecord ForkedLog [rootLog forkT logVec]
  Log
  (txRange [_ startT endT]
    (concat
      (when rootLog
        (seq (d/tx-range rootLog startT (if (nil? endT) forkT (min forkT endT)))))
      (log-tail logVec startT endT)
      )))

(defrecord MockConnection
  [a_state, forkT, parentLog, a_txq]

  Connection
  (db [_] (:db @a_state))
  (transact [_ tx-data] (doto (datomic.promise/settable-future)
                          (deliver (let [tx-res
                                         (loop []
                                           (let [old-val @a_state
                                                 db (:db old-val)
                                                 tx-res (try (d/with db tx-data)
                                                             (catch Throwable err
                                                               (throw (ExecutionException. err))))
                                                 new-val  (->MockConnState
                                                            (:db-after tx-res)
                                                            (conj (:logVec old-val) (log-item tx-res)))]
                                             (if (compare-and-set! a_state old-val new-val)
                                               tx-res
                                               (recur))
                                             ))]
                                     (when-let [^BlockingQueue txq @a_txq]
                                       (.add ^BlockingQueue txq tx-res))
                                     tx-res))
                          ))
  (transactAsync [this tx-data] (.transact this tx-data))

  (requestIndex [_] true)
  (release [_] (do nil))
  (gcStorage [_ olderThan] (do nil))

  (sync [this] (doto (datomic.promise/settable-future)
                 (deliver (.db this))))
  (sync [this t] (.sync this))
  (syncExcise [this t] (.sync this))
  (syncIndex [this t] (.sync this))
  (syncSchema [this t] (.sync this))

  (txReportQueue [_]
    (or @a_txq
        (swap! a_txq #(or % (LinkedBlockingDeque.)))))
  (removeTxReportQueue [_]
    (reset! a_txq nil))

  (log [_] (->ForkedLog parentLog forkT (:logVec @a_state)))
  )

(defn mock-conn*
  [^Database db, ^Log parent-log]
  (->MockConnection (atom (->MockConnState db [])) (d/next-t db) parent-log (atom nil)))
