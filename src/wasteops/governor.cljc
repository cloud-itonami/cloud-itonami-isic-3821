(ns wasteops.governor
  "WasteTreatmentOpsGovernor -- the independent compliance layer that
  earns the WasteOpsAdvisor the right to commit. The advisor has no
  notion of whether a non-hazardous-waste treatment/disposal facility
  is actually registered and verified, whether its own proposed
  `:effect` secretly claims a direct actuation instead of a mere
  proposal, or whether it has silently drifted into a permanently
  out-of-scope decision area, so this MUST be a separate system able
  to *reject* a proposal and fall back to HOLD.

  This actor's scope is deliberately narrow -- OPERATIONS COORDINATION
  only (facility-record logging, maintenance scheduling, safety-
  concern flagging, outbound shipment coordination). It NEVER performs
  or authorizes:
    - direct sorting/incineration-equipment control (actuation)
    - environmental-permit-authority decisions (permit issuance,
      disposal-authorization, environmental-compliance determination)

  Three HARD checks, ALL permanent, un-overridable by any human
  approval:

    1. Facility unverified          -- the target waste-treatment/
                                        disposal facility record (the
                                        facility that owns every batch
                                        processed there) must exist AND
                                        be independently confirmed
                                        `:registered?`/`:verified?` in
                                        the store before ANY proposal
                                        for it may commit or even
                                        escalate. Never trusts a
                                        proposal's own claim about the
                                        facility/batch -- re-derived
                                        from the facility's own store
                                        record, the same 'ground truth,
                                        not self-report' discipline
                                        every sibling actor's governor
                                        uses.
    2. Effect not :propose          -- every proposal's `:effect` MUST
                                        be `:propose`. Any other effect
                                        value is, by construction, a
                                        claim to directly actuate/
                                        commit outside governance --
                                        HARD block, not merely
                                        low-confidence.
    3. Scope exclusion              -- ANY proposal (regardless of op)
                                        whose op, rationale, summary,
                                        citations or draft value
                                        touches sorting/incineration-
                                        equipment control or an
                                        environmental-permit-authority
                                        decision is a HARD, PERMANENT
                                        block -- this actor's charter
                                        excludes that territory
                                        structurally, not as a rollout
                                        milestone. Evaluated
                                        UNCONDITIONALLY on every
                                        proposal, the same 'exercise
                                        the failure mode directly'
                                        discipline every sibling
                                        actor's own unconditional-
                                        evaluation checks establish. An
                                        op outside the closed four-op
                                        allowlist is the SAME failure
                                        mode (an advisor proposing
                                        something it was never
                                        authorized to propose) and is
                                        folded into this same check.

  One ESCALATE (SOFT) gate, always requiring human sign-off regardless
  of how clean the proposal otherwise is:

    - `:flag-safety-concern` -- ALWAYS escalates, regardless of
      confidence. `wasteops.phase` independently agrees:
      `:flag-safety-concern` is never a member of any phase's `:auto`
      set either -- two layers, not one.

  Plus the ordinary LLM-confidence-floor escalate every sibling
  actor's governor also applies."
  (:require [kotoba.lang.text :as str]
            [wasteops.store :as store]))

(def confidence-floor 0.6)

(def allowed-ops
  "The closed proposal-op allowlist -- an op outside this set is a
  scope violation by construction (see `scope-exclusion-violations`)."
  #{:log-facility-record :schedule-maintenance
    :flag-safety-concern :coordinate-shipment})

