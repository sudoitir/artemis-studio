package io.github.sudoitir.rahdar.kernel.core;

import java.util.regex.Pattern;

/**
 * A table or column name that is put into SQL text, where a bound parameter cannot stand for it. Only plain
 * lower-case names pass, so a name that came from a constant or from the catalog is quoted and anything else
 * is refused before it reaches the database.
 */
public final class SqlIdentifier {

    private static final Pattern PLAIN = Pattern.compile("[a-z_][a-z0-9_]*");

    private SqlIdentifier() {}

    /** {@code name} in double quotes; throws for anything but {@code [a-z_][a-z0-9_]*}. */
    public static String quote(String name) {
        if (name == null || !PLAIN.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a plain SQL identifier: " + name);
        }
        return '"' + name + '"';
    }
}
