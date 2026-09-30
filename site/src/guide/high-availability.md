---
title: High availability
description: Run two or more Artemis Studio replicas behind a load balancer on one Postgres, with probes, graceful drain and failover.
---

# High availability

Studio replicas are stateless: several of them behave as one when they share a Postgres database and
sit behind a load balancer. Sessions, settings, cluster ownership and in-flight operations live in the
database, and stream events reach every replica through it, so the load balancer needs no sticky
sessions.

## The reference deployment

`deploy/compose/compose.ha.yaml` runs two replicas, HAProxy and Postgres, and CI kills a replica in it on
every change to the image:

```bash
cp deploy/compose/.env.example .env      # set DB_*, SECRET_KEY
docker compose -f deploy/compose/compose.ha.yaml --env-file .env up -d
```

Studio is on `http://127.0.0.1:8080` (set `STUDIO_BIND` to change it). The second replica starts after
the first is ready, so schema migrations run once. Postgres itself is a single container here: its
availability is yours to provide (a managed service, Patroni, …).

Every replica must use the same `ARTEMIS_STUDIO_SECRET_KEY` and the same database.

## What each replica does

- **Requests and streams**: any replica serves any request. A stream event raised on one replica
  reaches the clients of every replica within a second, and a client that reconnects to another replica
  replays the broker events it missed.
- **Broker work**: each registered cluster is owned by one replica, which scrapes it, holds its
  notification subscriptions, samples it and reconciles its capture. Clusters are spread evenly across
  the ready replicas, so adding a replica spreads the load instead of multiplying it.
- **Installation-wide jobs** (housekeeping, partition maintenance, sweeps) run once per tick on whichever
  replica takes them.

Settings → **Studio health** lists the replicas, their state, when each last checked in, and the
clusters each one owns. Broker-node figures on that screen are as seen from the replica answering.

## Timings

| Event | Time |
| --- | --- |
| The load balancer stops routing to a dead replica | about 2 s (`inter 1s`, `fall 2`) |
| A dead replica's clusters are taken over | within 20 s |
| Their scraping and subscriptions run again | within 25 s |
| A replica that shuts down hands its clusters over | within 1 s |
| A new replica takes its share of clusters | within 10 s of being ready |
| A setting changed on one replica is used by all | within 2 s |

The heartbeat, lease lifetime, drain delay and run grace are `artemis-studio.ha.heartbeat` (5 s),
`artemis-studio.ha.ttl` (15 s), `artemis-studio.ha.drain-delay` (5 s) and `artemis-studio.ha.run-grace` (20 s).

## Probes

| Path | Answers 200 when | Use it for |
| --- | --- | --- |
| `/livez` | the process is running | liveness, and the startup probe |
| `/readyz` | the replica is ready, reaches the other replicas, and is not draining | readiness, and the load balancer's health check |

Neither depends on a broker: a broker outage never restarts or unroutes Studio. Until migrations have
run, neither probe answers. Give the startup probe enough time for the first boot's migrations.

## Shutting down and rolling restarts

On `SIGTERM` a replica:

1. fails `/readyz`, gives its clusters to the other replicas, and waits `drain-delay` for the load
   balancer to notice;
2. tells its stream clients to reconnect (they do at once, to another replica, without losing events)
   and stops accepting new streams;
3. gives running bulk operations and transfers 20 s to finish, then stops them and records them as
   interrupted with their progress;
4. stops its jobs, writes buffered records and closes its broker connections.

Allow at least 45 s between `SIGTERM` and `SIGKILL` (`stop_grace_period` in compose,
`terminationGracePeriodSeconds` in Kubernetes). Restart replicas one at a time and wait for each to be
ready again.

If a replica dies instead, its running operations are recorded as interrupted within a minute, and its
clusters move to the others as in the table above.

## Your own load balancer

Any HTTP load balancer works if it:

- checks `GET /readyz` and removes a replica after one or two failures;
- does not buffer `GET /api/v1/stream` (Server-Sent Events): `proxy_buffering off` in nginx; HAProxy and
  Traefik work as they are;
- keeps idle connections open for longer than 20 s, the stream's keep-alive interval;
- needs no session affinity.

`deploy/compose/ha/haproxy.cfg` is a working example.

## Limits

- Each replica holds one extra database connection, used to listen for the others.
- The per-node limit on management calls applies per replica for calls an operator starts; scheduled
  polling has one owner per cluster.
- Plugin metric series move between replicas along with cluster ownership, so scrape `/actuator/prometheus`
  on every replica.
- A plugin that needs a restart restarts the replica it was installed on; the others pick up the change
  without a restart when they can.
