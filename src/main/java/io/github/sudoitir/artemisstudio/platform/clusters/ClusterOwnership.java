package io.github.sudoitir.artemisstudio.platform.clusters;

import io.github.sudoitir.artemisstudio.kernel.core.ShutdownPhases;
import io.github.sudoitir.artemisstudio.kernel.replica.DrainStarted;
import io.github.sudoitir.artemisstudio.kernel.replica.HaProperties;
import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaRegistry;
import io.github.sudoitir.artemisstudio.kernel.replica.ReplicaSignal;
import io.github.sudoitir.artemisstudio.kernel.replica.StudioBus;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Which replica runs a cluster's broker duties (ADR-0152, design D2). Every cluster has one owner:
 * the ready replica with the highest rendezvous hash of (cluster, replica), so owners spread over
 * the replicas and a join or a leave moves only the clusters that must move. The owner holds a row
 * of {@code cluster_lease}.
 *
 * <p>A thread of its own, not the job pool (a starved pool must not make a replica lose its
 * clusters), runs one transaction every {@code artemis-studio.ha.heartbeat}, all on database time:
 * it renews the leases it holds, expires those whose rendezvous owner is another ready replica,
 * and takes leases that are missing or expired and that it should own, or that nobody has taken for
 * one more ttl, which covers an owner that is gone and a replica that never ticks. A
 * {@link ReplicaSignal} of kind {@code leases}, sent when a replica drains, registers a cluster or
 * hands clusters over, runs a tick at once.
 *
 * <p>{@link #owns} is the gate duties use. It also fences: a replica that has not renewed for
 * {@code ttl - ttl/3} (10 s by default, on the monotonic clock) stops claiming ownership before its
 * lease can be taken. A draining replica expires its leases on {@link DrainStarted}.
 *
 * <p>{@link ClusterDutyAcquired} and {@link ClusterDutyReleased} are published locally, on the
 * ownership thread, when the set changes; a listener that blocks must hand its work off.
 */
// ponytail: no fencing tokens. A pause longer than the fence margin (a long GC, a frozen VM) can let
// the old owner finish one duplicate pass after a peer took over; tokens on every broker write would
// close that, and the work is idempotent enough that one extra pass is cheaper.
@Component
@Slf4j
public class ClusterOwnership implements SmartLifecycle {

    private static final ReplicaSignal LEASES = new ReplicaSignal("leases", null);
    private static final Duration DEBOUNCE = Duration.ofMillis(100);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ReplicaRegistry replicas;
    private final StudioBus bus;
    private final HaProperties ha;
    private final ApplicationEventPublisher events;
    private static final Duration NOT_READY_POLL = Duration.ofMillis(250);

    /** Permits are wake-ups asked for since the last tick; the tick drains them all, so they never pile up. */
    private final Semaphore wake = new Semaphore(0);

    private final AtomicReference<Set<UUID>> owned = new AtomicReference<>(Set.of());
    private volatile long renewedAt = System.nanoTime();
    private final AtomicReference<Thread> thread = new AtomicReference<>();

    public ClusterOwnership(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactions,
            ReplicaRegistry replicas,
            StudioBus bus,
            HaProperties ha,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactions);
        this.replicas = replicas;
        this.bus = bus;
        this.ha = ha;
        this.events = events;
    }

    /** Whether this replica runs the cluster's duties now: it holds the lease and renewed it recently. */
    public boolean owns(UUID clusterId) {
        return fresh() && owned.get().contains(clusterId);
    }

    /** The clusters {@link #owns} is true for. */
    public Set<UUID> owned() {
        return fresh() ? owned.get() : Set.of();
    }

    private boolean fresh() {
        return System.nanoTime() - renewedAt
                < ha.ttl().minus(ha.ttl().dividedBy(3)).toNanos();
    }

    /** The owner among {@code candidates}: the highest score, the larger id on a tie. */
    static UUID ownerOf(UUID clusterId, Set<UUID> candidates) {
        UUID best = null;
        long bestScore = 0;
        for (UUID replica : candidates) {
            long score = score(clusterId, replica);
            if (best == null || score > bestScore || (score == bestScore && replica.compareTo(best) > 0)) {
                best = replica;
                bestScore = score;
            }
        }
        return best;
    }

    private static long score(UUID cluster, UUID replica) {
        long h = mix(cluster.getMostSignificantBits() ^ mix(replica.getMostSignificantBits()));
        h = mix(h ^ cluster.getLeastSignificantBits());
        return mix(h ^ replica.getLeastSignificantBits());
    }

    /** The splitmix64 finalizer. */
    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // ---- the tick ----------------------------------------------------------------------------------

    private record Row(UUID clusterId, boolean expired, boolean orphaned) {}

    /** One renew, release and acquire pass. Does nothing unless this replica is ready. */
    synchronized void tick() {
        if (replicas.state() != ReplicaRegistry.State.READY) {
            return;
        }
        long started = System.nanoTime();
        try {
            Set<UUID> now = tx.execute(_ -> rebalance());
            renewedAt = started;
            apply(now);
        } catch (RuntimeException e) {
            log.warn("Cluster ownership tick failed: {}", e.toString());
            if (!fresh()) {
                apply(Set.of());
            }
        }
    }

    private Set<UUID> rebalance() {
        UUID me = replicas.id();
        double ttl = ha.ttl().toMillis() / 1000.0;
        Set<UUID> ready = new HashSet<>();
        ready.add(me);
        replicas.live().stream()
                .filter(r -> r.state() == ReplicaRegistry.State.READY)
                .forEach(r -> ready.add(r.id()));

        Set<UUID> mine = new HashSet<>(jdbc.queryForList("""
                UPDATE cluster_lease SET expires_at = now() + make_interval(secs => ?)
                WHERE replica_id = ? AND expires_at > now() RETURNING cluster_id
                """, UUID.class, ttl, me));
        List<Row> rows = jdbc.query(
                """
                SELECT c.id, coalesce(l.expires_at <= now(), true) AS expired,
                       coalesce(l.expires_at, c.created_at) <= now() - make_interval(secs => ?) AS orphaned
                FROM cluster c LEFT JOIN cluster_lease l ON l.cluster_id = c.id
                """,
                (rs, _) -> new Row(rs.getObject("id", UUID.class), rs.getBoolean("expired"), rs.getBoolean("orphaned")),
                ttl);

        Set<UUID> result = new HashSet<>();
        List<UUID> handedOver = new ArrayList<>();
        for (Row row : rows) {
            boolean desired = me.equals(ownerOf(row.clusterId(), ready));
            if (mine.contains(row.clusterId())) {
                if (desired) {
                    result.add(row.clusterId());
                } else {
                    handedOver.add(row.clusterId());
                }
            } else if (row.expired() && (desired || row.orphaned()) && acquire(row.clusterId(), me, ttl)) {
                result.add(row.clusterId());
            }
        }
        handedOver.forEach(id -> jdbc.update(
                "UPDATE cluster_lease SET expires_at = now() WHERE cluster_id = ? AND replica_id = ?", id, me));
        if (!handedOver.isEmpty()) {
            bus.publish(LEASES);
        }
        return result;
    }

    /** Whether the lease is now this replica's; losing the race to another replica is not an error. */
    private boolean acquire(UUID clusterId, UUID me, double ttl) {
        return jdbc.update("""
                        INSERT INTO cluster_lease (expires_at, replica_id, cluster_id)
                        SELECT now() + make_interval(secs => ?), ?, id FROM cluster WHERE id = ?
                        ON CONFLICT (cluster_id) DO UPDATE
                        SET expires_at = EXCLUDED.expires_at, replica_id = EXCLUDED.replica_id
                        WHERE cluster_lease.expires_at <= now()
                        """, ttl, me, clusterId) > 0;
    }

    private void apply(Set<UUID> now) {
        Set<UUID> before = owned.getAndSet(Set.copyOf(now));
        before.stream().filter(id -> !now.contains(id)).forEach(id -> publish(new ClusterDutyReleased(id)));
        now.stream().filter(id -> !before.contains(id)).forEach(id -> publish(new ClusterDutyAcquired(id)));
    }

    private void publish(Object event) {
        try {
            events.publishEvent(event);
        } catch (RuntimeException e) {
            log.warn("A listener of {} failed: {}", event, e.toString());
        }
    }

    // ---- hand-over ---------------------------------------------------------------------------------

    /** A draining replica gives its clusters up at once, so a peer takes them within a tick. */
    @EventListener
    synchronized void onDrain(DrainStarted drain) {
        try {
            tx.executeWithoutResult(_ -> {
                jdbc.update("UPDATE cluster_lease SET expires_at = now() WHERE replica_id = ?", replicas.id());
                bus.publish(LEASES);
            });
        } catch (RuntimeException e) {
            log.warn("Could not release the cluster leases of replica {}: {}", replicas.id(), e.toString());
        }
        apply(Set.of());
    }

    @EventListener
    void onSignal(ReplicaSignal signal) {
        if ("leases".equals(signal.kind())) {
            wake.release();
        }
    }

    /** A new cluster has no owner yet: every replica ticks, and the one that should own it takes it. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onRegistered(ClusterRegistered registered) {
        announce();
    }

    /** Wakes every replica's tick, this one's included. */
    private void announce() {
        try {
            bus.publish(LEASES);
        } catch (RuntimeException e) {
            log.warn("Could not ask the replicas to rebalance: {}", e.toString());
        }
    }

    // ---- lifecycle ---------------------------------------------------------------------------------

    @Override
    public void start() {
        if (thread.get() != null) {
            return;
        }
        Thread t = new Thread(this::run, "studio-cluster-ownership");
        t.setDaemon(true);
        thread.set(t);
        t.start();
    }

    @Override
    public void stop() {
        Thread t = thread.getAndSet(null);
        if (t != null) {
            t.interrupt();
            try {
                t.join(Duration.ofSeconds(2));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return thread.get() != null;
    }

    /** With the jobs: no tick after shutdown has stopped the work it would hand out. */
    @Override
    public int getPhase() {
        return ShutdownPhases.JOBS;
    }

    private void run() {
        boolean ready = false;
        while (thread.get() == Thread.currentThread()) {
            if (!ready && replicas.state() == ReplicaRegistry.State.READY) {
                ready = true;
                // Just joined: the others hand over this replica's share now rather than on their next tick.
                announce();
            }
            tick();
            try {
                // Until ready, look often, so a replica takes up its duties as soon as it can serve.
                long wait = ready ? ha.heartbeat().toMillis() : NOT_READY_POLL.toMillis();
                if (wake.tryAcquire(wait, TimeUnit.MILLISECONDS)) {
                    Thread.sleep(DEBOUNCE);
                    wake.drainPermits();
                }
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
