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

## Scope

In scope:

- The Ktor server (`server/`) and its mailbox API
- The shared KMP module (`shared/`), including the Double Ratchet
  implementation and session/pairing logic
- The Android and iOS clients (`android/`, `ios/`)
- The CLI (`cli/`)
- Build and release tooling that ships code to end users
  (GitHub Actions workflows, fastlane lanes)

Out of scope:

- Vulnerabilities in third-party dependencies, unless we are using them in
  an unsafe way — please report those upstream
- Issues that require a compromised device, root/jailbreak, or physical
  access to a user's unlocked phone
- Trust-On-First-Use of session fingerprints. This is an accepted design
  trade-off; see `docs/e2ee-location-sync.md`
- Findings from automated scanners with no demonstrated impact

## Cryptographic protocol

The full E2EE protocol is documented in
[`docs/e2ee-location-sync.md`](docs/e2ee-location-sync.md). Reports that
identify deviations from that spec, or weaknesses in the spec itself, are
especially welcome.

## Safe harbor

We will not pursue legal action against researchers who:

- Make a good-faith effort to follow this policy
- Avoid privacy violations, data destruction, and service disruption
- Give us reasonable time to address an issue before public disclosure
