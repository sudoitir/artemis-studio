# ADR-0182: Each user has an inbox, and a stream of their own signalled over the replica bus

- **Status**: accepted
- **Date**: 2026-10-07
- **Deciders**: Mahdi Amirabdollahi

## Context

Approvers must learn that a request waits for them, and requesters that it was decided or ran,
whatever page they are on and whichever replica serves them. Studio's live updates are per
cluster ([ADR-0018](0018-sse-hub.md), `GET /api/v1/stream?clusterId`); nothing is addressed to one
user. Replicas already coordinate through Postgres `LISTEN/NOTIFY`, which sends on commit and can
drop messages when a listener reconnects ([ADR-0152](0152-replicas-coordinate-through-postgres.md)).
Other features and plugins will want the same per-user notices.

## Decision

1. **The inbox is a table, one row per recipient.** `inbox_item` has a link CHECK that allows
   in-app paths only (`^/[A-Za-z0-9]`, no `//` or `\`), size CHECKs on title (200), body (2000) and
   data (4 KB), a unique `(recipient, source, dedupe_key)`, a partial unread index and an expiry
   index. A post is one `INSERT … SELECT unnest(:ids) ON CONFLICT … DO UPDATE`. The unread count is
   bounded at 100 and shown as "99+". Items are kept 90 days, read items 30.
2. **The API acts on the caller's rows only:** `GET /api/v1/inbox` (keyset paging),
   `GET /api/v1/inbox/count`, `POST /api/v1/inbox/read`, `DELETE /api/v1/inbox/{id}`.
3. **Each user has one stream,** `GET /api/v1/me/stream`, at most 5 per user, reusing the
   existing subscriber, heartbeat and the closes on session end and token revocation.
4. **The bus only signals.** A post publishes a replica signal naming the user inside the posting
   transaction; every replica sends that user's subscribers a frame with no data, and the client
   refetches. Held-operation changes work the same way. After the bus resumes, clients resync.
   The tables are the truth.
5. **Plugins post through a scoped `@PluginApi Inbox`:** `post`, `postToHolders(notice,
   permission, clusterId, exclude)` over live permission holders among enabled users, and
   `resolve(dedupeKey, newTitle)`. The source is the plugin id and cannot be chosen.
6. **Approval items are posted by Studio** in the hold and decision transactions, so they arrive
   for any provider.

## Consequences

- A lost bus message costs a refetch at the next signal or resync, never a lost notice.
- Nothing personal crosses the bus, so a frame cannot leak to another user.
- Per-recipient rows cost storage per post, capped by at most 500 recipients per post and by
  retention.
- Plugins gain a notice channel they cannot use to impersonate Studio or send links off-site.

## Alternatives considered

- **Fan-out on read** (one notice, recipients computed when read). Rejected: recipient counts are
  small, and a read state column per user keeps every read to one index scan.
- **Carry the item in the frame.** Rejected: the bus can drop messages and is not access-checked;
  a signal plus a refetch is both reliable and safe.
- **Reuse the per-cluster stream.** Rejected: notices are not about a cluster, and a user may have
  no cluster page open.
- **A broker or Redis for pub/sub.** Rejected: Postgres already does this for the replicas.
