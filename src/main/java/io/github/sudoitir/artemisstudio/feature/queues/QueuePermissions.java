package io.github.sudoitir.artemisstudio.feature.queues;

/** Permission strings this module checks (ADR-0038); declared in its module descriptor. */
public final class QueuePermissions {

    public static final String QUEUE_CREATE = "queue:create";

    public static final String QUEUE_DELETE = "queue:delete";

    public static final String QUEUE_UPDATE = "queue:update";

    public static final String QUEUE_PAUSE = "queue:pause";

    private QueuePermissions() {}
}
