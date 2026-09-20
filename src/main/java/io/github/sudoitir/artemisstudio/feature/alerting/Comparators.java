package io.github.sudoitir.artemisstudio.feature.alerting;

/**
 * The six comparators {@code alert_rule.comparator}'s CHECK allows.
 *
 * <p>Public because an {@link AlertCondition} may live in another module (ADR-0089), and
 * a condition that hand-rolled its own comparison would drift from what the rule's stored
 * comparator means everywhere else.
 */
public final class Comparators {

    private Comparators() {}

    public static boolean test(String comparator, double value, double threshold) {
        return switch (comparator) {
            case "GT" -> value > threshold;
            case "GTE" -> value >= threshold;
            case "LT" -> value < threshold;
            case "LTE" -> value <= threshold;
            case "EQ" -> value == threshold;
            case "NE" -> value != threshold;
            default -> throw new IllegalArgumentException("unknown comparator: " + comparator);
        };
    }
}
