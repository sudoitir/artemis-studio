package io.github.sudoitir.artemisstudio.broker.capture;

import io.github.sudoitir.artemisstudio.service.RoutingService;
import java.util.UUID;

/**
 * The names of the broker objects a tap owns (ADR-0062 D5).
 *
 * <p>{@code artemis-studio.capture.<instance>.<source address>.<subscription>}. Three
 * things have to be readable back out of that name, because the reconciler's only
 * view of actual state is the list of names a broker reports:
 *
 * <ul>
 *   <li><b>the instance</b>, so one Studio never destroys another's tap;
 *   <li><b>the subscription</b>, so an orphan is recognised as one;
 *   <li><b>the source address</b>, so a drained message can be attributed to the
 *       address it was copied from without depending on a broker header (D2).
 * </ul>
 *
 * <p>The subscription id goes last rather than second, which is the one place this
 * departs from the shape sketched in the design. An Artemis address routinely
 * contains dots — {@code ORDER.IN} is the ordinary case — so the address cannot be a
 * fixed segment; putting the fixed-width UUID at the end makes the address exactly
 * "everything in between" and the parse unambiguous.
 */
public final class CaptureNames {

    /** Address-settings and security-settings match covering every capture object. */
    public static final String MATCH = RoutingService.CAPTURE_DIVERT_PREFIX + "#";

    private CaptureNames() {}

    /**
     * The tap's name. The divert carries it as-is; the queue and its address take
     * {@link #queueOf}.
     */
    public static String of(String instanceId, String address, UUID subscriptionId) {
        return RoutingService.CAPTURE_DIVERT_PREFIX + instanceId + '.' + address + '.' + subscriptionId;
    }

    /**
     * The capture queue and the address it is bound to.
     *
     * <p>Not the same string as the divert's, and it cannot be. A queue binds under
     * its own name, and Artemis refuses to deploy a divert whose name is already a
     * binding — {@code AMQ222006: Binding already exists with name …, divert will not
     * be deployed} — which it reports as a 200 with a line in its own log and nothing
     * else. Measured on 2.56.0. The suffix still falls under the
     * {@code artemis-studio.capture.#} match, so one address setting and one security
     * setting cover both.
     */
    public static String queueOf(String name) {
        return name + ".q";
    }

    /** Whether this name is a capture object belonging to this instance. */
    public static boolean ownedBy(String name, String instanceId) {
        return name != null && name.startsWith(RoutingService.CAPTURE_DIVERT_PREFIX + instanceId + '.');
    }

    /** The subscription a capture object serves, or null when the name does not parse. */
    public static UUID subscriptionOf(String name) {
        int lastDot = name == null ? -1 : name.lastIndexOf('.');
        if (lastDot < 0) {
            return null;
        }
        try {
            return UUID.fromString(name.substring(lastDot + 1));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** The source address a capture object copies from, or null when the name does not parse. */
    public static String addressOf(String name, String instanceId) {
        if (!ownedBy(name, instanceId) || subscriptionOf(name) == null) {
            return null;
        }
        int start = RoutingService.CAPTURE_DIVERT_PREFIX.length() + instanceId.length() + 1;
        int end = name.lastIndexOf('.');
        return end <= start ? null : name.substring(start, end);
    }
}
