# ADR-0104: Studio restarts itself for a plugin, but only when something will start it again

- **Status**: accepted
- **Date**: 2026-09-23
- **Deciders**: Mahdi Amirabdollahi
- **Change**: `openspec/changes/plugins`
- **Amends**: [ADR-0099](0099-runtime-plugins-are-child-contexts-installed-from-the-ui.md) — its "Studio states the command and never restarts itself"
- **Builds on**: [ADR-0103](0103-plugin-installer-tier-and-step-up-reauthentication.md)

## Context

Most plugin changes take effect without a restart (ADR-0099's Instant and Brief-maintenance classes). A restart is still needed in three cases:

- A plugin declares `activation: restart`.
- A running version does not stop within its drain time.
- A stopped version's class loader is never garbage-collected.

ADR-0099 had Studio show the restart command and nothing more. That leaves the operator to find a shell on the host at the moment they have just confirmed a change in the UI.

Two ways to restart from inside Studio exist, and both have problems:

- **The actuator `restart` endpoint (Spring Cloud Commons)** refreshes the application context inside the same JVM. It therefore never frees a plugin class loader or its metaspace, which is the reason for the restart. Exposing restart or shutdown over HTTP is also attack surface Studio does not otherwise need.
- **Exiting the process** frees everything, but it is only a restart if something starts the process again. Under a plain `docker run` without `--restart`, or a developer's `just dev`, exiting would simply stop Studio.

## Decision

- **A restart is a graceful exit.** Studio closes every plugin, records a clean stop in `studio_boot` (so the crash-loop guard does not count it), and exits with code 75 after a 3-second delay so the response reaches the browser. The code is non-zero so that a supervisor configured to restart only on failure still restarts it.
- **Only when supervised.** Studio exits only when `artemis-studio.plugins.restart.supervised` is true, or, when that property is unset, when it runs on Kubernetes (detected by Boot's `CloudPlatform`). Both compose files set the property next to `restart: unless-stopped`, and the two lines are documented as a pair. Everywhere else, Studio refuses to exit and shows the command.
- **Automatic only with consent.** The review of a restart-class activation says in advance whether Studio will restart itself (`AUTOMATIC`) or the operator must restart it (`MANUAL`). Confirming an `AUTOMATIC` plan is the consent: the version is recorded, and the next boot starts it.
- **A manual "Restart Studio"** covers the leaked and stuck cases. It has the same guards as every other plugin action (ADR-0103): an installer, a browser session, a step-up within 5 minutes, and an audit row.
  - It is refused within 2 minutes of a boot, so a compromised session cannot hold Studio in a restart loop.
  - A second request while a restart is pending does nothing.
- **Leak detection.** Every closed version's class loader is held weakly. If a loader is still reachable a minute after close, Studio lists it as "still in memory" and marks a restart as needed. Studio asks the JVM for a collection before deciding, at most every 10 minutes and only while such a candidate exists.
- **`-XX:MaxMetaspaceSize=256m`** in the compose `JAVA_OPTS` bounds the damage from a leak until the restart happens.

## Consequences

- An operator who confirms a restart-class plugin sees Studio come back with the plugin running, and never needs a shell.
- Every signed-in user is disconnected for as long as Studio takes to start. The review states this before confirmation.
- A deployment that restarts Studio some other way (systemd, a custom orchestrator) sets `restart.supervised=true` itself. Until it does, it gets the command, never an outage.
- Removing `restart: unless-stopped` without also removing the property would leave Studio down after a plugin restart. The compose files say this next to both lines.

## Alternatives considered

- **Actuator `restart`/`shutdown` over HTTP.** Rejected: an in-JVM refresh does not free leaked class loaders, and the endpoints add attack surface.
- **Always exiting and relying on the operator to have a supervisor.** Rejected: under `docker run` without `--restart`, a plugin install would take Studio down.
- **Restarting automatically without consent whenever a restart is needed** (for example, on a detected leak). Rejected: it would disconnect everyone at a moment nobody chose.