(def always-escalate-ops
  "Ops that ALWAYS require human sign-off, clean or not."
  #{:flag-safety-concern})

(def scope-excluded-terms
  "Case-insensitive substrings that mark a proposal as touching a
  permanently out-of-scope decision area -- direct sorting/
  incineration-equipment control, or an environmental-permit-authority
  decision. Scanned across the proposal's op/summary/rationale/cites/
  value, never trusting the advisor's own framing of its intent."
  ["sorting equipment control" "sorting-equipment control" "選別設備制御"
   "incineration equipment control" "incineration-equipment control" "焼却設備制御"
   "incinerator control" "焼却炉制御"
   "sorting line actuation" "sorting-line actuation" "選別ライン作動"
   "incinerator actuation" "incineration actuation" "焼却炉作動"
   "environmental permit issuance" "environmental-permit issuance" "環境許可発行"
   "disposal permit issuance" "disposal-permit issuance" "処分許可発行"
   "environmental permit authorization" "environmental-permit authorization" "環境許可承認"
   "environmental compliance determination" "environmental-compliance determination" "環境コンプライアンス決定"])

;; ----------------------------- checks -----------------------------

(defn- facility-unverified-violations
  "The target facility must exist AND be independently
  `:registered?`/`:verified?` in the store -- never trust the
  proposal's own `:facility-id` claim without a store lookup."
  [{:keys [facility-id]} st]
  (let [f (store/facility st facility-id)]
    (when-not (and f (:registered? f) (:verified? f))
      [{:rule :facility-unverified
        :detail (str facility-id " は未登録または未検証のfacility -- いかなる提案も進められない")}])))

(defn- effect-not-propose-violations
  "`:effect` must ALWAYS be `:propose` -- any other value is a claim
  to directly actuate/commit outside governance."
  [proposal]
  (when (not= :propose (:effect proposal))
    [{:rule :effect-not-propose
      :detail (str ":effect は :propose のみ許可されるが " (pr-str (:effect proposal)) " が提案された")}]))

(defn- text-blob
  "Flatten every advisor-authored field on a proposal into one
  lower-cased blob the scope-exclusion scan checks."
  [proposal]
  (str/lower (pr-str (select-keys proposal [:op :summary :rationale :cites :value]))))

(defn- scope-exclusion-violations
  "HARD, PERMANENT block: a proposal outside the closed op allowlist,
  or one whose content touches sorting/incineration-equipment-control
  or environmental-permit-authority-decision territory, regardless of
  confidence or how clean every other check is. Evaluated
  UNCONDITIONALLY on every proposal."
  [proposal]
  (let [op (:op proposal)
        blob (text-blob proposal)]
    (cond
      (not (contains? allowed-ops op))
      [{:rule :op-not-allowed
        :detail (str (pr-str op) " は許可された操作(closed allowlist)に含まれない")}]

      (some #(str/includes? blob %) scope-excluded-terms)
      [{:rule :scope-excluded
        :detail "選別/焼却設備の直接制御、または環境許可機関の判断領域に触れる提案は永久に禁止"}])))

(defn check
  "Censors a WasteOpsAdvisor proposal against the governor rules.
  Returns {:ok? bool :violations [..] :confidence c :escalate? bool
  :high-stakes? bool :hard? bool}."
  [request _context proposal store]
  (let [facility-id (or (:facility-id proposal) (:facility-id request))
        hard (into []
                   (concat (facility-unverified-violations {:facility-id facility-id} store)
                           (effect-not-propose-violations proposal)
                           (scope-exclusion-violations proposal)))
        conf (:confidence proposal 0.0)
        low? (< conf confidence-floor)
        always-escalate? (boolean (always-escalate-ops (:op proposal)))
        stakes? (boolean always-escalate?)
        hard? (boolean (seq hard))]
    {:ok?          (and (not hard?) (not low?) (not stakes?))
     :violations   hard
     :confidence   conf
     :hard?        hard?
     :escalate?    (and (not hard?) (or low? stakes?))
     :high-stakes? stakes?}))

(defn hold-fact
  "The audit fact written when a proposal is rejected (HOLD)."
  [request context verdict]
  {:t          :governor-hold
   :op         (:op request)
   :actor      (:actor-id context)
   :facility-id (:facility-id request)
   :disposition :hold
   :basis      (mapv :rule (:violations verdict))
   :violations (:violations verdict)
   :confidence (:confidence verdict)})
