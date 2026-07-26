(ns wasteops.render-html
  "Build-time HTML renderer for `docs/samples/operator-console.html`.

  Closes flagship checklist item 2 (com-junkawasaki/root ADR-2607189300,
  Wave5 rollout ledger): this repo previously had NO demo page and no
  generator at all. This namespace drives the REAL actor stack
  (`wasteops.operation` -> `wasteops.governor` -> `wasteops.store`)
  through a scenario adapted from this repo's own `wasteops.sim` demo
  driver (`clojure -M:dev:run`, confirmed by actually running it before
  this file was written -- unlike `cloud-itonami-isic-851`'s
  `schoolops.sim`, this repo's own sim driver uses facility ids that DO
  match `wasteops.store/demo-data`'s seeded facilities exactly, and
  every disposition it produces (commit / escalate+approve / HARD hold,
  and the exact `:rule` on each hold) matches `wasteops.governor`'s own
  documented checks precisely, so it was safe to reuse rather than
  author from scratch), trimmed to a representative subset (three
  clean phase-3 auto-commits across two facilities, the always-escalate
  safety-concern-flagging lifecycle for one facility -- approved by a
  human facility supervisor -- and three distinct HARD-hold reasons
  that never reach a human) and rendered deterministically -- no
  invented numbers, no timestamps in the page content, byte-identical
  across reruns against the same seed (verified by diffing two
  consecutive runs before shipping).

  Usage: `clojure -M:dev:render-html [out-file]`
  (default `docs/samples/operator-console.html`)."
  (:require [jp-go-dds.skin]
            [clojure.string :as str]
            [wasteops.store :as store]
            [wasteops.advisor :as advisor]
            [wasteops.operation :as op]
            [langgraph.graph :as g]))

;; ----------------------------- harness (unchanged across every repo
;; in this cluster -- do not rewrite, only copy) -----------------------

(def ^:private operator
  {:actor-id "op-1" :actor-role :facility-supervisor :phase 3})

(defn- exec! [actor tid request]
  (g/run* actor {:request request :context operator} {:thread-id tid}))

(defn- approve! [actor tid]
  (g/run* actor {:approval {:status :approved :by "op-1"}}
          {:thread-id tid :resume? true}))

