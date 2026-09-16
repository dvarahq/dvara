# Security Policy

DVARA sits in the request path for LLM traffic — security is the product. We welcome reports from
the security community and run a coordinated disclosure process with safe harbor for good-faith
research.

## Reporting a vulnerability

**Please report privately — do not open a public GitHub issue, pull request, or discussion for a
security issue.** Two channels, either is fine:

1. **GitHub Private Vulnerability Reporting** (preferred) — the **"Report a vulnerability"** button
   under this repository's **Security** tab. This opens a private advisory only you and the
   maintainers can see.
2. **Email** — [`security@dvarahq.com`](mailto:security@dvarahq.com), also listed at
   `https://dvarahq.com/.well-known/security.txt`.

**Please include**, as far as you can: affected component and version, a description and impact,
reproduction steps or a proof-of-concept, and any logs or configuration that help us reproduce.
**Do not include third-party data, other workspaces' data, or real secrets** — redact them.

## Our commitment (response SLA)

| Stage | Target |
|---|---|
| **Acknowledge** your report | within **3 business days** |
| **Triage + initial severity** (CVSS 3.1) | within **7 business days** |
| **Remediation target** — Critical / High | **30 days** |
| **Remediation target** — Medium / Low | **90 days** |
| **Coordinated public disclosure** | by mutual agreement, typically **≤ 90 days** after triage |

We will keep you updated, credit you in the advisory and release notes (unless you prefer to remain
anonymous), and let you know when a fix ships. If a report is out of scope or a duplicate, we will
tell you why.

## Safe harbor

We consider security research and vulnerability disclosure conducted under this policy to be
**authorized, good-faith conduct**. We will not pursue or support legal action against you, and will
work to protect you from third-party action, provided you:

- make a good-faith effort to avoid privacy violations, data destruction, and service disruption;
- test only against **your own** DVARA instance — never someone else's, and never a shared
  production environment you do not own;
- **do not** run denial-of-service, spam, brute-force, resource-exhaustion, or social-engineering
  attacks, and do not physically attack infrastructure or staff;
- **stop** and report as soon as you encounter another party's data, credentials, or PII — do not
  access, copy, modify, retain, or exfiltrate it;
- give us reasonable time to remediate before any public disclosure.

This policy authorizes testing of software you run yourself. It does **not** grant authorization to
test DVARA-operated hosted environments without prior written agreement — contact us first.

## Scope

**In scope** — everything in this repository:

- the **data plane** (`/v1/*`) — API-key authentication, routing, provider dispatch, streaming;
- the **request pipeline** — the policy, PII, guardrail, output-schema and context-window filters,
  and any way to get a request past one of them;
- **PII detection and redaction** — a value that should have been redacted reaching a provider;
- **guardrails** — injection and content detection, and bypasses of either;
- **credential handling** — provider secret resolution, and any path that leaks one into a log, an
  error message, a response, or a trace.

**Out of scope** — reports we generally consider non-issues:

- findings that require a **non-default, explicitly insecure** configuration the documentation warns
  against (for example running with authentication disabled, or the Mock provider on a production
  profile, which executes Groovy in the gateway JVM by design);
- denial of service, volumetric, or resource-exhaustion issues;
- missing security headers or best-practice hardening with no demonstrated exploit;
- social engineering, phishing, physical access, or attacks on our hosted and marketing
  infrastructure and third-party services (GitHub, the documentation site, email);
- reports from automated scanners with no working proof-of-concept.

**Not in this repository.** Some DVARA components are not published here. The audit chain this
build writes to a file **is** here and in scope; the pipeline that collects and verifies it across
a fleet is not. Report an issue in a component that is not published here through the same channels
above — say which component, and note that because the source is not public, a proof-of-concept and
reproduction steps matter more than usual. The same SLA and the same safe harbor apply.

## Supported versions

Security fixes land on the **latest released minor** and the **immediately previous minor**. Older
versions are end-of-life; upgrade to a supported release rather than patching in place.

| Version | Supported |
|---|---|
| Latest minor (current release line) | ✅ security fixes |
| Previous minor | ✅ security fixes |
| Older | ❌ end-of-life |
