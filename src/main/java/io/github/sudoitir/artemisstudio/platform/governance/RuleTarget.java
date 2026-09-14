package io.github.sudoitir.artemisstudio.platform.governance;

/** What part of a message a rule names. */
public enum RuleTarget {
    HEADER(Location.HEADER),
    PROPERTY(Location.PROPERTY),
    /** A JSON body path, dotted, with {@code [*]} for array elements. */
    BODY_PATH(Location.BODY);

    private final Location location;

    RuleTarget(Location location) {
        this.location = location;
    }

    public Location location() {
        return location;
    }
}
