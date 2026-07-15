# Contributing to cloud-itonami-isic-3821

Contributions should preserve the actor's scope: back-office coordination only,
with CRITICAL exclusions of direct sorting/incineration-equipment control and
environmental-permit-authority decisions (see README.md).

- All code must be .cljc (portable Clojure, no JVM-only constructs).
- Tests must pass: clojure -M:test
- Commit messages should link to relevant ADRs or issues.

**This actor does NOT:**
- Direct sorting/incineration-equipment control (actuation).
- Environmental-permit-authority decisions (environmental-permit issuance,
  disposal-permit issuance, environmental-compliance determination).

Contributions that cross these boundaries will be rejected.
