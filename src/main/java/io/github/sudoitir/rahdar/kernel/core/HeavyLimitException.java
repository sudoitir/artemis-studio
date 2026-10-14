package io.github.sudoitir.rahdar.kernel.core;

/**
 * A heavy request refused because a limit is full: the caller's own, or the installation's. Mapped to
 * HTTP 429 with the limit named, so the caller knows what to wait for.
 */
public class HeavyLimitException extends RuntimeException {

    private final String endpoint;
    private final int cap;
    private final boolean installation;

    private HeavyLimitException(String message, String endpoint, int cap, boolean installation) {
        super(message);
        this.endpoint = endpoint;
        this.cap = cap;
        this.installation = installation;
    }

    /** The caller already has {@code cap} requests of {@code endpoint} running. */
    static HeavyLimitException perUser(String endpoint, int cap) {
        return new HeavyLimitException(
                "You already have %d %s running. Wait for one to finish, or stop it.".formatted(cap, endpoint),
                endpoint,
                cap,
                false);
    }

    /** Every user together have {@code cap} heavy requests running, the most this installation allows. */
    static HeavyLimitException installation(String endpoint, int cap) {
        return new HeavyLimitException(
                "Rahdar is already serving %d heavy requests, the most it runs at once. Try again shortly."
                        .formatted(cap),
                endpoint,
                cap,
                true);
    }

    /** What the refused request was. */
    public String endpoint() {
        return endpoint;
    }

    /** The limit that was full. */
    public int cap() {
        return cap;
    }

    /** Whether the installation-wide limit was full, rather than the caller's own. */
    public boolean installation() {
        return installation;
    }
}
