# Security Policy

Where is a real-time location sharing app with end-to-end encryption. We take
security and privacy reports seriously and appreciate responsible disclosure.

## Project status

Where is an open-source, non-commercial project maintained by volunteers in
their spare time. There is no dedicated security team, no funding, and no
formal SLA. We will do our best to triage and address reports, but response
and remediation times depend on maintainer availability. Please calibrate
expectations accordingly.

## Reporting a vulnerability

Please report vulnerabilities **privately**, not in a public issue.

Preferred: open a private security advisory via
[GitHub's "Report a vulnerability" flow](https://github.com/danmarg/where/security/advisories/new).
This keeps the report confidential and lets us collaborate on a fix and
coordinated disclosure.

Include, where possible:

- A description of the issue and its impact
- Steps to reproduce or a proof of concept
- The commit hash or release the report applies to
- Any suggested mitigation

We will try to acknowledge new reports promptly, but cannot commit to a
fixed turnaround (see "Project status" above). Severe issues affecting
confidentiality of user location data or breaking E2EE guarantees are
prioritized over everything else.

## Scope and threat model

The cryptographic protocol and its **threat model** are documented in
[`docs/e2ee-location-sync.md`](docs/e2ee-location-sync.md). That document
is the authoritative definition of what Where attempts to defend against
and what it does not. Please read it before filing a report.

**In scope** — we prioritize, in this order:

1. Attacks that violate the documented threat model (e.g. breaking
   confidentiality, integrity, forward secrecy, or post-compromise
   security of established sessions; deviations of the implementation
   from the spec).
2. Vulnerabilities in the server, clients, CLI, or release tooling
   (`server/`, `shared/`, `android/`, `ios/`, `cli/`, GitHub Actions
   workflows, fastlane lanes) that compromise users or their data.
3. Unsafe use of third-party dependencies on our part.

**Out of scope** — we will generally not act on:

- **Attacks the threat model already accepts.** The protocol spec
  enumerates these explicitly. The most prominent is Trust-On-First-Use
  during initial pairing: an active attacker who can MITM the
  out-of-band invite channel can substitute keys, and Where does not
  defend against this by design. Reports that re-describe accepted
  trade-offs without new information will be closed.
- Vulnerabilities in third-party dependencies when we are using them
  correctly — please report those upstream.
- Issues that require a compromised device, root/jailbreak, or physical
  access to a user's unlocked phone.
- Findings from automated scanners with no demonstrated impact.

If you believe the threat model itself is wrong — i.e. that an attack we
treat as accepted should not be accepted, or that a stated guarantee is
unachievable as specified — that is in scope and we want to hear about
it. Frame the report as a critique of the spec, not just the code.

## Safe harbor

We will not pursue legal action against researchers who:

- Make a good-faith effort to follow this policy
- Avoid privacy violations, data destruction, and service disruption
- Give us reasonable time to address an issue before public disclosure