(defn run-demo!
  "Runs a fresh seeded store through a scenario mixing every disposition
  this actor can reach, using ONLY real facility ids from
  `wasteops.store/demo-data` and real op keywords from
  `wasteops.governor/allowed-ops`:

  landfill-north-1 and sorting-facility-2 (both registered AND
  verified) walk the clean phase-3 auto-commit path: `:log-facility-
  record` (intake-volume logging), `:schedule-maintenance` (sorting-
  conveyor maintenance) and `:coordinate-shipment` (recovered-material
  outbound coordination) are all governor-clean, high-confidence, and
  members of phase 3's `:auto` set -- they auto-commit with no human in
  the loop. `landfill-north-1` also flags a `:flag-safety-concern`
  (leachate seepage) -- this op is ALWAYS in `governor/always-escalate-
  ops` and is deliberately absent from every phase's `:auto` set
  (`wasteops.phase`), so it escalates regardless of phase or confidence
  and is approved by a human facility supervisor.

  Then three DISTINCT HARD-hold reasons, none of which ever reach a
  human (a human approver cannot override a HARD violation):
    - incin-facility-3 (seeded `:registered? true :verified? false` --
      Hillside Waste-to-Energy Incineration Facility, recertification
      lapsed): `:log-facility-record` HARD-holds on
      `:facility-unverified` -- the governor independently re-derives
      registration/verification from the facility's own store record,
      never from the proposal's self-reported facility-id.
    - landfill-north-1, advisor attempts direct actuation (`:effect
      :commit` instead of `:propose`): `:coordinate-shipment` HARD-
      holds on `:effect-not-propose` -- every proposal's `:effect` MUST
      be `:propose`; any other value is, by construction, a claim to
      directly actuate/commit outside governance.
    - landfill-north-1, advisor drifts into the permanently-excluded
      incineration-equipment-control / sorting-line-actuation scope
      (`:out-of-scope?` flag on the request, the same failure-mode hook
      `wasteops.sim` exercises): `:schedule-maintenance` HARD-holds on
      `:scope-excluded` -- this actor's charter structurally excludes
      that territory, evaluated unconditionally on every proposal
      regardless of op or confidence.

  Returns the resulting store -- every field `render` below reads is
  real governor/store output, not a hand-typed copy."
  []
  (let [db (store/seed-db)
        actor (op/build db)]

    ;; landfill-north-1: clean intake-volume logging patch -- phase-3
    ;; auto-commit.
    (exec! actor "f1-log" {:op :log-facility-record :facility-id "landfill-north-1"
                            :patch {:intake-tons 42.5 :shift "day"}})

    ;; sorting-facility-2: clean sorting-conveyor maintenance scheduling
    ;; -- phase-3 auto-commit.
    (exec! actor "f2-maint" {:op :schedule-maintenance :facility-id "sorting-facility-2"
                              :patch {:equipment "sorting-conveyor-4" :window "2026-07-22"}})

    ;; sorting-facility-2: clean recovered-material outbound shipment
    ;; coordination -- phase-3 auto-commit.
    (exec! actor "f2-ship" {:op :coordinate-shipment :facility-id "sorting-facility-2"
                             :patch {:material "recovered-cardboard" :destination "recycler-a"}})

    ;; landfill-north-1: environmental safety-concern flag (leachate
    ;; seepage) -- ALWAYS escalates, approved by a human facility
    ;; supervisor.
    (exec! actor "f1-safety" {:op :flag-safety-concern :facility-id "landfill-north-1"
                               :patch {:concern "leachate seepage observed" :confidence 0.95}})
    (approve! actor "f1-safety")

    ;; incin-facility-3: registered but NOT verified (recertification
    ;; lapsed) -> HARD hold on :facility-unverified, never reaches a
    ;; human.
    (exec! actor "f3-log" {:op :log-facility-record :facility-id "incin-facility-3"
                            :patch {:intake-tons 0.1}})

    ;; landfill-north-1: advisor attempts direct actuation (:effect
    ;; :commit instead of :propose) -> HARD hold on
    ;; :effect-not-propose, never reaches a human.
    (let [actor-direct (op/build db {:advisor (reify advisor/Advisor
                                                 (-advise [_ _ req]
                                                   (assoc (advisor/infer db req) :effect :commit)))})]
      (exec! actor-direct "f1-effect" {:op :coordinate-shipment :facility-id "landfill-north-1"
                                        :patch {:material "recovered-metal"}}))

    ;; landfill-north-1: advisor drifts into incineration-equipment-
    ;; control / sorting-line-actuation scope -> HARD hold on
    ;; :scope-excluded, permanent, never reaches a human.
    (exec! actor "f1-scope" {:op :schedule-maintenance :facility-id "landfill-north-1"
                              :out-of-scope? true :patch {}})

    db))

;; ----------------------------- rendering -----------------------------

(defn- esc [v]
  (-> (str v)
      (str/replace "&" "&amp;")
      (str/replace "<" "&lt;")
      (str/replace ">" "&gt;")))

