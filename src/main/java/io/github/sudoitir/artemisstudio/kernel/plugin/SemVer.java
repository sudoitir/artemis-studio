package io.github.sudoitir.artemisstudio.kernel.plugin;

/** Orders plugin versions, {@code major.minor.patch[-pre][+build]} (design.md §1). */
public final class SemVer {

    private SemVer() {}

    /**
     * Numeric by component, a missing component counting as {@code 0}; a pre-release sorts before
     * its release ({@code 1.0.0-rc.1 < 1.0.0}). A component that is not a number falls back to
     * comparing the whole strings, so a malformed version never throws.
     */
    public static int compare(String a, String b) {
        String[] pa = a.split("\\+", 2)[0].split("-", 2);
        String[] pb = b.split("\\+", 2)[0].split("-", 2);
        String[] na = pa[0].split("\\.");
        String[] nb = pb[0].split("\\.");
        for (int i = 0; i < Math.max(na.length, nb.length); i++) {
            Long va = i < na.length ? parse(na[i]) : Long.valueOf(0);
            Long vb = i < nb.length ? parse(nb[i]) : Long.valueOf(0);
            if (va == null || vb == null) {
                return a.compareTo(b);
            }
            if (!va.equals(vb)) {
                return Long.compare(va, vb);
            }
        }
        boolean preA = pa.length > 1;
        boolean preB = pb.length > 1;
        if (preA != preB) {
            return preA ? -1 : 1;
        }
        return preA ? pa[1].compareTo(pb[1]) : 0;
    }

    private static Long parse(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
