---
title: Data governance
description: How Studio masks sensitive message values everywhere it shows or stores them, detects personal data, and lets a granted role see originals — with every clear view audited.
---

# Data governance

Studio reads your brokers' messages and puts them in front of people, API clients and assistants. It also
stores copies of them: in the message index, from capture, and as request-reply payloads. Data governance is
the one policy that decides, for every header, property and body value, what each of those audiences may see
and what Studio may keep.

It is on for every installation and cannot be disabled. The defaults are conservative: credentials are never
shown or stored, and recognisable personal data is masked until someone with the right to see it asks.

## What is masked, and how

Every sensitive value belongs to a **data class**. The class decides what happens to it unless a rule says
otherwise.

| Class | How it is found | What is shown |
|---|---|---|
| Credential | Built-in rules and a bearer-token / JWT detector | `[dropped credential]` — never shown, never stored, to anyone |
| Payment card number | 13–19 digits that pass the Luhn check | `[payment card number ending 4242]` |
| IBAN | Country code, check digits, mod-97 check | `[IBAN ending 3000]` |
| Email | Address pattern | `[redacted email]` |
| Phone number | International `+…` or grouped ten-digit numbers | `[redacted phone number]` |
| National identifier | Rules only — formats differ by country | `[redacted national identifier]` |
| Personal data | Rules only | `[redacted personal data]` |

A masked value is always labelled as masked in the interface, in words, so it is never mistaken for the real
value. Wherever message content appears — message detail, SQL results, request-reply flows — each value
carries its class.

### Built-in credential rules

These ship enabled and match case-insensitively:

- properties named `Authorization`, `Proxy-Authorization`, `Cookie`, `Set-Cookie`;
- any property whose name contains `password`, `secret`, `token`, `api-key` or `api_key`;
- any value, in any field, that is a bearer token or shaped like a JSON Web Token.

A built-in rule can be disabled, never deleted. Disabling one is audited. Before you disable one, consider
that a credential in a message is a live key to another system, not just personal data.

## Rules

A rule masks a **header**, a **property** (a name, with `*` matching any run of characters) or a **JSON body
path** (dotted, with `[*]` for array elements, such as `items[*].email`). It names a class, and optionally an
action that overrides the class default and an **address pattern** (`orders.#`, `orders.*`) that limits it to
some addresses.

Rules are managed under **Administration → Masking rules**, which needs `governance:read` to view and
`governance:write` to change. Every change is audited and moves the policy to a new version.

## Detection and findings

Studio also runs its detectors over every property value and body value it can read, whether or not a rule
names the field. A value the detectors recognise is **masked immediately** — the policy does not wait for
someone to review it.

Each such detection in a field no rule covers is recorded as a **finding**: the address, the field, the class,
when it was first and last seen, and how often. The finding never records the value. Findings are written in
batches, not once per message.

## Content that cannot be inspected

A binary body, or a body in a format Studio cannot read, cannot be classified, so it is **withheld** from
anyone without clear access, with the reason shown in its place. The same applies to the part of a body
beyond the scan limit, `governance.scan-limit` (256 KiB by default, under **Settings**), and the notice names
that setting.

## Clear access

The `message:clear` permission shows sensitive values in clear at the scope it is granted — globally, for an
environment, or for one cluster. Credentials stay dropped even then. A value shown in clear is still marked as
sensitive, so the person reading it knows it is.

Every response that served a sensitive value in clear writes a `VIEW_CLEAR` audit event with the caller, the
cluster, the target, and the classes and counts served — never the values. A SQL console query records the same
as `clearFields` on its own audit event.

Wildcard grants include `message:clear`: a role holding `*` (the built-in ADMIN) or `message:*` sees clear
values.

### Separating duties

If the people who administer Studio should not see personal data, do not give them `*`. Create a custom role
with the administration permissions they need — `user:admin`, `settings:write`, `cluster:write`,
`governance:write` and so on — and grant `message:clear` only to a role for the people whose work requires it,
scoped to the clusters where they need it.

## The SQL console

- Result rows, live tails and CSV/JSON exports carry masked values exactly as they are shown.
- A query that filters or orders on a field a masking rule covers is **refused** for a caller without
  `message:clear`, and the refusal names the field. A predicate is an oracle: evaluating it would reveal the
  value the policy hides.
- A body search from a caller without clear access is evaluated against the **masked** body, so it cannot
  confirm a value the caller would only ever see masked.
- Literal values compared with classified fields are masked in the audited query text.

## What Studio stores

The message index, capture and request-reply payloads store the **masked** form. Full-text and `LIKE` search
over the index therefore only ever see masked text: searching the index for a stored email address finds
nothing.

The originals of masked values — except credentials, which are never kept — are **sealed** beside the row,
encrypted with Studio's secret key and bound to that row. They are opened only to answer a caller with
`message:clear`. If the key is lost, the originals cannot be recovered; the masked rows remain readable.

An index query that names a field stored masked says so: the predicate compares masked text, and the live
broker holds the originals.

### When the policy changes

A new or changed rule applies to every read immediately, including rows stored earlier. In the background, a
re-masking job rewrites stored rows under the new policy in bounded batches, sealing newly masked originals.
The **Masking rules** screen shows how many stored messages are still masked under an earlier version.

## Upgrading

- Rows stored before this release are masked on every read at once; the re-masking job then brings the stored
  copies into line. Until it reports zero, older rows still hold their original values on disk.
- Users who could read full message content with `message:read` now see sensitive values masked. Grant
  `message:clear` to the roles that need originals.
- Request-reply payloads need `message:read`; `cluster:read` alone shows flows without their payloads.
- An invalid browse filter is reported without repeating its text.
