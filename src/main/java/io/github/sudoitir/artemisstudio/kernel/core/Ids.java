package io.github.sudoitir.artemisstudio.kernel.core;

import java.util.UUID;
import org.hibernate.id.uuid.UuidVersion7Strategy;

/**
 * Primary keys made where the row is written. A UUIDv7 starts with the time, so inserts land at the right edge of the
 * key's index. Entities get theirs from {@code @UuidGenerator(style = VERSION_7)}, which uses the same generator; call
 * this when the id is needed before the insert. A bearer handle (a ticket or snapshot id that is itself the
 * credential) stays a random v4 UUID, because a v7 id is partly predictable.
 */
public final class Ids {

    private Ids() {}

    public static UUID v7() {
        return UuidVersion7Strategy.INSTANCE.generateUuid(null);
    }
}
