(ns wasteops.store
  "SSoT for the ISIC-3821 non-hazardous-waste treatment-and-disposal
  OPERATIONS-COORDINATION actor, behind a `Store` protocol so the
  backend is a swap, not a rewrite -- the same seam every
  `cloud-itonami-isic-*` actor in this fleet uses.

  This actor coordinates the BACK OFFICE of a non-hazardous-waste
  treatment/disposal facility (landfill / sorting-and-materials-
  recovery / composting / incineration-without-hazmat): intake-
  volume/sorting-yield/disposal-method system-record logging,
  sorting/incineration/composting-equipment maintenance scheduling,
  environmental-contamination/fire-hazard/emissions-exceedance
  safety-concern flagging, and outbound recovered-material/residual-
  waste shipment coordination. It never touches sorting/incineration-
  equipment control (direct actuation) or any environmental-permit-
  authority decision -- see `wasteops.governor`'s
  `scope-exclusion-violations`, a HARD, permanent, un-overridable
  block.

  `MemStore` -- atom of EDN. The deterministic default for dev/tests/
  demo (no deps). A `facilities` directory keyed by `:facility-id`
  STRING (never a keyword -- keyed consistently on the string, the
  same 'string key, always' discipline every sibling actor's store
  uses, precisely to avoid the coalops/isic-0510 scaffold's prior
  keyword/string key mismatch that silently masked itself as HARD
  facility-unverified holds across many assertions).

  A registered/verified waste-treatment-facility record (the facility
  under which every intake batch it processes is tracked) must exist
  before ANY proposal for it may ever commit or escalate --
  `wasteops.governor`'s `facility-unverified-violations` re-derives
  this from the facility's own `:registered?`/`:verified?` fields,
  never from a proposal's own self-reported facility/batch claim, the
  SAME 'ground truth, not self-report' discipline every sibling
  actor's own governor uses.

  The ledger stays append-only: which facility a proposal targeted,
  which operation, on what basis, committed/held/escalated and
  approved by whom is always a query over an immutable log.")

(defprotocol Store
  (facility [s facility-id] "Registered non-hazardous-waste treatment/
    disposal facility record, or nil.
    Facility map: {:facility-id .. :name .. :registered? bool :verified? bool}.")
  (all-facilities [s])
  (ledger [s] "the append-only immutable decision-fact log")
  (coordination-log [s] "the append-only committed coordination-proposal history")
  (commit-record! [s record] "apply a committed proposal's record to the SSoT")
  (append-ledger! [s fact] "append one immutable decision fact")
  (with-facilities [s facilities] "replace/seed the facility directory (map facility-id->facility)"))

;; ----------------------------- demo data -----------------------------

(defn demo-data
  "A small, self-contained facility directory covering both the happy
  path and the governor's own hard checks, so the actor + tests run
  offline."
  []
  {:facilities
   {"landfill-north-1" {:facility-id "landfill-north-1" :name "North Ridge Sanitary Landfill"
                         :registered? true :verified? true}
    "sorting-facility-2" {:facility-id "sorting-facility-2" :name "Eastside Materials Recovery & Sorting Facility"
                           :registered? true :verified? true}
    "incin-facility-3" {:facility-id "incin-facility-3" :name "Hillside Waste-to-Energy Incineration Facility (recertification lapsed)"
                         :registered? true :verified? false}}})

;; ----------------------------- MemStore (default) -----------------------------

(defrecord MemStore [a]
  Store
  (facility [_ facility-id] (get-in @a [:facilities facility-id]))
  (all-facilities [_] (sort-by :facility-id (vals (:facilities @a))))
  (ledger [_] (:ledger @a))
  (coordination-log [_] (:coordination-log @a))
  (commit-record! [_ record]
    (swap! a update :coordination-log conj record)
    record)
  (append-ledger! [_ fact] (swap! a update :ledger conj fact) fact)
  (with-facilities [s facilities] (when (seq facilities) (swap! a assoc :facilities facilities)) s))

(defn seed-db
  "A MemStore seeded with the demo facility directory. The
  deterministic default."
  []
  (->MemStore (atom (assoc (demo-data) :ledger [] :coordination-log []))))

(defn mem-store
  "A MemStore seeded with an explicit `facilities` map (facility-id
  string -> facility map) -- the primary test/dev entry point.
  `facilities` may be empty (an unregistered-everywhere store)."
  [facilities]
  (->MemStore (atom {:facilities (or facilities {}) :ledger [] :coordination-log []})))
