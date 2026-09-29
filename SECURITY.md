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
