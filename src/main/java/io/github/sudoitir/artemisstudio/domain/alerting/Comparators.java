package io.github.sudoitir.artemisstudio.domain.alerting;

/** The six comparators {@code alert_rule.comparator}'s CHECK allows. */
final class Comparators {

    private Comparators() {}

    static boolean test(String comparator, double value, double threshold) {
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
