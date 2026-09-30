# Security Policy

## Reporting a vulnerability

Do **not** open a public issue, discussion or pull request for a security problem.

Report it privately through GitHub's private vulnerability reporting:
<https://github.com/sudoitir/artemis-studio/security/advisories/new>. Include a description,
the affected version (the image tag or the About dialog) or commit, and a minimal
reproduction.

What happens next:

1. **Within 3 working days**, we acknowledge the report.
2. **Within 10 working days**, we confirm the vulnerability or explain why it is not one, and
   agree a disclosure date with you.
3. The fix ships in a release, and we publish a
   [GitHub security advisory](https://github.com/sudoitir/artemis-studio/security/advisories)
   crediting you, unless you prefer otherwise. We aim to disclose within 90 days of the report.

Please give us that window before any public disclosure.

## How we respond to a vulnerability

Once a report is confirmed, or a vulnerability is found in a dependency or in the image:

| Severity (CVSS v3.1) | Patched release |
| --- | --- |
| Critical (9.0–10.0) | within 7 days of confirmation |
| High (7.0–8.9) | within 30 days |
| Medium and low | in the next routine release |

1. **Triage** within 3 working days: reproduce it, score it with CVSS, and decide whether Artemis
   Studio is affected (a vulnerable dependency on an unreachable path is recorded as not affected).
2. **Fix** in a private fork of the advisory, so nothing is public before the release.
3. **Release**: the fix is merged and ships as the next CalVer release, like every release, through
   the release workflow, signed and with its SBOM and provenance
   ([verify a release](https://sudoitir.github.io/artemis-studio/guide/verify-releases)). Before the
   first stable release there are no backports: the patched release is the latest.
4. **Advisory**: we publish a
   [GitHub security advisory](https://github.com/sudoitir/artemis-studio/security/advisories) with a
   CVE requested through GitHub, naming the affected versions, the first fixed version, any
   workaround, and the reporter unless they prefer otherwise.

Every pull request that changes the image fails on a critical or high vulnerability that has a fix,
and dependency manifests are scanned on every change and weekly.

## Scope

Artemis Studio holds broker credentials and can perform destructive operations on
message brokers. Of particular interest:

- Credential handling (storage, encryption at rest, exposure in logs or API
  responses).
- Authentication and authorization bypass, privilege escalation across
  environment/cluster scopes, read-only mode bypass.
- SSRF via cluster/broker URL fields.
- Missing audit records for mutating actions.
- Injection into management calls sent to brokers.

## Supported versions

Before the first stable release, only the latest release is supported: fixes ship in the next
release, and there are no backports. See the
[releases](https://github.com/sudoitir/artemis-studio/releases).
