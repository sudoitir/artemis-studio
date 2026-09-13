### Upgrading to this release

This release reorganises Studio into modules, and the database schema with it. An
existing database **cannot be upgraded**: Studio has to start against an empty one.
Several changes below each carry their own note; this is the order to do them in.

**Before you stop the old version**

1. Remove every message capture (Settings → Message index, subscriptions of kind
   *capture*) and wait for the next reconcile pass to report them gone. A capture's
   divert and queue on the broker are named after this Studio's instance id, which is
   kept in the database; a new database gets a new id, and the new Studio will never
   remove what the old one installed. If you have already upgraded, delete the
   `artemis-studio.capture.<old-id>.*` diverts and queues on each broker by hand.
2. Write down what you will re-create: registered clusters and their credentials,
   environments, users, roles and grants, API tokens (and which assistants use them),
   notification channels, alert rules, request-reply expectations, message index
   subscriptions, declared broker configuration, and identity provider group mappings.

**Upgrade**

3. Stop Studio, then drop and recreate its database (or remove the Postgres volume).
4. Rename `artemis-studio.rr.clock-skew-tolerance-ms` to
   `artemis-studio.broker.clock-skew-tolerance-ms` if you set it, and delete
   `artemis-studio.branding.product-name`, `artemis-studio.security.session-timeout`
   and `artemis-studio.security.oidc-default-role`, which no longer exist.
5. Start the new version and sign in as `admin` with the password it prints once.

**After**

6. Register the clusters and re-create what you wrote down. Built-in alert rules are
   seeded again for each cluster. Issue new API keys and update every assistant or
   script that used an old one.
7. With OIDC configured, set each provider's group mappings and default role under
   Administration → Group mappings. Until then, a user whose groups match no mapping
   is refused sign-in.
8. API clients: group mappings moved from `/api/v1/oidc/mappings` to
   `/api/v1/identity/providers/{providerId}/group-mappings`; `POST /api/v1/auth/login`
   takes an optional `provider`; `GET /api/v1/auth/providers` returns
   `{ id, kind, label, startPath }`; the connection check's `recommendations` is now
   `contributions.brokerconfig`.

Optional features can now be turned off with `artemis-studio.features.<id>.enabled=false`;
see the configuration guide for the ids.
