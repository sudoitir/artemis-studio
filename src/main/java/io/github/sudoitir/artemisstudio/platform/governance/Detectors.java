package io.github.sudoitir.artemisstudio.platform.governance;

import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Value detectors (ADR-0075 D3): checksums where the format has one, conservative patterns where
 * it does not. National identifiers and personal data come from rules only — their formats vary
 * too much to detect honestly.
 */
final class Detectors {

    /** One detector: a candidate pattern and the check a candidate must pass. Order is priority. */
    record Detector(DataClass dataClass, Pattern pattern, Predicate<String> check) {}

    static final List<Detector> ALL = List.of(
            new Detector(
                    DataClass.CREDENTIAL,
                    Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._~+/-]+=*|\\beyJ[\\w-]{5,}\\.[\\w-]{5,}\\.[\\w-]*"),
                    s -> true),
            // Card numbers start 2-6, which keeps epoch-millisecond timestamps out; a leading + is a phone number.
            new Detector(
                    DataClass.PAN,
                    Pattern.compile("(?<![\\d.+])[2-6]\\d(?:[ -]?\\d){11,17}(?![\\d.])"),
                    Detectors::luhn),
            new Detector(
                    DataClass.IBAN, Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]){11,30}\\b"), Detectors::mod97),
            new Detector(
                    DataClass.EMAIL,
                    Pattern.compile(
                            "\\b[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63})*\\.[A-Za-z]{2,24}\\b"),
                    s -> true),
            new Detector(
                    DataClass.PHONE,
                    Pattern.compile("(?<![\\w+])\\+\\d{8,15}\\b|(?<!\\d)\\(?\\d{3}\\)?[ .-]\\d{3}[ .-]\\d{4}(?!\\d)"),
                    s -> true));

    /** A detected span of a string. */
    record Hit(DataClass dataClass, int start, int end) {}

    private Detectors() {}

    /** The first detector that matches anywhere in {@code value}, or null. */
    static DataClass classify(String value) {
        for (Detector detector : ALL) {
            Matcher m = detector.pattern().matcher(value);
            while (m.find()) {
                if (detector.check().test(m.group())) {
                    return detector.dataClass();
                }
            }
        }
        return null;
    }

    /** Every detected span, scanning detector by detector; later detectors skip spans already claimed. */
    static List<Hit> scan(String text) {
        java.util.ArrayList<Hit> hits = new java.util.ArrayList<>();
        for (Detector detector : ALL) {
            Matcher m = detector.pattern().matcher(text);
            while (m.find()) {
                int start = m.start();
                int end = m.end();
                if (detector.check().test(m.group())
                        && hits.stream().noneMatch(h -> h.start() < end && start < h.end())) {
                    hits.add(new Hit(detector.dataClass(), start, end));
                }
            }
        }
        hits.sort(java.util.Comparator.comparingInt(Hit::start));
        return hits;
    }

    static boolean luhn(String candidate) {
        String digits = candidate.replaceAll("[ -]", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubled = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubled) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubled = !doubled;
        }
        return sum % 10 == 0;
    }

    static boolean mod97(String candidate) {
        String iban = candidate.replace(" ", "");
        if (iban.length() < 15 || iban.length() > 34) {
            return false;
        }
        String rearranged = iban.substring(4) + iban.substring(0, 4);
        int remainder = 0;
        for (char c : rearranged.toCharArray()) {
            int value = Character.isDigit(c) ? c - '0' : c - 'A' + 10;
            remainder = value > 9 ? (remainder * 100 + value) % 97 : (remainder * 10 + value) % 97;
        }
        return remainder == 1;
    }
}
