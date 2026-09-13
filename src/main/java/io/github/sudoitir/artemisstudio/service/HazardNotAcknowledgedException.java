package io.github.sudoitir.artemisstudio.service;

import java.util.List;

/** A real run asked for without every High hazard acknowledged by its identifier (ADR-0067 D7). */
public class HazardNotAcknowledgedException extends RuntimeException {

    private final List<String> missing;

    public HazardNotAcknowledgedException(List<String> missing) {
        super("Acknowledge " + missing.size() + (missing.size() == 1 ? " hazard" : " hazards") + " before applying: "
                + String.join(", ", missing));
        this.missing = List.copyOf(missing);
    }

    public List<String> missing() {
        return missing;
    }
}
