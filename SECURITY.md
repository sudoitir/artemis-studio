# Security Policy

## Reporting a vulnerability

Do **not** open a public issue for a security problem.

Use GitHub's **private vulnerability reporting** on this repository
(Security → Report a vulnerability), or email the maintainers if that is not
available. Include a description, affected version/commit, and a minimal
reproduction. Please give us a reasonable window to respond before any public
disclosure.

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

Pre-alpha: only the `main` branch is supported. Once releases exist, this section
will list supported version ranges.
