package io.github.sudoitir.artemisstudio.kernel.core;

import org.springframework.security.access.AccessDeniedException;

/**
 * A caller who may read a queue or address, and may not do this to it. Mapped to HTTP 403 with the
 * stable problem {@code resource-forbidden}, which names the permission and the resource so the
 * console can say what is missing. A caller who may not read the resource gets
 * {@link NotFoundException} instead, never this.
 */
public class ResourceForbiddenException extends AccessDeniedException {

    private final String permission;
    private final String kind;
    private final String name;

    public ResourceForbiddenException(String permission, String kind, String name) {
        this(permission, kind, name, "You do not hold " + permission + " on " + kind + " " + name + ".");
    }

    public ResourceForbiddenException(String permission, String kind, String name, String message) {
        super(message);
        this.permission = permission;
        this.kind = kind;
        this.name = name;
    }

    public String permission() {
        return permission;
    }

    /** {@code queue} or {@code address}. */
    public String kind() {
        return kind;
    }

    public String name() {
        return name;
    }
}