(defn- last-fact-for [ledger facility-id]
  (last (filter #(= (:facility-id %) facility-id) ledger)))

(defn- status-cell [ledger facility-id]
  (let [f (last-fact-for ledger facility-id)]
    (cond
      (nil? f) "<span class=\"muted\">no activity</span>"
      (= :committed (:t f)) "<span class=\"ok\">committed</span>"
      (= :approval-granted (:t f)) "<span class=\"ok\">approved &amp; committed</span>"
      (= :governor-hold (:t f))
      (let [rule (-> f :violations first :rule)]
        (str "<span class=\"critical\">HARD hold &middot; " (esc (name (or rule :unknown))) "</span>"))
      (= :approval-requested (:t f)) "<span class=\"warn\">awaiting approval</span>"
      :else "<span class=\"muted\">in progress</span>")))

(defn- facility-row [ledger {:keys [facility-id name registered? verified?]}]
  (format "        <tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>"
          (esc facility-id) (esc name)
          (if registered? "<span class=\"ok\">registered</span>" "<span class=\"critical\">unregistered</span>")
          (if verified? "<span class=\"ok\">verified</span>" "<span class=\"critical\">unverified</span>")
          (status-cell ledger facility-id)))

(defn- ledger-row [{:keys [t op facility-id disposition basis]}]
  (format "        <tr><td>%s</td><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name t)) (esc (name (or op :n-a))) (esc facility-id)
          (esc (or (some->> basis (map #(if (keyword? %) (name %) %)) (str/join ", "))
                    (some-> disposition name) ""))))

(defn- coordination-row [{:keys [op facility-id value payload]}]
  (format "        <tr><td><code>%s</code></td><td>%s</td><td>%s</td></tr>"
          (esc (name op)) (esc facility-id)
          (esc (pr-str (or payload value {})))))

(def ^:private action-gate-rows
  ;; Static description of this actor's own op contract
  ;; (`wasteops.governor`/`wasteops.phase`) -- documentation of fixed
  ;; behavior, not runtime telemetry, so it is legitimately
  ;; hand-described rather than derived from a live run.
  ["        <tr><td><code>:log-facility-record</code></td><td><span class=\"ok\">phase-3 auto-commit when clean -- intake-volume/sorting-yield/disposal-method data logging</span></td></tr>"
   "        <tr><td><code>:schedule-maintenance</code></td><td><span class=\"ok\">phase-3 auto-commit when clean &middot; independently re-scanned for incineration/sorting-equipment-control scope drift on every proposal</span></td></tr>"
   "        <tr><td><code>:coordinate-shipment</code></td><td><span class=\"ok\">phase-3 auto-commit when clean &middot; :effect independently re-checked as :propose on every proposal, never trusted</span></td></tr>"
   "        <tr><td><code>:flag-safety-concern</code></td><td><span class=\"warn\">ALWAYS human approval &middot; never a member of any phase's :auto set -- a safety concern always needs a human to look at it</span></td></tr>"])

(defn render
  "Renders the full operator-console.html document from a store `db`
  that has already run `run-demo!` (or any other real scenario)."
  [db]
  (let [ledger (vec (store/ledger db))
        facilities (store/all-facilities db)
        facility-rows (str/join "\n" (map (partial facility-row ledger) facilities))
        ledger-rows (str/join "\n" (map ledger-row ledger))
        coordination-rows (str/join "\n" (map coordination-row (store/coordination-log db)))]
    (str
     "<html><head><meta charset=\"utf-8\"><title>cloud-itonami-isic-3821 &middot; treatment and disposal of non-hazardous waste</title><style>"
   (jp-go-dds.skin/dds+skin)
   "</style></head><body>\n"
     "<header class=\"bar\">\n"
     "  <h1>Treatment and disposal of non-hazardous waste (ISIC 3821) — Operator Console</h1>\n"
     "  <span class=\"badge\">read-only sample · governor-gated · sorting/incineration-equipment control and environmental-permit decisions always out of scope</span>\n"
     "</header>\n"
     "<main>\n"
     "  <section class=\"card\">\n"
     "    <h2>Facilities</h2>\n"
     "    <p class=\"muted\">Demo snapshot — build-time-generated from <code>wasteops.store</code> via <code>wasteops.render-html</code> (<code>clojure -M:dev:render-html</code>), regenerated nightly.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Facility</th><th>Name</th><th>Registration</th><th>Verification</th><th>Last op status</th></tr></thead>\n"
     "      <tbody>\n"
     facility-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Committed coordination records</h2>\n"
     "    <p class=\"muted\">Facility-record logging, maintenance scheduling and outbound shipment-coordination proposals this actor actually committed to the SSoT this run.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Facility</th><th>Payload</th></tr></thead>\n"
     "      <tbody>\n"
     coordination-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Action gate (WasteTreatmentOpsGovernor)</h2>\n"
     "    <p class=\"muted\">HARD holds cannot be overridden by a human approver. Facility registration/verification, proposal :effect and sorting/incineration-equipment-control &amp; environmental-permit-authority scope exclusions are independently re-checked on every proposal, never trusted from the advisor's own claim; a safety concern always needs a human facility supervisor to look at it, at every rollout phase.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Op</th><th>Gate</th></tr></thead>\n"
     "      <tbody>\n"
     (str/join "\n" action-gate-rows) "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "  <section class=\"card\">\n"
     "    <h2>Audit ledger (this run)</h2>\n"
     "    <p class=\"muted\">Append-only decision-fact log — every proposal, hold and commit this scenario produced.</p>\n"
     "    <table>\n"
     "      <thead><tr><th>Fact</th><th>Op</th><th>Facility</th><th>Basis</th></tr></thead>\n"
     "      <tbody>\n"
     ledger-rows "\n"
     "      </tbody>\n"
     "    </table>\n"
     "  </section>\n"
     "</main>\n"
     "</body></html>\n")))

(defn -main [& args]
  (let [out (or (first args) "docs/samples/operator-console.html")
        db (run-demo!)
        html (render db)]
    (spit out html)
    (println "wrote" out "(" (count (store/ledger db)) "ledger facts,"
             (count (store/coordination-log db)) "committed coordination records )")))
