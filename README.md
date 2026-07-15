# cloud-itonami-isic-3821

Open Business Blueprint for **ISIC Rev.4 3821**: Treatment and disposal
of non-hazardous waste — an ISIC Wave (waste-management) operations-
coordination actor per ADR-2607121000, mirroring
`cloud-itonami-isic-3700`'s (Sewerage) back-office regulated-utility
coordination discipline.

**Maturity: `:implemented`** — WasteOpsAdvisor ⊣ WasteTreatmentOpsGovernor as
a langgraph-clj StateGraph (`intake → advise → govern → decide →
commit/hold`, human-approval interrupt). All source `.cljc` (portable
to JVM / ClojureScript / GraalVM), no JVM-only interop.

## CRITICAL: Scope Exclusions

This actor **DOES NOT** and **NEVER WILL**:

- **Direct sorting/incineration-equipment control** — actuating, opening/closing, or otherwise directly operating sorting-line or incineration equipment
- **Environmental-permit-authority decisions** — environmental-permit issuance, disposal-permit issuance, or environmental-compliance determination

This actor **only** coordinates back-office operations for a
non-hazardous-waste treatment/disposal facility (landfill / sorting-
and-materials-recovery / composting / incineration-without-hazmat):
facility-record logging (intake-volume/sorting-yield/disposal-method
data), maintenance scheduling (sorting/incineration/composting-
equipment), safety-concern flagging (environmental-contamination/
fire-hazard/emissions-exceedance, always routed to a human), and
outbound recovered-material/residual-waste shipment coordination.
Every proposal the advisor drafts carries `:effect :propose` — never a
direct actuation — and `wasteops.governor` independently re-scans
every proposal's content for the excluded scope areas above,
regardless of op or confidence.

## Operations

Closed proposal-op allowlist (`wasteops.governor/allowed-ops`), all
`:effect :propose`:

- `:log-facility-record` — intake-volume/sorting-yield/disposal-method data logging
- `:schedule-maintenance` — sorting/incineration/composting-equipment maintenance scheduling proposal
- `:flag-safety-concern` — surface an environmental-contamination/fire-hazard/emissions-exceedance concern — **ALWAYS escalates**
- `:coordinate-shipment` — outbound recovered-material/residual-waste shipment coordination

**HARD invariants** (always `:hold`, never human-overridable):

1. **Facility unverified** — the target waste-treatment/disposal
   facility record (which owns every batch it processes) must exist
   AND be independently confirmed `:registered?`/`:verified?` in the
   store before any proposal for it may commit or even escalate.
   Never trusts a proposal's own claim about the facility/batch —
   re-derived from the facility's own store record, the same "ground
   truth, not self-report" discipline every sibling actor's governor
   uses.
2. **Effect not `:propose`** — any proposal whose `:effect` is not
   `:propose` is, by construction, a claim to directly actuate outside
   governance.
3. **Scope exclusion** — any proposal (regardless of op) outside the
   closed allowlist, or whose rationale/summary/citations/value
   touches sorting/incineration-equipment control or environmental-
   permit-authority-decision territory, is a permanent, un-overridable
   block. Evaluated unconditionally on every proposal.

**ESCALATE** (always human sign-off, when the governor is otherwise clean):

- `:flag-safety-concern` — always, regardless of confidence.
- Low advisor confidence (`< 0.6`).

## Rollout phases (`wasteops.phase`)

Phase 0 (read-only) → 1 (facility-record logging, approval-gated) → 2
(adds maintenance scheduling + shipment coordination, approval-gated)
→ 3 (supervised auto: facility-record/maintenance/shipment-
coordination may auto-commit when governor-clean and confident).
`:flag-safety-concern` is deliberately absent from every phase's
`:auto` set — a permanent structural fact, not a rollout milestone
still to come — matching `wasteops.governor`'s own
`always-escalate-ops` independently.

## Development

```bash
clojure -M:test   # run the full suite
clojure -M:run    # walk the demo scenarios (wasteops.sim)
clojure -M:lint    # clj-kondo
```

AGPL-3.0-or-later, forkable by any qualified operator. Part of cloud-itonami.
