(ns wasteops.sim
  "Demo driver -- `clojure -M:run`. Walks a clean facility-record
  logging request through intake -> advise -> govern -> decide ->
  approval -> commit at phase 1 (assisted-logging, always approval),
  then re-runs the same op at phase 3 (supervised-auto, clean + high
  confidence -> auto-commit), then a maintenance-scheduling request
  and a shipment-coordination request (also auto-commit clean at
  phase 3), then a safety-concern flag (ALWAYS escalates, at any
  phase -- approve, then commit), then HARD-hold scenarios: an
  unregistered facility, a facility registered but not yet verified, a
  proposal whose own `:effect` is not `:propose`, and a proposal that
  has drifted into the permanently-excluded sorting/incineration-
  equipment-control scope."
  (:require [langgraph.graph :as g]
            [wasteops.advisor :as advisor]
            [wasteops.store :as store]
            [wasteops.operation :as op]))

(defn- exec-op [actor tid request context]
  (g/run* actor {:request request :context context} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "waste-facility-supervisor-1"}} {:thread-id tid :resume? true}))

(defn -main [& _]
  (let [db (store/seed-db)
        operator-phase-1 {:actor-id "op-1" :actor-role :facility-supervisor :phase 1}
        operator-phase-3 {:actor-id "op-1" :actor-role :facility-supervisor :phase 3}
        actor (op/build db)]

    (println "== log-facility-record landfill-north-1 (phase 1, escalates -- human approves) ==")
    (println (exec-op actor "t1" {:op :log-facility-record :facility-id "landfill-north-1"
                                  :patch {:intake-tons 42.5 :shift "day"}} operator-phase-1))
    (println (approve! actor "t1"))

    (println "== log-facility-record landfill-north-1 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t2" {:op :log-facility-record :facility-id "landfill-north-1"
                                  :patch {:intake-tons 45.1 :shift "night"}} operator-phase-3))

    (println "== schedule-maintenance sorting-facility-2 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t3" {:op :schedule-maintenance :facility-id "sorting-facility-2"
                                  :patch {:equipment "sorting-conveyor-4" :window "2026-07-22"}} operator-phase-3))

    (println "== coordinate-shipment sorting-facility-2 (phase 3, clean -- auto-commits) ==")
    (println (exec-op actor "t4" {:op :coordinate-shipment :facility-id "sorting-facility-2"
                                  :patch {:material "recovered-cardboard" :destination "recycler-a"}} operator-phase-3))

    (println "== flag-safety-concern landfill-north-1 (ALWAYS escalates, even at phase 3) ==")
    (let [r (exec-op actor "t5" {:op :flag-safety-concern :facility-id "landfill-north-1"
                                 :patch {:concern "leachate seepage observed" :confidence 0.95}} operator-phase-3)]
      (println r)
      (println "-- human facility supervisor reviews & approves --")
      (println (approve! actor "t5")))

    (println "== log-facility-record landfill-north-9 (unregistered facility -> HARD hold) ==")
    (println (exec-op actor "t6" {:op :log-facility-record :facility-id "landfill-north-9"
                                  :patch {:intake-tons 0.1}} operator-phase-3))

    (println "== log-facility-record incin-facility-3 (registered but unverified -> HARD hold) ==")
    (println (exec-op actor "t7" {:op :log-facility-record :facility-id "incin-facility-3"
                                  :patch {:intake-tons 0.1}} operator-phase-3))

    (println "== coordinate-shipment landfill-north-1, advisor attempts direct actuation (:effect :commit) -> HARD hold ==")
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                (-advise [_ _ req]
                                                  (assoc (advisor/infer db req) :effect :commit)))})]
      (println (exec-op actor-direct "t8" {:op :coordinate-shipment :facility-id "landfill-north-1"
                                           :patch {:material "recovered-metal"}} operator-phase-3)))

    (println "== schedule-maintenance landfill-north-1, advisor drifts into incineration-equipment-control scope -> HARD hold, permanent ==")
    (println (exec-op actor "t9" {:op :schedule-maintenance :facility-id "landfill-north-1"
                                   :out-of-scope? true
                                   :patch {}} operator-phase-3))

    (println "== audit ledger ==")
    (doseq [f (store/ledger db)] (println f))

    (println "== committed coordination log ==")
    (doseq [r (store/coordination-log db)] (println r))))
